package cubo.backend;

import cubo.comun.Args;
import cubo.comun.Json;
import cubo.comun.Util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servidor central: recibe a los 3 clientes por sockets TCP propios, valida en
 * BD2, enruta las imágenes a RabbitMQ y devuelve la respuesta al cliente.
 *
 *   java -cp "out:lib/*" cubo.backend.Backend --puerto 5000 --rabbit amqp://cubo:cubo123@IP:5672/cubo
 */
public class Backend {
    static final AtomicLong enviadas = new AtomicLong(), respondidas = new AtomicLong(), rechazadas = new AtomicLong(),
            errores = new AtomicLong(), timeouts = new AtomicLong();

    final ConcurrentHashMap<String, Sesion> sesiones = new ConcurrentHashMap<>();
    final Set<Sesion> conexiones = ConcurrentHashMap.newKeySet();
    final Map<String, Map<String, Object>> nodos = new ConcurrentHashMap<>();
    final Map<String, Long> vistoNodo = new ConcurrentHashMap<>();
    final Correlador correlador;
    final ExecutorService clientes, procesadores;
    PuenteRabbit rabbit;
    final String nombre = "backend-" + Util.ipLocal();

    Backend(int hilos, long timeoutMs) {
        correlador = new Correlador(timeoutMs);
        clientes = Executors.newCachedThreadPool(r -> hilo(r, "cliente"));    // 1 hilo por conexión
        procesadores = Executors.newFixedThreadPool(hilos, r -> hilo(r, "consulta"));
    }

    private static Thread hilo(Runnable r, String prefijo) {
        Thread t = new Thread(r, prefijo);
        t.setDaemon(true);
        return t;
    }

    /** Respuesta final de un nodo BD (cola_respuestas) → cliente correspondiente. */
    void alRespuesta(Map<String, Object> r) {
        String id = Json.str(r, "idSolicitud");
        Correlador.Pendiente p = correlador.quitar(id);
        if (p == null) return; // ya expiró (timeout) o es un duplicado
        String estado = Json.str(r, "estado");
        if ("OK".equals(estado)) {
            Map<String, Object> res = Json.mapa(r, "resultado");
            List<Object> clases = Json.lista(res, "clases");
            List<Object> probs = Json.lista(res, "probabilidades");
            Map<String, Object> mapa = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(clases.size(), probs.size()); i++) mapa.put(String.valueOf(clases.get(i)), probs.get(i));
            p.sesion.enviar(Json.obj("tipo", "RESULTADO", "idSolicitud", id, "idMensajeCliente", p.idMensajeCliente,
                    "modelo", p.modelo, "etiqueta", Json.str(res, "etiqueta"),
                    "indiceClase", Json.num(res, "indiceClase", -1), "probabilidad", Json.dbl(res, "probabilidad", 0),
                    "probabilidades", mapa, "nodoIA", Json.str(res, "nodoIA"), "nodoBD", Json.str(r, "nodoBD"),
                    "tiempoInferenciaMs", Json.num(res, "tiempoInferenciaMs", 0),
                    "latenciaTotalMs", System.currentTimeMillis() - p.recibido, "timestamp", System.currentTimeMillis()));
            respondidas.incrementAndGet();
        } else {
            String codigo = Json.str(r, "codigo");
            p.sesion.enviar(ManejadorCliente.error(id, p.idMensajeCliente, codigo.isEmpty() ? "VALIDACION" : codigo,
                    Json.str(r, "motivo")));
            if ("RECHAZADO".equals(estado)) rechazadas.incrementAndGet(); else errores.incrementAndGet();
        }
    }

    void alHeartbeat(Map<String, Object> hb) {
        String n = Json.str(hb, "nodo");
        boolean nuevo = !nodos.containsKey(n);
        nodos.put(n, hb);
        vistoNodo.put(n, System.currentTimeMillis());
        if (nuevo) Util.log("INFO", "nodo detectado: " + n + " (" + Json.str(hb, "rol") + ", " + Json.str(hb, "lenguaje")
                + ", " + Json.str(hb, "so") + ", " + Json.str(hb, "ip") + ")");
    }

    void metricas() {
        long n = 0;
        while (true) {
            Util.dormir(1000);
            long ahora = System.currentTimeMillis();
            if (n++ % 5 == 0) rabbit.publicar(PuenteRabbit.EX_CONTROL, "heartbeat." + nombre, Json.obj(
                    "nodo", nombre, "rol", "BACKEND", "lenguaje", "Java", "so", System.getProperty("os.name"),
                    "ip", Util.ipLocal(), "hilos", ((ThreadPoolExecutor) procesadores).getMaximumPoolSize(),
                    "timestamp", ahora), false);
            rabbit.publicar(PuenteRabbit.EX_CONTROL, "metricas." + nombre, Json.obj(
                    "nodo", nombre, "rol", "BACKEND", "cpuPct", Util.cpuProceso(), "memMB", Util.memoriaMB(),
                    "hilosActivos", ((ThreadPoolExecutor) procesadores).getActiveCount() + conexiones.size(),
                    "procesados", respondidas.get(), "errores", errores.get() + timeouts.get(),
                    "pendientes", correlador.cantidad(), "timestamp", ahora), false);
        }
    }

    void consola() {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("Comandos: nodos | sesiones | pendientes | metricas | salir");
        try {
            String l;
            while ((l = r.readLine()) != null) {
                switch (l.trim()) {
                    case "nodos":
                        long ahora = System.currentTimeMillis();
                        List<String> nombres = new ArrayList<>(nodos.keySet());
                        nombres.sort(null);
                        for (String n : nombres) {
                            Map<String, Object> hb = nodos.get(n);
                            long hace = (ahora - vistoNodo.get(n)) / 1000;
                            System.out.printf("  %-24s %-8s %-7s %-10s %-15s hace %3d s %s%n", n, Json.str(hb, "rol"),
                                    Json.str(hb, "lenguaje"), Json.str(hb, "so"), Json.str(hb, "ip"), hace,
                                    hace > 15 ? "(CAÍDO)" : "");
                        }
                        break;
                    case "sesiones":
                        for (Sesion s : conexiones) System.out.println("  " + s.describir());
                        System.out.println("  conexiones: " + conexiones.size() + ", autenticadas: " + sesiones.size());
                        break;
                    case "pendientes":
                        System.out.println("  solicitudes esperando respuesta: " + correlador.cantidad()
                                + " | por publicar: " + rabbit.pendientesPublicacion());
                        break;
                    case "metricas":
                        System.out.printf("  enviadas %d | respondidas %d | rechazadas %d | errores %d | timeouts %d | CPU %.1f%% | mem %.1f MB%n",
                                enviadas.get(), respondidas.get(), rechazadas.get(), errores.get(), timeouts.get(),
                                Util.cpuProceso(), Util.memoriaMB());
                        break;
                    case "salir":
                        rabbit.cerrar();
                        System.exit(0);
                        break;
                    case "":
                        break;
                    default:
                        System.out.println("Comandos: nodos | sesiones | pendientes | metricas | salir");
                }
            }
        } catch (Exception ignorado) { }
    }

    public static void main(String[] argv) throws Exception {
        Args a = new Args(argv);
        int puerto = a.entero("puerto", "CUBO_BACKEND_PUERTO", 5000);
        int hilos = a.entero("hilos", "CUBO_HILOS", 2 * Runtime.getRuntime().availableProcessors());
        long timeout = a.entero("timeout", 30) * 1000L;
        Backend b = new Backend(hilos, timeout);
        b.rabbit = new PuenteRabbit(a.get("rabbit", "CUBO_RABBIT_URL", Args.rabbitPorDefecto()), b::alRespuesta, b::alHeartbeat);
        Thread m = new Thread(b::metricas, "metricas");
        m.setDaemon(true);
        m.start();
        ServerSocket ss = new ServerSocket(puerto, 200);
        Util.log("INFO", "Backend escuchando en TCP " + puerto + " (" + Util.ipLocal() + ") | " + hilos
                + " hilos de consulta | timeout " + timeout / 1000 + " s");
        Thread aceptador = new Thread(() -> {
            while (true) {
                try {
                    Socket s = ss.accept();
                    b.clientes.submit(new ManejadorCliente(s, b));
                } catch (Exception e) {
                    Util.log("ERROR", "accept: " + e.getMessage());
                }
            }
        }, "aceptador");
        aceptador.start();
        if (!a.tiene("sin-consola")) b.consola();
        aceptador.join();
    }
}
