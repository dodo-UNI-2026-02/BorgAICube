package cubo.backend;

import cubo.comun.Json;
import cubo.comun.Trama;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Una conexión de cliente. Varios hilos pueden escribirle (el manejador del
 * cliente, los procesadores de consultas y el consumidor de cola_respuestas),
 * así que cada escritura toma un ReentrantLock: las tramas nunca se intercalan.
 */
public class Sesion {
    final Socket socket;
    private final OutputStream out;
    private final ReentrantLock lockEscritura = new ReentrantLock();
    volatile String token;
    volatile long idCliente;
    volatile String nombre = "";
    volatile String plataforma = "";
    final long creada = System.currentTimeMillis();

    public Sesion(Socket s) throws IOException {
        this.socket = s;
        this.out = new BufferedOutputStream(s.getOutputStream(), 65536);
    }

    /** Constructor para pruebas: escribe sobre cualquier OutputStream. */
    Sesion(OutputStream out) {
        this.socket = null;
        this.out = out;
    }

    public boolean enviar(Map<String, Object> msg) {
        String json = Json.stringify(msg);
        lockEscritura.lock();
        try {
            Trama.escribir(out, json);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            lockEscritura.unlock();
        }
    }

    public boolean autenticada() { return token != null; }

    public String describir() {
        return String.format("%-10s cliente=%-6d %-22s %s", plataforma, idCliente, nombre,
                socket != null ? socket.getRemoteSocketAddress() : "-");
    }

    public void cerrar() {
        try { if (socket != null) socket.close(); } catch (IOException ignorado) { }
    }
}
