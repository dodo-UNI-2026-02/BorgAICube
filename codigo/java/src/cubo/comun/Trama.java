package cubo.comun;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Protocolo de encuadre sobre TCP: [4 bytes longitud N big-endian][N bytes JSON UTF-8].
 * readFully garantiza que una trama partida en varios segmentos TCP se reconstruya
 * y que varias tramas que llegan juntas se separen correctamente.
 */
public final class Trama {
    public static final int MAX = 8 * 1024 * 1024; // 8 MiB

    public static class TramaGrandeException extends IOException {
        public TramaGrandeException(int n) { super("trama de " + n + " bytes excede el máximo de " + MAX); }
    }

    private Trama() {}

    /** Lee una trama completa; lanza EOFException si el otro extremo cerró. */
    public static String leer(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX) throw new TramaGrandeException(n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Escribe una trama en una sola llamada. El llamador debe sincronizar el flujo. */
    public static void escribir(OutputStream out, String json) throws IOException {
        byte[] cuerpo = json.getBytes(StandardCharsets.UTF_8);
        if (cuerpo.length > MAX) throw new TramaGrandeException(cuerpo.length);
        ByteBuffer bb = ByteBuffer.allocate(4 + cuerpo.length);
        bb.putInt(cuerpo.length).put(cuerpo);
        out.write(bb.array());
        out.flush();
    }
}
