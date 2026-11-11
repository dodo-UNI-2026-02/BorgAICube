package cubo.comun;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Cliente TCP del protocolo propio, compartido por el cliente de escritorio, el
 * servidor web (puente HTTP→TCP) y el generador de carga.
 *  - Un hilo lector despacha cada trama: las respuestas síncronas (LOGIN_*, ACK,
 *    HISTORIAL, ERROR de una petición) completan un CompletableFuture; los
 *    RESULTADO/ERROR asíncronos van al listener alEvento.
 *  - Las escrituras se sincronizan para que dos hilos no intercalen tramas.
 */
public class ConexionBackend implements Closeable {
    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Object lockEscritura = new Object();
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> esperando = new ConcurrentHashMap<>();
    private final Consumer<Map<String, Object>> alEvento;
    private final String plataforma;
    private volatile boolean cerrada;
    public volatile String token = "";
    public volatile long idCliente;
    public volatile String nombre = "";

    public ConexionBackend(String host, int puerto, String plataforma, Consumer<Map<String, Object>> alEvento)
            throws IOException {
        this.plataforma = plataforma;
        this.alEvento = alEvento;
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, puerto), 10000);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
        out = new BufferedOutputStream(socket.getOutputStream(), 65536);
        Thread lector = new Thread(this::leer, "lector-backend");
        lector.setDaemon(true);
        lector.start();
        Thread ping = new Thread(() -> {
            while (!cerrada) {
                Util.dormir(15000);
                try { enviar(Json.obj("tipo", "PING", "timestamp", System.currentTimeMillis())); } catch (IOException e) { break; }
            }
        }, "ping-backend");
        ping.setDaemon(true);
        ping.start();
    }

    public boolean abierta() { return !cerrada; }

    public void enviar(Map<String, Object> msg) throws IOException {
        String json = Json.stringify(msg);
        synchronized (lockEscritura) {
            Trama.escribir(out, json);
        }
    }

    private void leer() {
        try {
            while (true) {
                Map<String, Object> m = Json.parseObjeto(Trama.leer(in));
                String tipo = Json.str(m, "tipo");
                switch (tipo) {
                    case "LOGIN_OK":
                    case "LOGIN_ERROR":
                        completar("LOGIN", m);
                        break;
                    case "ACK":
                    case "HISTORIAL":
                        completar(Json.str(m, "idMensajeCliente"), m);
                        break;
                    case "ERROR":
                        if (!completar(Json.str(m, "idMensajeCliente"), m) && alEvento != null) alEvento.accept(m);
                        break;
                    case "PONG":
                        break;
                    default:
                        if (alEvento != null) alEvento.accept(m);
                }
            }
        } catch (Exception e) {
            cerrada = true;
            for (CompletableFuture<Map<String, Object>> f : esperando.values())
                f.complete(Json.obj("tipo", "ERROR", "codigo", "DESCONECTADO", "mensaje", "Conexión con el backend cerrada"));
            if (alEvento != null) alEvento.accept(Json.obj("tipo", "DESCONECTADO", "mensaje", String.valueOf(e.getMessage())));
        }
    }

    private boolean completar(String clave, Map<String, Object> m) {
        CompletableFuture<Map<String, Object>> f = esperando.remove(clave);
        if (f == null) return false;
        f.complete(m);
        return true;
    }

    private Map<String, Object> peticion(String clave, Map<String, Object> msg, long timeoutMs) throws IOException {
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        esperando.put(clave, f);
        try {
            enviar(msg);
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return Json.obj("tipo", "ERROR", "codigo", "TIMEOUT", "mensaje", "El backend no respondió a tiempo");
        } catch (Exception e) {
            throw new IOException(e);
        } finally {
            esperando.remove(clave);
        }
    }

    private Map<String, Object> autenticar(Map<String, Object> msg) throws IOException {
        Map<String, Object> r = peticion("LOGIN", msg, 20000);
        if ("LOGIN_OK".equals(Json.str(r, "tipo"))) {
            token = Json.str(r, "token");
            idCliente = Json.num(r, "idCliente", 0);
            nombre = Json.str(r, "nombre");
        }
        return r;
    }

    public Map<String, Object> login(String usuario, String password) throws IOException {
        return autenticar(Json.obj("tipo", "LOGIN", "usuario", usuario, "password", password,
                "plataforma", plataforma, "timestamp", System.currentTimeMillis()));
    }

    public Map<String, Object> registro(String usuario, String password, String nombre) throws IOException {
        return autenticar(Json.obj("tipo", "REGISTRO", "usuario", usuario, "password", password, "nombre", nombre,
                "plataforma", plataforma, "timestamp", System.currentTimeMillis()));
    }

    /** Envía una imagen y espera el ACK (o el ERROR de validación). El RESULTADO llega luego por alEvento. */
    public Map<String, Object> consultaImagen(String idMensajeCliente, String modelo, String formato, byte[] imagen,
                                              String texto) throws IOException {
        return consultaImagenBase64(idMensajeCliente, modelo, formato, Base64.getEncoder().encodeToString(imagen), texto);
    }

    public Map<String, Object> consultaImagenBase64(String idMensajeCliente, String modelo, String formato,
                                                    String base64, String texto) throws IOException {
        if (idMensajeCliente == null) idMensajeCliente = UUID.randomUUID().toString();
        return peticion(idMensajeCliente, Json.obj("tipo", "CONSULTA", "token", token, "idCliente", idCliente,
                "idMensajeCliente", idMensajeCliente, "tipoConsulta", "IMAGEN", "modelo", modelo, "formato", formato,
                "payload", base64, "texto", texto == null ? "" : texto, "plataforma", plataforma,
                "timestamp", System.currentTimeMillis()), 30000);
    }

    public Map<String, Object> historial(int limite) throws IOException {
        String id = UUID.randomUUID().toString();
        return peticion(id, Json.obj("tipo", "CONSULTA", "token", token, "idCliente", idCliente,
                "idMensajeCliente", id, "tipoConsulta", "HISTORIAL", "limite", limite,
                "timestamp", System.currentTimeMillis()), 20000);
    }

    @Override
    public void close() {
        cerrada = true;
        try { socket.close(); } catch (IOException ignorado) { }
    }

    /** "png" o "jpg" según la extensión / cabecera del archivo. */
    public static String formatoDe(String nombre, byte[] datos) {
        if (datos.length > 3 && (datos[0] & 0xff) == 0x89 && datos[1] == 'P' && datos[2] == 'N') return "png";
        if (datos.length > 2 && (datos[0] & 0xff) == 0xFF && (datos[1] & 0xff) == 0xD8) return "jpg";
        String n = nombre.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ? "jpg" : "png";
    }
}
