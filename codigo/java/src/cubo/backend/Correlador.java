package cubo.backend;

import cubo.comun.Json;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Relaciona cada idSolicitud publicado en RabbitMQ con la sesión que debe
 * recibir la respuesta. ConcurrentHashMap.remove es atómico: si la respuesta y
 * el timeout llegan a la vez, solo uno de los dos "gana" y el cliente recibe
 * un único mensaje.
 */
public class Correlador {
    public static final class Pendiente {
        final Sesion sesion;
        final String idMensajeCliente, modelo;
        final long recibido;

        Pendiente(Sesion s, String idm, String modelo) {
            sesion = s; idMensajeCliente = idm; this.modelo = modelo; recibido = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, Pendiente> pendientes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService barrido = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "timeouts");
        t.setDaemon(true);
        return t;
    });

    public Correlador(long timeoutMs) {
        barrido.scheduleAtFixedRate(() -> {
            long ahora = System.currentTimeMillis();
            for (Map.Entry<String, Pendiente> e : pendientes.entrySet()) {
                if (ahora - e.getValue().recibido > timeoutMs) {
                    Pendiente p = pendientes.remove(e.getKey());
                    if (p != null) {
                        p.sesion.enviar(Json.obj("tipo", "ERROR", "idSolicitud", e.getKey(),
                                "idMensajeCliente", p.idMensajeCliente, "codigo", "TIMEOUT",
                                "mensaje", "Sin respuesta en " + (timeoutMs / 1000) + " s"));
                        Backend.timeouts.incrementAndGet();
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void registrar(String idSolicitud, Sesion s, String idMensajeCliente, String modelo) {
        pendientes.put(idSolicitud, new Pendiente(s, idMensajeCliente, modelo));
    }

    public Pendiente quitar(String idSolicitud) { return pendientes.remove(idSolicitud); }

    public int cantidad() { return pendientes.size(); }
}
