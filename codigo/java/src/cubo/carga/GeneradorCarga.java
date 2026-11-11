package cubo.carga;

import cubo.comun.Args;
import cubo.comun.ConexionBackend;
import cubo.comun.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Prueba de saturación: N transacciones con delay aleatorio entre envíos, desde
 * C sesiones simultáneas, por el cliente DESKTOP (TCP propio) o WEB (HTTP hacia
 * el servidor web propio, que a su vez usa TCP hacia el backend).
 * Registra por transacción t_envio, t_ack, t_resultado, latencia y estado, y
 * agrega un resumen (latencia media/p50/p95/p99, throughput, tasa de error) a
 * resultados/resumen_carga.csv.
 *
 *   java -cp "out:lib/*" cubo.carga.GeneradorCarga --plataforma DESKTOP --host IP --n 1000 --config paralelo --nodos-ia 1
 *   java -cp "out:lib/*" cubo.carga.GeneradorCarga --plataforma WEB --host IP_WEB --puerto 8080 --n 1000
 *   java -cp "out:lib/*" cubo.carga.GeneradorCarga --tipo REGISTRO --n 1000      (1000 clientes nuevos aleatorios)
 */
public class GeneradorCarga {

    public static class Config {
        public String plataforma = "DESKTOP", host = "localhost", modelo = "MIX", tipo = "CONSULTA",
                muestras = "../datos/muestras", password = "clave123", salida = null, config = "sin-etiqueta";
        public int puerto = 5000, n = 1000, delayMin = 0, delayMax = 200, concurrencia = 4, usuarios = 50,
                nodosIA = 1, esperaFinalS = 90;
    }

    static final class Registro {
        String idm, idSolicitud = "", plataforma, modelo;
        long tEnvio, tAck, tResultado;
        volatile String estado = "PENDIENTE", codigo = "";
    }

    /** Canal hacia el sistema: TCP directo (DESKTOP) o HTTP al servidor web (WEB). */
    interface Canal {
        Map<String, Object> login(String u, String p) throws IOException;
        Map<String, Object> registro(String u, String p) throws IOException;
        Map<String, Object> consulta(String idm, String modelo, String formato, String b64, String texto) throws IOException;
        void close();
    }

    static final class CanalTcp implements Canal {
        final ConexionBackend c;

        CanalTcp(Config cfg, Consumer<Map<String, Object>> ev) throws IOException {
            c = new ConexionBackend(cfg.host, cfg.puerto, cfg.plataforma.equals("DESKTOP") ? "DESKTOP" : "CARGA", ev);
        }

        public Map<String, Object> login(String u, String p) throws IOException { return c.login(u, p); }
        public Map<String, Object> registro(String u, String p) throws IOException { return c.registro(u, p, u); }
        public Map<String, Object> consulta(String idm, String m, String f, String b64, String t) throws IOException {
            return c.consultaImagenBase64(idm, m, f, b64, t);
        }
        public void close() { c.close(); }
    }

    /** Cliente HTTP mínimo (java.net.HttpURLConnection del SDK) para el servidor web propio. */
    public static final class CanalWeb implements Canal {
        final String base;
        volatile String cookie;
        volatile boolean activo = true;
        final Consumer<Map<String, Object>> ev;

        public CanalWeb(String host, int puerto, Consumer<Map<String, Object>> ev) {
            base = "http://" + host + ":" + puerto;
            this.ev = ev;
        }

        public Map<String, Object> pedir(String metodo, String ruta, Map<String, Object> cuerpo, int timeoutMs) throws IOException {
            HttpURLConnection h = (HttpURLConnection) java.net.URI.create(base + ruta).toURL().openConnection();
            h.setRequestMethod(metodo);
            h.setConnectTimeout(10000);
            h.setReadTimeout(timeoutMs);
            if (cookie != null) h.setRequestProperty("Cookie", cookie);
            if (cuerpo != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] b = Json.stringify(cuerpo).getBytes(StandardCharsets.UTF_8);
                try (OutputStream o = h.getOutputStream()) { o.write(b); }
            }
            int code = h.getResponseCode();
            String sc = h.getHeaderField("Set-Cookie");
            if (sc != null) cookie = sc.split(";", 2)[0];
            InputStream is = code < 400 ? h.getInputStream() : h.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) try (InputStream x = is) { x.transferTo(bo); }
            String txt = bo.toString(StandardCharsets.UTF_8);
            return txt.isEmpty() ? Json.obj("tipo", "ERROR", "codigo", "HTTP_" + code) : Json.parseObjeto(txt);
        }

        void iniciarSondeo() {
            Thread t = new Thread(() -> {
                while (activo) {
                    try {
                        Map<String, Object> r = pedir("GET", "/api/eventos", null, 35000);
                        for (Object o : Json.lista(r, "eventos")) {
                            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                            ev.accept(m);
                        }
                    } catch (Exception e) {
                        if (activo) cubo.comun.Util.dormir(500);
                    }
                }
            }, "sondeo-web");
            t.setDaemon(true);
            t.start();
        }

        public Map<String, Object> login(String u, String p) throws IOException {
            Map<String, Object> r = pedir("POST", "/api/login", Json.obj("usuario", u, "password", p), 20000);
            if ("LOGIN_OK".equals(Json.str(r, "tipo"))) iniciarSondeo();
            return r;
        }

        public Map<String, Object> registro(String u, String p) throws IOException {
            cookie = null;
            return pedir("POST", "/api/registro", Json.obj("usuario", u, "password", p, "nombre", u), 20000);
        }

        public Map<String, Object> consulta(String idm, String m, String f, String b64, String t) throws IOException {
            return pedir("POST", "/api/consulta", Json.obj("idMensajeCliente", idm, "modelo", m, "formato", f,
                    "payload", b64, "texto", t), 30000);
        }

        public void close() {
            activo = false;
            try { pedir("POST", "/api/logout", Json.obj(), 5000); } catch (Exception ignorado) { }
        }
    }

    /** Ejecuta la prueba y devuelve un resumen legible. */
    public static String ejecutar(Config cfg, Consumer<String> progreso) throws Exception {
        List<String[]> imgs = new ArrayList<>(); // {modelo, formato, base64}
        if (cfg.tipo.equals("CONSULTA")) {
            File[] fs = new File(cfg.muestras).listFiles((d, nm) -> nm.matches("[AB]_.*\\.(png|jpg|jpeg)"));
            if (fs == null || fs.length == 0) throw new IOException("no hay imágenes A_*.png / B_*.png en " + cfg.muestras
                    + " (se generan con: preprocesar)");
            for (File f : fs) {
                String m = f.getName().substring(0, 1);
                if (cfg.modelo.equals("MIX") || cfg.modelo.equals(m)) {
                    byte[] d = Files.readAllBytes(f.toPath());
                    imgs.add(new String[]{m, ConexionBackend.formatoDe(f.getName(), d), Base64.getEncoder().encodeToString(d)});
                }
            }
            if (imgs.isEmpty()) throw new IOException("no hay imágenes del modelo " + cfg.modelo);
        }
        ConcurrentHashMap<String, Registro> regs = new ConcurrentHashMap<>();
        Consumer<Map<String, Object>> alEvento = m -> {
            Registro r = regs.get(Json.str(m, "idMensajeCliente"));
            if (r == null) return;
            String tipo = Json.str(m, "tipo");
            if ("RESULTADO".equals(tipo)) { r.tResultado = System.currentTimeMillis(); r.estado = "OK"; }
            else if ("ERROR".equals(tipo)) { r.tResultado = System.currentTimeMillis(); r.codigo = Json.str(m, "codigo"); r.estado = "ERROR"; }
        };
        AtomicInteger enviados = new AtomicInteger();
        List<Thread> hilos = new ArrayList<>();
        List<Canal> canales = new ArrayList<>();
        long inicio = System.currentTimeMillis();
        for (int s = 0; s < cfg.concurrencia; s++) {
            final int sesion = s;
            final int cuota = cfg.n / cfg.concurrencia + (s < cfg.n % cfg.concurrencia ? 1 : 0);
            Thread t = new Thread(() -> {
                Random rnd = new Random(1000L + sesion);
                Canal c = null;
                try {
                    c = cfg.plataforma.equals("WEB") ? new CanalWeb(cfg.host, cfg.puerto, alEvento) : new CanalTcp(cfg, alEvento);
                    synchronized (canales) { canales.add(c); }
                    if (cfg.tipo.equals("CONSULTA")) {
                        String usuario = "usuario" + (sesion % cfg.usuarios + 1);
                        Map<String, Object> l = c.login(usuario, cfg.password);
                        if (!"LOGIN_OK".equals(Json.str(l, "tipo"))) throw new IOException("login de " + usuario + ": " + Json.str(l, "motivo"));
                    }
                    for (int i = 0; i < cuota; i++) {
                        Thread.sleep(cfg.delayMin + rnd.nextInt(Math.max(1, cfg.delayMax - cfg.delayMin + 1)));
                        Registro r = new Registro();
                        r.idm = UUID.randomUUID().toString();
                        r.plataforma = cfg.plataforma;
                        if (cfg.tipo.equals("REGISTRO")) {
                            r.modelo = "-";
                            regs.put(r.idm, r);
                            r.tEnvio = System.currentTimeMillis();
                            Map<String, Object> x = c.registro("carga_" + UUID.randomUUID().toString().substring(0, 12), "clave123");
                            r.tAck = r.tResultado = System.currentTimeMillis();
                            r.estado = "LOGIN_OK".equals(Json.str(x, "tipo")) ? "OK" : "ERROR";
                            r.codigo = Json.str(x, "motivo");
                        } else {
                            String[] img = imgs.get(rnd.nextInt(imgs.size()));
                            r.modelo = img[0];
                            regs.put(r.idm, r);
                            r.tEnvio = System.currentTimeMillis();
                            Map<String, Object> a = c.consulta(r.idm, img[0], img[1], img[2], "carga");
                            r.tAck = System.currentTimeMillis();
                            if ("ACK".equals(Json.str(a, "tipo"))) r.idSolicitud = Json.str(a, "idSolicitud");
                            else if (r.estado.equals("PENDIENTE")) {
                                r.tResultado = r.tAck;
                                r.codigo = Json.str(a, "codigo");
                                r.estado = "ERROR";
                            }
                        }
                        int e = enviados.incrementAndGet();
                        if (progreso != null && e % 50 == 0) progreso.accept("enviadas " + e + "/" + cfg.n);
                    }
                } catch (Exception e) {
                    System.err.println("sesión " + sesion + ": " + e.getMessage());
                }
            }, "carga-" + s);
            hilos.add(t);
            t.start();
        }
        for (Thread t : hilos) t.join();
        long limite = System.currentTimeMillis() + cfg.esperaFinalS * 1000L;
        while (System.currentTimeMillis() < limite && regs.values().stream().anyMatch(r -> r.estado.equals("PENDIENTE"))) {
            Thread.sleep(200);
        }
        for (Canal c : canales) c.close();
        for (Registro r : regs.values()) if (r.estado.equals("PENDIENTE")) { r.estado = "ERROR"; r.codigo = "SIN_RESPUESTA"; }
        return resumir(cfg, new ArrayList<>(regs.values()), inicio);
    }

    static String resumir(Config cfg, List<Registro> regs, long inicio) throws IOException {
        regs.sort((a, b) -> Long.compare(a.tEnvio, b.tEnvio));
        String salida = cfg.salida != null ? cfg.salida : "../resultados/carga_" + cfg.plataforma + "_" + cfg.config + ".csv";
        File fs = new File(salida);
        if (fs.getParentFile() != null) fs.getParentFile().mkdirs();
        List<Long> lat = new ArrayList<>();
        long fin = inicio;
        int ok = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(fs, StandardCharsets.UTF_8))) {
            w.println("idMensajeCliente,idSolicitud,plataforma,modelo,t_envio,t_ack,t_resultado,latencia_ms,estado,codigo_error");
            for (Registro r : regs) {
                long l = r.tResultado > 0 ? r.tResultado - r.tEnvio : -1;
                w.printf("%s,%s,%s,%s,%d,%d,%d,%d,%s,%s%n", r.idm, r.idSolicitud, r.plataforma, r.modelo, r.tEnvio, r.tAck,
                        r.tResultado, l, r.estado, r.codigo);
                if (r.estado.equals("OK")) { ok++; lat.add(l); }
                fin = Math.max(fin, r.tResultado);
            }
        }
        lat.sort(null);
        int total = regs.size(), err = total - ok;
        double media = lat.stream().mapToLong(Long::longValue).average().orElse(0);
        double dur = Math.max(0.001, (fin - inicio) / 1000.0);
        double tps = ok / dur;
        long p50 = pct(lat, 50), p95 = pct(lat, 95), p99 = pct(lat, 99), max = lat.isEmpty() ? 0 : lat.get(lat.size() - 1);
        File res = new File(fs.getParentFile(), "resumen_carga.csv");
        boolean nuevo = !res.exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(res, StandardCharsets.UTF_8, true))) {
            if (nuevo) w.println("fecha,config,plataforma,tipo,nodos_ia,n,ok,errores,tasa_error,lat_media_ms,p50_ms,p95_ms,p99_ms,max_ms,throughput_tps,duracion_s");
            w.println(String.format(Locale.US, "%s,%s,%s,%s,%d,%d,%d,%d,%.4f,%.1f,%d,%d,%d,%d,%.2f,%.1f",
                    LocalDateTime.now().withNano(0), cfg.config, cfg.plataforma, cfg.tipo, cfg.nodosIA, total, ok, err,
                    total == 0 ? 0 : err / (double) total, media, p50, p95, p99, max, tps, dur));
        }
        return String.format(Locale.US, "Prueba %s (%s, %s): %d transacciones | OK %d | errores %d (%.2f %%) | "
                        + "latencia media %.1f ms, p50 %d, p95 %d, p99 %d, máx %d ms | throughput %.2f tx/s | %.1f s. CSV: %s",
                cfg.config, cfg.plataforma, cfg.tipo, total, ok, err, total == 0 ? 0 : 100.0 * err / total, media, p50, p95, p99,
                max, tps, dur, salida);
    }

    static long pct(List<Long> v, int p) {
        if (v.isEmpty()) return 0;
        return v.get(Math.min(v.size() - 1, (int) Math.ceil(p / 100.0 * v.size()) - 1));
    }

    public static void main(String[] argv) throws Exception {
        Args a = new Args(argv);
        Config c = new Config();
        c.plataforma = a.get("plataforma", "DESKTOP").toUpperCase();
        c.host = a.get("host", "CUBO_BACKEND_HOST", "localhost");
        c.puerto = a.entero("puerto", c.plataforma.equals("WEB") ? 8080 : 5000);
        c.n = a.entero("n", 1000);
        c.delayMin = a.entero("delay-min", 0);
        c.delayMax = a.entero("delay-max", 200);
        c.concurrencia = a.entero("concurrencia", 4);
        c.usuarios = a.entero("usuarios", 50);
        c.password = a.get("password", "clave123");
        c.muestras = a.get("muestras", "../datos/muestras");
        c.modelo = a.get("modelo", "MIX").toUpperCase();
        c.tipo = a.get("tipo", "CONSULTA").toUpperCase();
        c.config = a.get("config", "sin-etiqueta");
        c.nodosIA = a.entero("nodos-ia", 1);
        c.esperaFinalS = a.entero("espera-final", 90);
        c.salida = a.get("salida", null);
        System.out.println("Generando carga: " + c.n + " " + c.tipo + " por " + c.plataforma + " → " + c.host + ":" + c.puerto
                + " | " + c.concurrencia + " sesiones | delay " + c.delayMin + "-" + c.delayMax + " ms");
        System.out.println(ejecutar(c, System.out::println));
        System.exit(0);
    }
}
