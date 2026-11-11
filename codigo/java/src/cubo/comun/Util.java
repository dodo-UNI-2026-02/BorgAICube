package cubo.comun;

import java.lang.management.ManagementFactory;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Util {
    private Util() {}

    public static String sha256(byte[] datos) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(datos);
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String ipLocal() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 80); // UDP: no envía tráfico
            return s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public static String hora() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    public static void log(String nivel, String msg) {
        System.out.println("[" + hora() + "] [" + nivel + "] " + msg);
    }

    /** CPU del proceso (0-100 % del total de núcleos) usando la JMX del propio JDK. */
    public static double cpuProceso() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                double v = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuLoad();
                return v < 0 ? 0 : v * 100;
            }
        } catch (Throwable ignorado) { }
        return 0;
    }

    public static double memoriaMB() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024.0 * 1024.0);
    }

    public static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
