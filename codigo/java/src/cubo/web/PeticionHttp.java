package cubo.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Petición HTTP/1.1 parseada a mano desde el socket (sin frameworks ni com.sun.net.httpserver). */
public class PeticionHttp {
    public static final int MAX_CUERPO = 12 * 1024 * 1024;

    public static class ErrorHttp extends IOException {
        final int codigo;
        ErrorHttp(int codigo, String m) { super(m); this.codigo = codigo; }
    }

    String metodo, ruta, version;
    final Map<String, String> query = new HashMap<>();
    final Map<String, String> cabeceras = new HashMap<>();
    byte[] cuerpo = new byte[0];

    /** Lee una línea terminada en CRLF (o LF). Devuelve null si el cliente cerró. */
    private static String linea(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new ErrorHttp(431, "cabecera demasiado larga");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    public static PeticionHttp leer(InputStream in) throws IOException {
        String primera = linea(in);
        while (primera != null && primera.isEmpty()) primera = linea(in); // CRLF sobrantes entre peticiones
        if (primera == null) return null;
        String[] p = primera.split(" ");
        if (p.length != 3) throw new ErrorHttp(400, "línea de petición inválida");
        PeticionHttp r = new PeticionHttp();
        r.metodo = p[0].toUpperCase(Locale.ROOT);
        r.version = p[2];
        String objetivo = p[1];
        int q = objetivo.indexOf('?');
        r.ruta = URLDecoder.decode(q < 0 ? objetivo : objetivo.substring(0, q), StandardCharsets.UTF_8);
        if (q >= 0) {
            for (String par : objetivo.substring(q + 1).split("&")) {
                int e = par.indexOf('=');
                if (e > 0) r.query.put(URLDecoder.decode(par.substring(0, e), StandardCharsets.UTF_8),
                        URLDecoder.decode(par.substring(e + 1), StandardCharsets.UTF_8));
            }
        }
        String l;
        while ((l = linea(in)) != null && !l.isEmpty()) {
            int d = l.indexOf(':');
            if (d > 0) r.cabeceras.put(l.substring(0, d).trim().toLowerCase(Locale.ROOT), l.substring(d + 1).trim());
        }
        String cl = r.cabeceras.get("content-length");
        if (cl != null) {
            int n;
            try { n = Integer.parseInt(cl); } catch (NumberFormatException e) { throw new ErrorHttp(400, "Content-Length inválido"); }
            if (n > MAX_CUERPO) throw new ErrorHttp(413, "cuerpo demasiado grande");
            r.cuerpo = in.readNBytes(n);
            if (r.cuerpo.length != n) throw new ErrorHttp(400, "cuerpo incompleto");
        } else if ("chunked".equalsIgnoreCase(r.cabeceras.get("transfer-encoding"))) {
            throw new ErrorHttp(411, "se requiere Content-Length");
        }
        return r;
    }

    public String cabecera(String k) { return cabeceras.getOrDefault(k.toLowerCase(Locale.ROOT), ""); }

    public String cookie(String nombre) {
        for (String c : cabecera("cookie").split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(nombre)) return kv[1];
        }
        return null;
    }

    public String cuerpoTexto() { return new String(cuerpo, StandardCharsets.UTF_8); }

    public boolean mantenerViva() {
        String c = cabecera("connection").toLowerCase(Locale.ROOT);
        return "HTTP/1.1".equals(version) ? !c.contains("close") : c.contains("keep-alive");
    }
}
