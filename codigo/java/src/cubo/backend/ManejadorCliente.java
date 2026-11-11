package cubo.backend;

import cubo.comun.Json;
import cubo.comun.Trama;
import cubo.comun.Util;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Atiende una conexión TCP: lee tramas y despacha. Las consultas (que esperan
 * una validación RPC en BD2) se ejecutan en el pool de procesadores para que el
 * cliente pueda seguir enviando mensajes mientras tanto.
 */
public class ManejadorCliente implements Runnable {
    private final Socket socket;
    private final Backend b;

    public ManejadorCliente(Socket socket, Backend b) {
        this.socket = socket;
        this.b = b;
    }

    @Override
    public void run() {
        Sesion s;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(45000); // sin PING ni mensajes en 45 s → se cierra
            s = new Sesion(socket);
        } catch (Exception e) {
            return;
        }
        b.conexiones.add(s);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536))) {
            while (true) {
                String texto;
                try {
                    texto = Trama.leer(in);
                } catch (Trama.TramaGrandeException e) {
                    s.enviar(error(null, null, "TRAMA_GRANDE", e.getMessage()));
                    break;
                }
                Map<String, Object> m;
                try {
                    m = Json.parseObjeto(texto);
                } catch (IllegalArgumentException e) {
                    s.enviar(error(null, null, "FORMATO", e.getMessage()));
                    continue;
                }
                despachar(s, m);
            }
        } catch (EOFException | SocketTimeoutException fin) {
            // cliente desconectado o inactivo
        } catch (Exception e) {
            Util.log("WARN", "conexión " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        } finally {
            b.conexiones.remove(s);
            if (s.token != null) b.sesiones.remove(s.token);
            s.cerrar();
        }
    }

    static Map<String, Object> error(String idSolicitud, String idMensajeCliente, String codigo, String mensaje) {
        return Json.obj("tipo", "ERROR", "idSolicitud", idSolicitud, "idMensajeCliente", idMensajeCliente,
                "codigo", codigo, "mensaje", mensaje, "timestamp", System.currentTimeMillis());
    }

    private void despachar(Sesion s, Map<String, Object> m) {
        String tipo = Json.str(m, "tipo");
        switch (tipo) {
            case "PING":
                s.enviar(Json.obj("tipo", "PONG", "timestamp", System.currentTimeMillis()));
                break;
            case "LOGIN":
            case "REGISTRO":
                autenticar(s, m, tipo);
                break;
            case "CONSULTA":
                String idm = Json.str(m, "idMensajeCliente");
                if (!s.autenticada() || !s.token.equals(Json.str(m, "token"))) {
                    s.enviar(error(null, idm, "NO_AUTENTICADO", "Inicie sesión primero"));
                    return;
                }
                String tc = Json.str(m, "tipoConsulta");
                if ("IMAGEN".equals(tc)) b.procesadores.submit(() -> consultaImagen(s, m));
                else if ("HISTORIAL".equals(tc)) b.procesadores.submit(() -> historial(s, m));
                else s.enviar(error(null, idm, "FORMATO", "tipoConsulta desconocido: " + tc));
                break;
            default:
                s.enviar(error(null, Json.str(m, "idMensajeCliente"), "FORMATO", "tipo de mensaje desconocido: " + tipo));
        }
    }

    private void autenticar(Sesion s, Map<String, Object> m, String tipo) {
        Map<String, Object> pet = Json.obj("tipoValidacion", tipo, "usuario", Json.str(m, "usuario"),
                "password", Json.str(m, "password"), "nombre", Json.str(m, "nombre"));
        Map<String, Object> r = b.rabbit.rpcSincrono(PuenteRabbit.EX_CUBO, "cola_validacion", pet, 10000);
        if (r == null) {
            s.enviar(Json.obj("tipo", "LOGIN_ERROR", "motivo", "BD2 (validación) no respondió"));
            return;
        }
        if (!Json.bool(r, "valido")) {
            s.enviar(Json.obj("tipo", "LOGIN_ERROR", "motivo", Json.str(r, "motivo")));
            return;
        }
        if (s.token != null) b.sesiones.remove(s.token);
        s.token = UUID.randomUUID().toString();
        s.idCliente = Json.num(r, "idCliente", 0);
        s.nombre = Json.str(r, "nombre");
        s.plataforma = Json.str(m, "plataforma");
        b.sesiones.put(s.token, s);
        s.enviar(Json.obj("tipo", "LOGIN_OK", "idCliente", s.idCliente, "token", s.token, "nombre", s.nombre));
        Util.log("INFO", tipo + " OK: " + s.describir());
    }

    private void consultaImagen(Sesion s, Map<String, Object> m) {
        String idm = Json.str(m, "idMensajeCliente");
        String modelo = Json.str(m, "modelo");
        if (!"A".equals(modelo) && !"B".equals(modelo)) {
            s.enviar(error(null, idm, "FORMATO", "Indique el modelo: A (malaria) o B (células sanguíneas)"));
            return;
        }
        String payload = Json.str(m, "payload");
        byte[] img;
        try {
            img = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            s.enviar(error(null, idm, "FORMATO", "payload no es Base64 válido"));
            return;
        }
        if (img.length == 0) {
            s.enviar(error(null, idm, "FORMATO", "imagen vacía"));
            return;
        }
        String idSolicitud = UUID.randomUUID().toString();
        String formato = Json.str(m, "formato").toLowerCase();
        // 1) validación de cliente / producto / cuota en BD2 (RPC por RabbitMQ)
        Map<String, Object> v = b.rabbit.rpcSincrono(PuenteRabbit.EX_CUBO, "cola_validacion", Json.obj(
                "tipoValidacion", "SOLICITUD", "idCliente", s.idCliente, "modelo", modelo, "formato", formato,
                "bytes", img.length, "idSolicitud", idSolicitud, "nodoOrigen", "BACKEND"), 10000);
        if (v == null) {
            Backend.errores.incrementAndGet();
            s.enviar(error(idSolicitud, idm, "INTERNO", "BD2 (validación) no respondió"));
            return;
        }
        if (!Json.bool(v, "valido")) {
            Backend.rechazadas.incrementAndGet();
            String codigo = Json.str(v, "codigo");
            s.enviar(error(idSolicitud, idm, codigo.isEmpty() ? "VALIDACION" : codigo, Json.str(v, "motivo")));
            return;
        }
        // 2) se registra antes de publicar para que una respuesta rápida siempre encuentre la sesión
        b.correlador.registrar(idSolicitud, s, idm, modelo);
        s.enviar(Json.obj("tipo", "ACK", "idMensajeCliente", idm, "idSolicitud", idSolicitud, "estado", "EN_COLA"));
        // 3) a la cola del modelo correspondiente
        b.rabbit.publicar(PuenteRabbit.EX_CUBO, "cola_modelo" + modelo, Json.obj(
                "idSolicitud", idSolicitud, "idCliente", s.idCliente, "plataforma", s.plataforma,
                "modeloDestino", modelo, "formato", formato, "imagen", payload, "sha256", Util.sha256(img),
                "bytes", img.length, "texto", Json.str(m, "texto"),
                "timestampCliente", Json.num(m, "timestamp", 0), "timestampBackend", System.currentTimeMillis()), true);
        Backend.enviadas.incrementAndGet();
    }

    @SuppressWarnings("unchecked")
    private void historial(Sesion s, Map<String, Object> m) {
        String idm = Json.str(m, "idMensajeCliente");
        long limite = Math.max(1, Math.min(200, Json.num(m, "limite", 20)));
        Map<String, Object> pet = Json.obj("idCliente", s.idCliente, "limite", limite);
        CompletableFuture<Map<String, Object>> fa = b.rabbit.rpc(PuenteRabbit.EX_HISTORIAL, "A", pet);
        CompletableFuture<Map<String, Object>> fb = b.rabbit.rpc(PuenteRabbit.EX_HISTORIAL, "B", pet);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CompletableFuture<Map<String, Object>> f : List.of(fa, fb)) {
            try {
                for (Object o : Json.lista(f.get(8, TimeUnit.SECONDS), "items")) items.add((Map<String, Object>) o);
            } catch (Exception e) {
                f.cancel(true); // ese nodo BD no respondió: se devuelve lo que haya
            }
        }
        items.sort((x, y) -> Json.str(y, "fecha").compareTo(Json.str(x, "fecha")));
        if (items.size() > limite) items = new ArrayList<>(items.subList(0, (int) limite));
        s.enviar(Json.obj("tipo", "HISTORIAL", "idMensajeCliente", idm, "items", items));
    }
}
