package cubo.backend;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import cubo.comun.Json;
import cubo.comun.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Puente entre el backend y RabbitMQ.
 *  - Un Channel NO es thread-safe para publicar: todas las publicaciones pasan por
 *    una LinkedBlockingQueue que consume UN hilo publicador con su propio canal y
 *    publisher confirms (se confirma por lotes).
 *  - Consumidores en canales separados: cola_respuestas, cola_heartbeat y la cola
 *    exclusiva de respuestas RPC (validaciones en BD2 e historial en BD1/BD3).
 */
public class PuenteRabbit {
    public static final String EX_CUBO = "ex_cubo", EX_HISTORIAL = "ex_historial",
            EX_CONTROL = "ex_control", EX_DLX = "ex_dlx";
    static final String[] COLAS_NEGOCIO = {"cola_modeloA", "cola_modeloB", "cola_resultadosA", "cola_resultadosB",
            "cola_validacion", "cola_respuestas", "cola_historialA", "cola_historialB", "cola_entrenamiento"};
    static final String[][] COLAS_CONTROL = {{"cola_heartbeat", "heartbeat.#"}, {"cola_metricas", "metricas.#"},
            {"cola_logs", "logs.#"}};

    private static final class Publicacion {
        final String exchange, rk, correlationId, replyTo;
        final byte[] cuerpo;
        final boolean persistente;

        Publicacion(String ex, String rk, byte[] c, boolean p, String cid, String rt) {
            exchange = ex; this.rk = rk; cuerpo = c; persistente = p; correlationId = cid; replyTo = rt;
        }
    }

    private final Connection conn;
    private final LinkedBlockingQueue<Publicacion> cola = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> rpcPendientes = new ConcurrentHashMap<>();
    private final String colaRpc;

    public PuenteRabbit(String url, Consumer<Map<String, Object>> alRespuesta, Consumer<Map<String, Object>> alHeartbeat)
            throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setUri(url);
        f.setAutomaticRecoveryEnabled(true);
        f.setRequestedHeartbeat(30);
        Connection c = null;
        for (int intento = 1; c == null; intento++) {
            try {
                c = f.newConnection("backend-" + Util.ipLocal());
            } catch (Exception e) {
                if (intento >= 20) throw e;
                Util.log("WARN", "RabbitMQ no disponible (" + e.getMessage() + "); reintento en 3 s");
                Util.dormir(3000);
            }
        }
        conn = c;
        Channel ch = conn.createChannel();
        declararTopologia(ch);
        ch.close();

        // hilo publicador con su propio canal y confirmaciones
        Channel chPub = conn.createChannel();
        chPub.confirmSelect();
        Thread pub = new Thread(() -> publicar(chPub), "publicador-rabbit");
        pub.setDaemon(true);
        pub.start();

        // respuestas RPC
        Channel chRpc = conn.createChannel();
        colaRpc = chRpc.queueDeclare("", false, true, true, null).getQueue();
        chRpc.basicConsume(colaRpc, true, new DefaultConsumer(chRpc) {
            @Override
            public void handleDelivery(String tag, Envelope env, AMQP.BasicProperties p, byte[] cuerpo) {
                CompletableFuture<Map<String, Object>> fut = rpcPendientes.remove(p.getCorrelationId());
                if (fut != null) {
                    try { fut.complete(Json.parseObjeto(new String(cuerpo, StandardCharsets.UTF_8))); }
                    catch (Exception e) { fut.completeExceptionally(e); }
                }
            }
        });

        // respuestas finales de los nodos BD
        Channel chResp = conn.createChannel();
        chResp.basicQos(64);
        chResp.basicConsume("cola_respuestas", false, new DefaultConsumer(chResp) {
            @Override
            public void handleDelivery(String tag, Envelope env, AMQP.BasicProperties p, byte[] cuerpo) throws IOException {
                try {
                    alRespuesta.accept(Json.parseObjeto(new String(cuerpo, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    Util.log("ERROR", "respuesta inválida: " + e.getMessage());
                }
                getChannel().basicAck(env.getDeliveryTag(), false);
            }
        });

        // heartbeats de todos los nodos
        Channel chHb = conn.createChannel();
        chHb.basicConsume("cola_heartbeat", true, new DefaultConsumer(chHb) {
            @Override
            public void handleDelivery(String tag, Envelope env, AMQP.BasicProperties p, byte[] cuerpo) {
                try { alHeartbeat.accept(Json.parseObjeto(new String(cuerpo, StandardCharsets.UTF_8))); }
                catch (Exception ignorado) { }
            }
        });
    }

    /** Declaración idempotente, idéntica a Go (rmq.go) y Python (consumidor.py). */
    public static void declararTopologia(Channel ch) throws IOException {
        ch.exchangeDeclare(EX_CUBO, "direct", true);
        ch.exchangeDeclare(EX_HISTORIAL, "direct", true);
        ch.exchangeDeclare(EX_CONTROL, "topic", true);
        ch.exchangeDeclare(EX_DLX, "fanout", true);
        Map<String, Object> dlx = new HashMap<>();
        dlx.put("x-dead-letter-exchange", EX_DLX);
        for (String q : COLAS_NEGOCIO) {
            ch.queueDeclare(q, true, false, false, dlx);
            ch.queueBind(q, EX_CUBO, q);
        }
        ch.queueBind("cola_historialA", EX_HISTORIAL, "A");
        ch.queueBind("cola_historialB", EX_HISTORIAL, "B");
        Map<String, Object> ttl = new HashMap<>();
        ttl.put("x-message-ttl", 60000);
        for (String[] c : COLAS_CONTROL) {
            ch.queueDeclare(c[0], true, false, false, ttl);
            ch.queueBind(c[0], EX_CONTROL, c[1]);
        }
        ch.queueDeclare("cola_errores", true, false, false, null);
        ch.queueBind("cola_errores", EX_DLX, "");
    }

    private void publicar(Channel ch) {
        List<Publicacion> lote = new ArrayList<>();
        while (true) {
            try {
                lote.clear();
                lote.add(cola.take());
                cola.drainTo(lote, 199);
                for (Publicacion p : lote) {
                    AMQP.BasicProperties.Builder b = new AMQP.BasicProperties.Builder()
                            .contentType("application/json").deliveryMode(p.persistente ? 2 : 1)
                            .timestamp(new java.util.Date());
                    if (p.correlationId != null) b.correlationId(p.correlationId);
                    if (p.replyTo != null) b.replyTo(p.replyTo);
                    ch.basicPublish(p.exchange, p.rk, b.build(), p.cuerpo);
                }
                ch.waitForConfirmsOrDie(10000); // publisher confirms por lote
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Util.log("ERROR", "fallo al publicar en RabbitMQ: " + e.getMessage());
                Util.dormir(1000);
            }
        }
    }

    public void publicar(String exchange, String rk, Map<String, Object> cuerpo, boolean persistente) {
        cola.add(new Publicacion(exchange, rk, Json.stringify(cuerpo).getBytes(StandardCharsets.UTF_8), persistente,
                null, null));
    }

    /** Petición RPC asíncrona (reply_to + correlation_id). */
    public CompletableFuture<Map<String, Object>> rpc(String exchange, String rk, Map<String, Object> cuerpo) {
        String cid = UUID.randomUUID().toString();
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        rpcPendientes.put(cid, f);
        cola.add(new Publicacion(exchange, rk, Json.stringify(cuerpo).getBytes(StandardCharsets.UTF_8), false, cid, colaRpc));
        f.whenComplete((r, e) -> rpcPendientes.remove(cid));
        return f;
    }

    /** RPC con espera; ante timeout devuelve null. */
    public Map<String, Object> rpcSincrono(String exchange, String rk, Map<String, Object> cuerpo, long timeoutMs) {
        CompletableFuture<Map<String, Object>> f = rpc(exchange, rk, cuerpo);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return null;
        }
    }

    public int pendientesPublicacion() { return cola.size(); }

    public void cerrar() {
        try { conn.close(); } catch (Exception ignorado) { }
    }
}
