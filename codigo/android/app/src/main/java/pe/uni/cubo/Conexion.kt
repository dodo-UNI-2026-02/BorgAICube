package pe.uni.cubo

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Socket TCP propio hacia el backend. Protocolo: [4 bytes longitud big-endian][JSON UTF-8].
 * Un hilo lee las tramas y se las pasa a [alMensaje]; escribir está sincronizado para
 * que dos hilos (chat, prueba de estrés, ping) no mezclen bytes de tramas distintas.
 */
class Conexion(private val alMensaje: (JSONObject) -> Unit, private val alCerrar: (String) -> Unit) {
    private lateinit var socket: Socket
    private lateinit var salida: DataOutputStream
    @Volatile var token = ""
    @Volatile var idCliente = 0L

    fun conectar(host: String, puerto: Int) {
        socket = Socket()
        socket.connect(InetSocketAddress(host, puerto), 5000)
        socket.tcpNoDelay = true
        salida = DataOutputStream(socket.getOutputStream())
        Thread({ leer() }, "lector").start()
        Thread({ // PING para que el backend no cierre la sesión por inactividad (45 s)
            while (!socket.isClosed) {
                Thread.sleep(20000)
                runCatching { enviar(JSONObject().put("tipo", "PING")) }
            }
        }, "ping").start()
    }

    private fun leer() {
        try {
            val entrada = DataInputStream(socket.getInputStream().buffered(65536))
            while (true) {
                val b = ByteArray(entrada.readInt())
                entrada.readFully(b)
                alMensaje(JSONObject(String(b, Charsets.UTF_8)))
            }
        } catch (e: Exception) {
            alCerrar(e.message ?: "conexión cerrada")
        }
    }

    fun enviar(m: JSONObject) {
        val b = m.put("timestamp", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
        synchronized(this) {
            salida.writeInt(b.size)
            salida.write(b)
            salida.flush()
        }
    }

    fun login(usuario: String, clave: String, registrar: Boolean) = enviar(JSONObject()
        .put("tipo", if (registrar) "REGISTRO" else "LOGIN").put("usuario", usuario).put("password", clave)
        .put("nombre", usuario).put("plataforma", "ANDROID"))

    fun consulta(idMensaje: String, modelo: String, png: String, texto: String) = enviar(JSONObject()
        .put("tipo", "CONSULTA").put("token", token).put("idCliente", idCliente).put("idMensajeCliente", idMensaje)
        .put("tipoConsulta", "IMAGEN").put("modelo", modelo).put("formato", "png").put("payload", png)
        .put("texto", texto).put("plataforma", "ANDROID"))

    fun historial(idMensaje: String) = enviar(JSONObject()
        .put("tipo", "CONSULTA").put("token", token).put("idCliente", idCliente).put("idMensajeCliente", idMensaje)
        .put("tipoConsulta", "HISTORIAL").put("limite", 10))

    fun cerrar() = runCatching { socket.close() }
}
