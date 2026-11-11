package cubo.web;

import cubo.comun.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Respuesta HTTP/1.1 escrita a mano en el socket. */
public class RespuestaHttp {
    static final Map<Integer, String> TEXTOS = Map.of(200, "OK", 400, "Bad Request", 401, "Unauthorized",
            403, "Forbidden", 404, "Not Found", 405, "Method Not Allowed", 411, "Length Required",
            413, "Payload Too Large", 431, "Request Header Fields Too Large", 500, "Internal Server Error");

    int codigo = 200;
    String tipo = "text/plain; charset=utf-8";
    byte[] cuerpo = new byte[0];
    final Map<String, String> cabeceras = new LinkedHashMap<>();

    static RespuestaHttp json(int codigo, Map<String, Object> m) {
        RespuestaHttp r = new RespuestaHttp();
        r.codigo = codigo;
        r.tipo = "application/json; charset=utf-8";
        r.cuerpo = Json.stringify(m).getBytes(StandardCharsets.UTF_8);
        r.cabeceras.put("Cache-Control", "no-store");
        return r;
    }

    static RespuestaHttp texto(int codigo, String t) {
        RespuestaHttp r = new RespuestaHttp();
        r.codigo = codigo;
        r.cuerpo = t.getBytes(StandardCharsets.UTF_8);
        return r;
    }

    void escribir(OutputStream out, boolean mantener, boolean soloCabeceras) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("HTTP/1.1 ").append(codigo).append(' ').append(TEXTOS.getOrDefault(codigo, "OK")).append("\r\n");
        b.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n");
        b.append("Server: CuboIA-HTTP-propio\r\n");
        b.append("Content-Type: ").append(tipo).append("\r\n");
        b.append("Content-Length: ").append(cuerpo.length).append("\r\n");
        b.append("Connection: ").append(mantener ? "keep-alive" : "close").append("\r\n");
        for (Map.Entry<String, String> e : cabeceras.entrySet()) b.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        b.append("\r\n");
        out.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!soloCabeceras) out.write(cuerpo);
        out.flush();
    }
}
