package cubo.comun;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializador y parser JSON escrito a mano (el SDK de Java no trae JSON y no
 * se permiten librerías de terceros). Representación:
 * objeto → LinkedHashMap, arreglo → ArrayList, número → Long o Double,
 * cadena → String, true/false → Boolean, null → null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    // ------------------------------------------------------------ parseo
    public static Object parse(String texto) {
        Json p = new Json(texto);
        p.espacios();
        Object v = p.valor();
        p.espacios();
        if (p.i != p.s.length()) throw p.error("caracteres sobrantes");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObjeto(String texto) {
        Object o = parse(texto);
        if (!(o instanceof Map)) throw new IllegalArgumentException("se esperaba un objeto JSON");
        return (Map<String, Object>) o;
    }

    private IllegalArgumentException error(String m) {
        return new IllegalArgumentException("JSON inválido en posición " + i + ": " + m);
    }

    private void espacios() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++; else break;
        }
    }

    private Object valor() {
        if (i >= s.length()) throw error("fin inesperado");
        char c = s.charAt(i);
        switch (c) {
            case '{': return objeto();
            case '[': return arreglo();
            case '"': return cadena();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return numero();
                throw error("carácter inesperado '" + c + "'");
        }
    }

    private void literal(String l) {
        if (!s.startsWith(l, i)) throw error("se esperaba " + l);
        i += l.length();
    }

    private Map<String, Object> objeto() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        espacios();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            espacios();
            if (i >= s.length() || s.charAt(i) != '"') throw error("se esperaba una clave");
            String k = cadena();
            espacios();
            if (i >= s.length() || s.charAt(i) != ':') throw error("se esperaba ':'");
            i++;
            espacios();
            m.put(k, valor());
            espacios();
            if (i >= s.length()) throw error("objeto sin cerrar");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw error("se esperaba ',' o '}'");
        }
    }

    private List<Object> arreglo() {
        List<Object> l = new ArrayList<>();
        i++;
        espacios();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            espacios();
            l.add(valor());
            espacios();
            if (i >= s.length()) throw error("arreglo sin cerrar");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw error("se esperaba ',' o ']'");
        }
    }

    private String cadena() {
        i++; // comilla inicial
        StringBuilder b = null;
        int inicio = i;
        while (true) {
            if (i >= s.length()) throw error("cadena sin cerrar");
            char c = s.charAt(i);
            if (c == '"') {
                String r = b == null ? s.substring(inicio, i) : b.append(s, inicio, i).toString();
                i++;
                return r;
            }
            if (c == '\\') {
                if (b == null) b = new StringBuilder();
                b.append(s, inicio, i);
                i++;
                if (i >= s.length()) throw error("escape incompleto");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw error("escape \\u incompleto");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw error("escape inválido \\" + e);
                }
                inicio = i;
            } else {
                i++;
            }
        }
    }

    private Number numero() {
        int ini = i;
        boolean decimal = false;
        if (s.charAt(i) == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') i++;
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { decimal = true; i++; }
            else break;
        }
        String t = s.substring(ini, i);
        try {
            if (!decimal) return Long.parseLong(t);
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            try { return Double.parseDouble(t); } catch (NumberFormatException e2) { throw error("número inválido " + t); }
        }
    }

    // ------------------------------------------------------------ serialización
    public static String stringify(Object o) {
        StringBuilder b = new StringBuilder();
        escribir(b, o);
        return b.toString();
    }

    private static void escribir(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof String) escapar(b, (String) o);
        else if (o instanceof Boolean) b.append(o);
        else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d).append(".0");
            else b.append(d);
        } else if (o instanceof Number) b.append(o);
        else if (o instanceof Map) {
            b.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!primero) b.append(',');
                primero = false;
                escapar(b, String.valueOf(e.getKey()));
                b.append(':');
                escribir(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Collection) {
            b.append('[');
            boolean primero = true;
            for (Object x : (Collection<?>) o) {
                if (!primero) b.append(',');
                primero = false;
                escribir(b, x);
            }
            b.append(']');
        } else if (o instanceof float[]) {
            b.append('[');
            float[] a = (float[]) o;
            for (int k = 0; k < a.length; k++) { if (k > 0) b.append(','); b.append(a[k]); }
            b.append(']');
        } else escapar(b, o.toString());
    }

    private static void escapar(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------ utilidades
    /** Crea un objeto a partir de pares clave, valor. */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    public static long num(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return def; }
        return def;
    }

    public static double dbl(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    public static boolean bool(Map<String, Object> m, String k) {
        return Boolean.TRUE.equals(m.get(k));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapa(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> lista(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }
}
