package cubo.comun;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Pruebas del código común (sin dependencias):
 *  1. Json: ida y vuelta, escapes y unicode.
 *  2. Trama: mensajes partidos en varios read() y varios mensajes en un solo read().
 *  3. 50 hilos escribiendo en el mismo flujo con el lock → ninguna trama se intercala.
 *
 *   java -cp out cubo.comun.PruebasComun
 */
public class PruebasComun {
    static int fallos = 0;

    static void verificar(boolean c, String nombre) {
        System.out.println((c ? "[OK ]  " : "[FALLA] ") + nombre);
        if (!c) fallos++;
    }

    /** Flujo que entrega como máximo 'trozo' bytes por read(), como un socket lento. */
    static class Goteo extends InputStream {
        final byte[] d;
        final int trozo;
        int i;

        Goteo(byte[] d, int trozo) { this.d = d; this.trozo = trozo; }

        @Override public int read() { return i < d.length ? d[i++] & 0xff : -1; }

        @Override public int read(byte[] b, int off, int len) {
            if (i >= d.length) return -1;
            int n = Math.min(Math.min(len, trozo), d.length - i);
            System.arraycopy(d, i, b, off, n);
            i += n;
            return n;
        }
    }

    public static void main(String[] args) throws Exception {
        // 1. JSON
        String texto = "{\"a\":1,\"b\":-2.5e3,\"c\":\"línea\\n\\\"comillas\\\" \\u00f1 \\\\ /\",\"d\":[true,false,null,{}],\"e\":{\"x\":[]}}";
        Map<String, Object> m = Json.parseObjeto(texto);
        verificar(Json.num(m, "a", 0) == 1, "Json: entero");
        verificar(Json.dbl(m, "b", 0) == -2500.0, "Json: decimal con exponente");
        verificar("línea\n\"comillas\" ñ \\ /".equals(m.get("c")), "Json: escapes y unicode");
        Map<String, Object> m2 = Json.parseObjeto(Json.stringify(m));
        verificar(m.equals(m2), "Json: ida y vuelta conserva el contenido");
        boolean lanzo = false;
        try { Json.parse("{\"a\":}"); } catch (IllegalArgumentException e) { lanzo = true; }
        verificar(lanzo, "Json: rechaza JSON inválido");

        // 2. Trama partida y tramas juntas
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (int k = 0; k < 3; k++) Trama.escribir(buf, Json.stringify(Json.obj("n", k, "relleno", "x".repeat(5000))));
        DataInputStream in = new DataInputStream(new Goteo(buf.toByteArray(), 7));
        boolean ok = true;
        for (int k = 0; k < 3; k++) ok &= Json.num(Json.parseObjeto(Trama.leer(in)), "n", -1) == k;
        verificar(ok, "Trama: mensajes partidos en lecturas de 7 bytes");
        in = new DataInputStream(new ByteArrayInputStream(buf.toByteArray()));
        ok = true;
        for (int k = 0; k < 3; k++) ok &= Json.num(Json.parseObjeto(Trama.leer(in)), "n", -1) == k;
        verificar(ok, "Trama: varios mensajes en una sola lectura");
        byte[] grande = {0x7f, 0, 0, 0};
        lanzo = false;
        try { Trama.leer(new DataInputStream(new ByteArrayInputStream(grande))); } catch (Trama.TramaGrandeException e) { lanzo = true; }
        verificar(lanzo, "Trama: rechaza longitudes mayores a 8 MiB");

        // 3. 50 hilos escribiendo con lock sobre el mismo flujo
        ByteArrayOutputStream comun = new ByteArrayOutputStream();
        OutputStream lento = new OutputStream() { // escribe byte a byte para favorecer el intercalado
            @Override public void write(int b) { comun.write(b); }
            @Override public void write(byte[] b, int off, int len) { for (int k = 0; k < len; k++) { comun.write(b[off + k]); if (k % 50 == 0) Thread.yield(); } }
        };
        Object lock = new Object();
        CountDownLatch inicio = new CountDownLatch(1);
        List<Thread> hilos = new ArrayList<>();
        for (int h = 0; h < 50; h++) {
            final int id = h;
            Thread t = new Thread(() -> {
                try {
                    inicio.await();
                    for (int k = 0; k < 20; k++) {
                        String j = Json.stringify(Json.obj("hilo", id, "k", k, "datos", "z".repeat(300)));
                        synchronized (lock) { Trama.escribir(lento, j); }
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            hilos.add(t);
            t.start();
        }
        inicio.countDown();
        for (Thread t : hilos) t.join();
        in = new DataInputStream(new ByteArrayInputStream(comun.toByteArray()));
        int leidas = 0;
        ok = true;
        try {
            for (int k = 0; k < 1000; k++) { Json.parseObjeto(Trama.leer(in)); leidas++; }
        } catch (Exception e) { ok = false; }
        verificar(ok && leidas == 1000, "Concurrencia: 50 hilos × 20 tramas sin intercalado (" + leidas + " leídas)");

        System.out.println(fallos == 0 ? "TODAS LAS PRUEBAS PASARON" : fallos + " PRUEBAS FALLARON");
        System.exit(fallos == 0 ? 0 : 1);
    }
}
