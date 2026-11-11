package cubo.comun;

import java.util.HashMap;
import java.util.Map;

/** Argumentos "--clave valor" con respaldo en variables de entorno (sin archivos de configuración). */
public final class Args {
    private final Map<String, String> m = new HashMap<>();

    public Args(String[] a) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].startsWith("--")) {
                String k = a[i].substring(2);
                if (i + 1 < a.length && !a[i + 1].startsWith("--")) m.put(k, a[++i]);
                else m.put(k, "true");
            }
        }
    }

    public String get(String clave, String entorno, String def) {
        if (m.containsKey(clave)) return m.get(clave);
        if (entorno != null) {
            String v = System.getenv(entorno);
            if (v != null && !v.isEmpty()) return v;
        }
        return def;
    }

    public String get(String clave, String def) { return get(clave, null, def); }

    public int entero(String clave, String entorno, int def) {
        try { return Integer.parseInt(get(clave, entorno, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    public int entero(String clave, int def) { return entero(clave, null, def); }

    public boolean tiene(String clave) { return m.containsKey(clave); }

    public static String rabbitPorDefecto() {
        String v = System.getenv("CUBO_RABBIT_URL");
        return v != null && !v.isEmpty() ? v : "amqp://cubo:cubo123@localhost:5672/cubo";
    }
}
