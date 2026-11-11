package cubo.web;

import cubo.comun.Args;
import cubo.comun.ConexionBackend;
import cubo.comun.Json;
import cubo.comun.Util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Servidor HTTP/1.1 propio sobre ServerSocket (sin frameworks ni WebSocket).
 *  - Sirve el frontend (web/index.html, app.js, estilos.css).
 *  - Hace de puente HTTP → TCP: cada sesión web tiene su propia conexión TCP
 *    con el backend (protocolo de tramas propio).
 *  - Los resultados asíncronos se entregan por long polling (GET /api/eventos).
 *
 *   java -cp "out:lib/*" cubo.web.ServidorHttp --puerto 8080 --backend-host IP --backend-puerto 5000 --www ../web
 */
public class ServidorHttp {
    static final class SesionWeb {
        final String id = UUID.randomUUID().toString();
        final ConexionBackend con;
        final LinkedBlockingQueue<Map<String, Object>> eventos = new LinkedBlockingQueue<>();
        volatile long ultimoUso = System.currentTimeMillis();

        SesionWeb(String host, int puerto) throws IOException {
            con = new ConexionBackend(host, puerto, "WEB", eventos::add);
        }
    }

    private final ConcurrentHashMap<String, SesionWeb> sesiones = new ConcurrentHashMap<>();
    private final Path raiz;
    private final String backendHost;
    private final int backendPuerto;

    ServidorHttp(Path raiz, String host, int puerto) {
        this.raiz = raiz.toAbsolutePath().normalize();
        this.backendHost = host;
        this.backendPuerto = puerto;
        Thread limpieza = new Thread(() -> { // cierra sesiones inactivas (10 min)
            while (true) {
                Util.dormir(30000);
                long ahora = System.currentTimeMillis();
                sesiones.values().removeIf(s -> {
                    boolean vieja = ahora - s.ultimoUso > 600_000 || !s.con.abierta();
                    if (vieja) s.con.close();
                    return vieja;
                });
            }
        }, "limpieza-sesiones");
        limpieza.setDaemon(true);
        limpieza.start();
    }

    void atender(Socket s) {
        try (s) {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            while (true) {
                PeticionHttp p;
                try {
                    p = PeticionHttp.leer(in);
                } catch (PeticionHttp.ErrorHttp e) {
                    RespuestaHttp.texto(e.codigo, e.getMessage()).escribir(out, false, false);
                    return;
                }
                if (p == null) return;
                RespuestaHttp r;
                try {
                    r = enrutar(p);
                } catch (Exception e) {
                    r = RespuestaHttp.json(500, Json.obj("tipo", "ERROR", "codigo", "INTERNO", "mensaje", String.valueOf(e.getMessage())));
                }
                boolean mantener = p.mantenerViva();
                r.escribir(out, mantener, "HEAD".equals(p.metodo));
                if (!mantener) return;
            }
        } catch (SocketTimeoutException ignorado) {
        } catch (IOException ignorado) {
        }
    }

    private SesionWeb sesion(PeticionHttp p) {
        String id = p.cookie("sesion");
        SesionWeb s = id == null ? null : sesiones.get(id);
        if (s != null) s.ultimoUso = System.currentTimeMillis();
        return s;
    }

    RespuestaHttp enrutar(PeticionHttp p) throws Exception {
        String ruta = p.ruta;
        if (ruta.startsWith("/api/")) {
            if (ruta.equals("/api/eventos") && p.metodo.equals("GET")) return eventos(p);
            if (!p.metodo.equals("POST")) return RespuestaHttp.json(405, Json.obj("tipo", "ERROR", "mensaje", "use POST"));
            Map<String, Object> cuerpo = p.cuerpo.length == 0 ? Json.obj() : Json.parseObjeto(p.cuerpoTexto());
            switch (ruta) {
                case "/api/login":
                case "/api/registro":
                    return autenticar(cuerpo, ruta.endsWith("registro"));
                case "/api/consulta": {
                    SesionWeb s = sesion(p);
                    if (s == null) return noAutenticado();
                    String idm = Json.str(cuerpo, "idMensajeCliente");
                    return RespuestaHttp.json(200, s.con.consultaImagenBase64(idm.isEmpty() ? null : idm,
                            Json.str(cuerpo, "modelo"), Json.str(cuerpo, "formato"), Json.str(cuerpo, "payload"),
                            Json.str(cuerpo, "texto")));
                }
                case "/api/historial": {
                    SesionWeb s = sesion(p);
                    if (s == null) return noAutenticado();
                    return RespuestaHttp.json(200, s.con.historial((int) Json.num(cuerpo, "limite", 20)));
                }
                case "/api/logout": {
                    SesionWeb s = sesion(p);
                    if (s != null) { sesiones.remove(s.id); s.con.close(); }
                    RespuestaHttp r = RespuestaHttp.json(200, Json.obj("tipo", "OK"));
                    r.cabeceras.put("Set-Cookie", "sesion=; Path=/; Max-Age=0");
                    return r;
                }
                default:
                    return RespuestaHttp.json(404, Json.obj("tipo", "ERROR", "mensaje", "ruta desconocida"));
            }
        }
        if (!p.metodo.equals("GET") && !p.metodo.equals("HEAD")) return RespuestaHttp.texto(405, "método no permitido");
        return estatico(ruta);
    }

    private RespuestaHttp noAutenticado() {
        return RespuestaHttp.json(401, Json.obj("tipo", "ERROR", "codigo", "NO_AUTENTICADO", "mensaje", "Inicie sesión"));
    }

    private RespuestaHttp autenticar(Map<String, Object> c, boolean registro) {
        SesionWeb s;
        try {
            s = new SesionWeb(backendHost, backendPuerto);
        } catch (IOException e) {
            return RespuestaHttp.json(200, Json.obj("tipo", "LOGIN_ERROR", "motivo", "Backend no disponible: " + e.getMessage()));
        }
        try {
            String u = Json.str(c, "usuario"), pw = Json.str(c, "password");
            Map<String, Object> r = registro ? s.con.registro(u, pw, Json.str(c, "nombre")) : s.con.login(u, pw);
            if (!"LOGIN_OK".equals(Json.str(r, "tipo"))) {
                s.con.close();
                return RespuestaHttp.json(200, r);
            }
            sesiones.put(s.id, s);
            r.remove("token"); // el token del backend no sale al navegador; el navegador usa la cookie
            RespuestaHttp resp = RespuestaHttp.json(200, r);
            resp.cabeceras.put("Set-Cookie", "sesion=" + s.id + "; Path=/; HttpOnly; SameSite=Strict");
            return resp;
        } catch (IOException e) {
            s.con.close();
            return RespuestaHttp.json(200, Json.obj("tipo", "LOGIN_ERROR", "motivo", e.getMessage()));
        }
    }

    /** Long polling: espera hasta 25 s el siguiente evento (RESULTADO/ERROR) de la sesión. */
    private RespuestaHttp eventos(PeticionHttp p) throws InterruptedException {
        SesionWeb s = sesion(p);
        if (s == null) return noAutenticado();
        List<Map<String, Object>> lista = new ArrayList<>();
        Map<String, Object> primero = s.eventos.poll(25, TimeUnit.SECONDS);
        if (primero != null) {
            lista.add(primero);
            s.eventos.drainTo(lista, 199);
        }
        s.ultimoUso = System.currentTimeMillis();
        return RespuestaHttp.json(200, Json.obj("eventos", lista));
    }

    private RespuestaHttp estatico(String ruta) throws IOException {
        if (ruta.equals("/")) ruta = "/index.html";
        Path f = raiz.resolve(ruta.substring(1)).normalize();
        if (!f.startsWith(raiz)) return RespuestaHttp.texto(403, "prohibido"); // evita path traversal (../)
        File archivo = f.toFile();
        if (!archivo.isFile()) return RespuestaHttp.texto(404, "no encontrado: " + ruta);
        RespuestaHttp r = new RespuestaHttp();
        r.cuerpo = Files.readAllBytes(f);
        String n = archivo.getName().toLowerCase();
        r.tipo = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".js") ? "application/javascript; charset=utf-8"
                : n.endsWith(".css") ? "text/css; charset=utf-8" : n.endsWith(".png") ? "image/png"
                : n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg" : n.endsWith(".svg") ? "image/svg+xml"
                : "application/octet-stream";
        return r;
    }

    public static void main(String[] argv) throws Exception {
        Args a = new Args(argv);
        int puerto = a.entero("puerto", "CUBO_WEB_PUERTO", 8080);
        int hilos = a.entero("hilos", 128);
        ServidorHttp srv = new ServidorHttp(Path.of(a.get("www", "../web")), a.get("backend-host", "CUBO_BACKEND_HOST", "localhost"),
                a.entero("backend-puerto", "CUBO_BACKEND_PUERTO", 5000));
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        try (ServerSocket ss = new ServerSocket(puerto, 200)) {
            Util.log("INFO", "Servidor HTTP propio en http://" + Util.ipLocal() + ":" + puerto + "/ → backend "
                    + srv.backendHost + ":" + srv.backendPuerto + " | raíz " + srv.raiz + " | " + hilos + " hilos");
            while (true) {
                Socket s = ss.accept();
                pool.submit(() -> srv.atender(s));
            }
        }
    }
}
