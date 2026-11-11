package cubo.desktop;

import cubo.comun.Args;
import cubo.comun.ConexionBackend;
import cubo.comun.Json;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Cliente de chat en terminal (versión base exigida por el enunciado).
 *
 *   java -cp "out:lib/*" cubo.desktop.ClienteTerminal --host IP_BACKEND --puerto 5000
 *   (opcional: --usuario ana --password clave123 --imagen ruta.png --modelo A  para un envío directo)
 */
public class ClienteTerminal {
    private static ConexionBackend con;

    static void alEvento(Map<String, Object> m) {
        String tipo = Json.str(m, "tipo");
        if ("RESULTADO".equals(tipo)) {
            System.out.println("\nBorgAICube: " + ClienteDesktop.textoResultado(m));
            @SuppressWarnings("unchecked")
            Map<String, Object> probs = (Map<String, Object>) m.get("probabilidades");
            if (probs != null) probs.forEach((k, v) -> System.out.printf("   %-22s %6.2f %%%n", k, ((Number) v).doubleValue() * 100));
        } else if ("ERROR".equals(tipo)) {
            System.out.println("\nBorgAICube: ⚠ " + Json.str(m, "codigo") + " — " + Json.str(m, "mensaje"));
        } else if ("DESCONECTADO".equals(tipo)) {
            System.out.println("\n*** Conexión con el backend perdida ***");
        }
        System.out.print("> ");
    }

    public static void main(String[] argv) throws Exception {
        Args a = new Args(argv);
        String host = a.get("host", "CUBO_BACKEND_HOST", "localhost");
        int puerto = a.entero("puerto", "CUBO_BACKEND_PUERTO", 5000);
        con = new ConexionBackend(host, puerto, "DESKTOP", ClienteTerminal::alEvento);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("=== Borg IA Cube — cliente de terminal (" + host + ":" + puerto + ") ===");

        if (a.tiene("usuario")) {
            Map<String, Object> r = con.login(a.get("usuario", ""), a.get("password", "clave123"));
            if ("LOGIN_OK".equals(Json.str(r, "tipo"))) {
                System.out.println("LOGIN_OK idCliente " + con.idCliente + " — Bienvenido, " + con.nombre);
            } else {
                System.out.println(Json.str(r, "tipo") + " " + Json.str(r, "motivo") + Json.str(r, "mensaje"));
                if (a.tiene("imagen")) System.exit(1); // modo directo: sin sesión no se envía nada
            }
            if (a.tiene("imagen")) { // modo directo: un envío y salir
                enviar(new File(a.get("imagen", "")), a.get("modelo", "A"), "");
                Thread.sleep(a.entero("esperar", 10) * 1000L);
                System.exit(0);
            }
        }
        while (con.token.isEmpty()) {
            System.out.print("¿[1] Iniciar sesión o [2] Registrarse? ");
            String op = in.readLine();
            if (op == null) return;
            System.out.print("Usuario: ");
            String u = in.readLine();
            System.out.print("Contraseña: ");
            String p = in.readLine();
            Map<String, Object> r = "2".equals(op.trim()) ? con.registro(u, p, u) : con.login(u, p);
            if ("LOGIN_OK".equals(Json.str(r, "tipo")))
                System.out.println("Usuario validado. idCliente " + con.idCliente + " — Bienvenido, " + con.nombre);
            else System.out.println("Error: " + Json.str(r, "motivo") + Json.str(r, "mensaje"));
        }
        String modelo = "A";
        System.out.println("Comandos: /malaria (modelo A) | /sangre (modelo B) | /imagen <ruta> [texto] | /historial |"
                + " /carga <n> <carpeta_muestras> [delay_max_ms] | /salir");
        while (true) {
            System.out.print("> ");
            String l = in.readLine();
            if (l == null || l.trim().equals("/salir")) break;
            l = l.trim();
            if (l.equals("/malaria")) { modelo = "A"; System.out.println("Modelo A — Malaria (Parasitada / No infectada)"); }
            else if (l.equals("/sangre")) { modelo = "B"; System.out.println("Modelo B — Células sanguíneas (8 clases)"); }
            else if (l.startsWith("/imagen ")) {
                String[] p = l.substring(8).trim().split(" ", 2);
                enviar(new File(p[0]), modelo, p.length > 1 ? p[1] : "");
            } else if (l.equals("/historial")) {
                Map<String, Object> h = con.historial(20);
                for (Object o : Json.lista(h, "items")) {
                    @SuppressWarnings("unchecked") Map<String, Object> it = (Map<String, Object>) o;
                    System.out.printf("  %s  modelo %s  %-9s %-22s %5.1f%%  %s%n", Json.str(it, "fecha"), Json.str(it, "modelo"),
                            Json.str(it, "estado"), Json.str(it, "etiqueta"), Json.dbl(it, "probabilidad", 0) * 100,
                            Json.str(it, "idSolicitud"));
                }
                if (Json.lista(h, "items").isEmpty()) System.out.println("  (sin consultas) " + Json.str(h, "mensaje"));
            } else if (l.startsWith("/carga")) {
                String[] p = l.split("\\s+");
                int n = p.length > 1 ? Integer.parseInt(p[1]) : 10;
                File dir = new File(p.length > 2 ? p[2] : "../datos/muestras");
                int delay = p.length > 3 ? Integer.parseInt(p[3]) : 200;
                cargaAleatoria(n, dir, modelo, delay);
            } else if (!l.isEmpty()) {
                System.out.println("BorgAICube: envíame una imagen con /imagen <ruta>. Modelo actual: " + modelo
                        + (modelo.equals("A") ? " (malaria)" : " (células sanguíneas)"));
            }
        }
        con.close();
        System.exit(0);
    }

    static void enviar(File f, String modelo, String texto) {
        try {
            byte[] datos = Files.readAllBytes(f.toPath());
            Map<String, Object> r = con.consultaImagen(UUID.randomUUID().toString(), modelo,
                    ConexionBackend.formatoDe(f.getName(), datos), datos, texto);
            if ("ACK".equals(Json.str(r, "tipo"))) System.out.println("  (en cola: " + Json.str(r, "idSolicitud") + ")");
            else System.out.println("  ⚠ " + Json.str(r, "codigo") + " — " + Json.str(r, "mensaje"));
        } catch (Exception e) {
            System.out.println("  no se pudo enviar: " + e.getMessage());
        }
    }

    /** Ingresa N registros aleatorios desde el cliente (imágenes de muestra al azar, delay aleatorio). */
    static void cargaAleatoria(int n, File dir, String modelo, int delayMax) throws Exception {
        File[] todas = dir.listFiles((d, nombre) -> nombre.startsWith(modelo + "_"));
        if (todas == null || todas.length == 0) { System.out.println("  no hay imágenes " + modelo + "_*.png en " + dir); return; }
        List<byte[]> imgs = new ArrayList<>();
        for (File f : todas) imgs.add(Files.readAllBytes(f.toPath()));
        Random r = new Random();
        int ok = 0;
        for (int i = 0; i < n; i++) {
            Thread.sleep(r.nextInt(delayMax + 1));
            byte[] d = imgs.get(r.nextInt(imgs.size()));
            if ("ACK".equals(Json.str(con.consultaImagen(null, modelo, "png", d, "carga " + i), "tipo"))) ok++;
        }
        System.out.println("  " + ok + "/" + n + " consultas aceptadas; los resultados llegan de forma asíncrona.");
    }
}
