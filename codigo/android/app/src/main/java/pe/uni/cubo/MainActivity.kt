package pe.uni.cubo

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Borg IA Cube: chat Android que envía imágenes al backend y muestra el diagnóstico de la CNN. */
class MainActivity : Activity() {
    private lateinit var con: Conexion
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var texto: EditText
    private var modelo = "A"

    // prueba de estrés: idMensajeCliente → [t_envio, t_ack, t_resultado], estado y modelo
    private val tiempos = ConcurrentHashMap<String, LongArray>()
    private val estados = ConcurrentHashMap<String, String>()
    private val respondidas = AtomicInteger()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pantallaLogin()
    }

    private fun pantallaLogin() {
        val host = EditText(this).apply { hint = "IP del servidor"; setText("192.168.1.10") }
        val puerto = EditText(this).apply { hint = "Puerto"; setText("5000") }
        val usuario = EditText(this).apply { hint = "Usuario"; setText("ana") }
        val clave = EditText(this).apply {
            hint = "Clave"; setText("clave123")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val col = columna(titulo("Borg IA Cube"), host, puerto, usuario, clave)
        for ((etiqueta, registrar) in listOf("Ingresar" to false, "Registrarse" to true)) {
            col.addView(Button(this).apply {
                text = etiqueta
                setOnClickListener {
                    Thread {
                        try {
                            con = Conexion(::alMensaje) { m -> runOnUiThread { aviso("Desconectado: $m") } }
                            con.conectar(host.text.toString().trim(), puerto.text.toString().toInt())
                            con.login(usuario.text.toString().trim(), clave.text.toString(), registrar)
                        } catch (e: Exception) {
                            runOnUiThread { aviso("No se pudo conectar: ${e.message}") }
                        }
                    }.start()
                }
            })
        }
        setContentView(col)
    }

    private fun pantallaChat(nombre: String) {
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(chat) }
        texto = EditText(this).apply { hint = "Mensaje (opcional)" }
        val botonModelo = Button(this)
        botonModelo.text = "Modelo A: malaria"
        botonModelo.setOnClickListener {
            modelo = if (modelo == "A") "B" else "A"
            botonModelo.text = if (modelo == "A") "Modelo A: malaria" else "Modelo B: células sanguíneas"
        }
        val fila = LinearLayout(this).apply {
            addView(boton("Imagen") { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 1) })
            addView(boton("Historial") { Thread { con.historial("h-" + UUID.randomUUID()) }.start() })
            addView(boton("Estrés") { estres(1000, 50) })
        }
        val col = columna(titulo("Borg IA Cube — $nombre (id ${con.idCliente})"), botonModelo, fila, texto)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(col)
        burbuja("BorgAICube: elige el modelo y envíame una imagen.", false)
    }

    @Deprecated("API clásica, suficiente para elegir una imagen")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data ?: return
        val png = contentResolver.openInputStream(uri)!!.use { pngReducido(it.readBytes()) }
        val msg = texto.text.toString()
        burbuja("Yo: imagen enviada al modelo $modelo ${if (msg.isEmpty()) "" else "— $msg"}", true)
        texto.setText("")
        Thread { con.consulta("c-" + UUID.randomUUID(), modelo, png, msg) }.start()
    }

    /** Reduce la imagen a 256 px como máximo y la devuelve como PNG en Base64. */
    private fun pngReducido(datos: ByteArray): String {
        var bmp = BitmapFactory.decodeByteArray(datos, 0, datos.size)
        val esc = 256f / maxOf(bmp.width, bmp.height)
        if (esc < 1) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * esc).toInt(), (bmp.height * esc).toInt(), true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Llega por el hilo lector del socket. */
    private fun alMensaje(m: JSONObject) {
        val id = m.optString("idMensajeCliente")
        if (id.startsWith("e-")) { // respuesta de la prueba de estrés
            val t = tiempos[id] ?: return
            when (m.optString("tipo")) {
                "ACK" -> t[1] = System.currentTimeMillis()
                "RESULTADO", "ERROR" -> {
                    t[2] = System.currentTimeMillis()
                    estados[id] = if (m.optString("tipo") == "RESULTADO") "OK" else "ERROR," + m.optString("codigo")
                    respondidas.incrementAndGet()
                }
            }
            return
        }
        runOnUiThread {
            when (m.optString("tipo")) {
                "LOGIN_OK" -> {
                    con.token = m.getString("token"); con.idCliente = m.getLong("idCliente")
                    pantallaChat(m.optString("nombre"))
                }
                "LOGIN_ERROR" -> aviso("Error: " + m.optString("motivo"))
                "ACK" -> burbuja("(en cola: ${m.optString("idSolicitud").take(8)})", false)
                "RESULTADO" -> burbuja("BorgAICube: Diagnóstico (Modelo ${m.optString("modelo")}): " +
                        "${m.optString("etiqueta")} con ${"%.1f".format(m.optDouble("probabilidad") * 100)} % de confianza. " +
                        "Nodo IA ${m.optString("nodoIA")}, ${m.optLong("latenciaTotalMs")} ms.", false)
                "ERROR" -> burbuja("⚠ ${m.optString("codigo")}: ${m.optString("mensaje")}", false)
                "HISTORIAL" -> {
                    val items = m.optJSONArray("items")
                    val sb = StringBuilder("Historial:")
                    for (i in 0 until (items?.length() ?: 0)) {
                        val it = items!!.getJSONObject(i)
                        sb.append("\n• ${it.optString("fecha").take(19)}  ${it.optString("modelo")}  ${it.optString("estado")}  " +
                                "${it.optString("etiqueta")} ${"%.1f".format(it.optDouble("probabilidad", 0.0) * 100)} %")
                    }
                    burbuja(sb.toString(), false)
                }
            }
        }
    }

    /** Envía n imágenes de assets/muestras con un delay aleatorio y guarda carga_ANDROID.csv. */
    private fun estres(n: Int, delayMax: Int) {
        val muestras = assets.list("muestras")!!.map { nombre ->
            nombre.take(1) to Base64.encodeToString(assets.open("muestras/$nombre").readBytes(), Base64.NO_WRAP)
        }
        tiempos.clear(); estados.clear(); respondidas.set(0)
        burbuja("Estrés: enviando $n imágenes (delay 0–$delayMax ms)...", false)
        Thread {
            val inicio = System.currentTimeMillis()
            val modelos = HashMap<String, String>()
            for (i in 0 until n) {
                val (mod, png) = muestras[Random.nextInt(muestras.size)]
                val id = "e-$i-" + UUID.randomUUID().toString().take(8)
                modelos[id] = mod
                tiempos[id] = longArrayOf(System.currentTimeMillis(), 0, 0)
                runCatching { con.consulta(id, mod, png, "") }.onFailure { estados[id] = "ERROR,ENVIO"; respondidas.incrementAndGet() }
                Thread.sleep(Random.nextLong(delayMax + 1L))
            }
            val limite = System.currentTimeMillis() + 60000
            while (respondidas.get() < n && System.currentTimeMillis() < limite) Thread.sleep(200)
            val fin = System.currentTimeMillis()
            val f = File(getExternalFilesDir(null), "carga_ANDROID.csv")
            var ok = 0
            var suma = 0L
            f.printWriter().use { w ->
                w.println("idMensajeCliente,idSolicitud,plataforma,modelo,t_envio,t_ack,t_resultado,latencia_ms,estado,codigo_error")
                for ((id, t) in tiempos) {
                    val est = estados[id] ?: "ERROR,TIMEOUT"
                    val lat = if (t[2] > 0) t[2] - t[0] else -1
                    if (est == "OK") { ok++; suma += lat }
                    w.println("$id,,ANDROID,${modelos[id]},${t[0]},${t[1]},${t[2]},$lat,${if (est == "OK") "OK," else est}")
                }
            }
            val seg = (fin - inicio) / 1000.0
            runOnUiThread {
                burbuja("Estrés terminado: $ok/$n OK, errores ${n - ok}, latencia media ${if (ok > 0) suma / ok else 0} ms, " +
                        "throughput ${"%.1f".format(ok / seg)} tx/s. CSV: ${f.absolutePath}", false)
            }
        }.start()
    }

    // --- interfaz mínima construida por código ---
    private fun columna(vararg vs: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 24, 24, 24)
        vs.forEach { addView(it) }
    }

    private fun titulo(t: String) = TextView(this).apply { text = t; textSize = 20f }

    private fun boton(t: String, accion: () -> Unit) = Button(this).apply { text = t; setOnClickListener { accion() } }

    private fun burbuja(t: String, mio: Boolean) {
        chat.addView(TextView(this).apply {
            text = t
            setPadding(20, 12, 20, 12)
            setBackgroundColor(if (mio) 0xFFDCF8C6.toInt() else 0xFFECEFF1.toInt())
            gravity = if (mio) Gravity.END else Gravity.START
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 0) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        if (::con.isInitialized) con.cerrar()
    }
}
