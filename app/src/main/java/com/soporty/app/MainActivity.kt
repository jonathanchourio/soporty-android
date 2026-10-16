package com.soporty.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import java.io.IOException

class MainActivity : Activity() {

    private val REQ_PERMS = 10
    private val REQ_SCREEN = 11
    private val main = Handler(Looper.getMainLooper())
    private var afterPerms: (() -> Unit)? = null
    private var hadSession = false

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var timer: TextView
    private lateinit var panelJoin: View
    private lateinit var panelSession: View
    private lateinit var panelEnded: View
    private lateinit var code: EditText
    private lateinit var name: EditText
    private lateinit var consent: CheckBox
    private lateinit var joinError: TextView
    private lateinit var btnJoin: Button
    private lateinit var status: TextView
    private lateinit var lastBox: View
    private lateinit var lastWho: TextView
    private lateinit var lastBody: TextView
    private lateinit var btnShare: Button
    private lateinit var btnMic: Button
    private lateinit var btnOverlay: Button
    private lateinit var shareTip: TextView
    private lateinit var chatLog: TextView
    private lateinit var msg: EditText
    private lateinit var btnEnd: Button
    private lateinit var rating: RatingBar

    private val stateL: (UiState) -> Unit = { render(it) }
    private val eventL: (HubEvent) -> Unit = { onHubEvent(it) }
    private val chatL: (ChatLine) -> Unit = { appendChat(it) }

    private val tick = object : Runnable {
        override fun run() {
            val s = Hub.state
            if (s.startedAt > 0 && s.running) {
                val sec = (System.currentTimeMillis() / 1000 + s.skew - s.startedAt).coerceAtLeast(0)
                timer.text = String.format("%02d:%02d", sec / 60, sec % 60)
                timer.visibility = View.VISIBLE
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()
        Hub.onState(stateL); Hub.onEvent(eventL); Hub.onChat(chatL)
        Hub.chat.forEach { appendChat(it) }
        name.setText(Prefs.lastName(this))
        if (!handleIntent(intent)) maybeResume()
        main.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        btnOverlay.visibility = if (Hub.state.running && !MarksOverlay.canShow(this)) View.VISIBLE else View.GONE
        if (Hub.state.running && MarksOverlay.canShow(this)) SupportService.send(this, SupportService.ACTION_OVERLAY)
    }

    override fun onDestroy() {
        Hub.remove(stateL, eventL, chatL)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ vistas */

    private fun bindViews() {
        title = findViewById(R.id.title); subtitle = findViewById(R.id.subtitle); timer = findViewById(R.id.timer)
        panelJoin = findViewById(R.id.panelJoin); panelSession = findViewById(R.id.panelSession); panelEnded = findViewById(R.id.panelEnded)
        code = findViewById(R.id.code); name = findViewById(R.id.name); consent = findViewById(R.id.consent)
        joinError = findViewById(R.id.joinError); btnJoin = findViewById(R.id.btnJoin)
        status = findViewById(R.id.status); lastBox = findViewById(R.id.lastBox); lastWho = findViewById(R.id.lastWho); lastBody = findViewById(R.id.lastBody)
        btnShare = findViewById(R.id.btnShare); btnMic = findViewById(R.id.btnMic); btnOverlay = findViewById(R.id.btnOverlay); shareTip = findViewById(R.id.shareTip)
        chatLog = findViewById(R.id.chatLog); msg = findViewById(R.id.msg); btnEnd = findViewById(R.id.btnEnd); rating = findViewById(R.id.rating)

        subtitle.text = Api.base.removePrefix("https://").removePrefix("http://")
        // Formato «123 456 789» mientras se escribe
        code.addTextChangedListener(object : TextWatcher {
            private var busy = false
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (busy || e == null) return
                busy = true
                val d = e.toString().filter { it.isDigit() }.take(9)
                val f = d.chunked(3).joinToString(" ")
                if (f != e.toString()) { e.replace(0, e.length, f) }
                busy = false
            }
        })
        btnJoin.setOnClickListener { join() }
        btnShare.setOnClickListener {
            if (Hub.state.sharing) SupportService.send(this, SupportService.ACTION_STOP_SHARE) else requestScreen()
        }
        btnMic.setOnClickListener { SupportService.send(this, SupportService.ACTION_MIC) }
        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            toast("Activa «Permitir mostrar sobre otras apps» y regresa a la app.")
        }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendMsg() }
        msg.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_NULL) { sendMsg(); true } else false }
        btnEnd.setOnClickListener {
            AlertDialog.Builder(this).setTitle("¿Terminar la sesión?").setMessage("Se cortará la llamada con tu técnico.")
                .setPositiveButton("Terminar") { _, _ -> SupportService.send(this, SupportService.ACTION_END) }
                .setNegativeButton("Cancelar", null).show()
        }
        findViewById<Button>(R.id.btnRate).setOnClickListener { rate() }
        findViewById<Button>(R.id.btnNew).setOnClickListener {
            Prefs.clear(this); hadSession = false; Hub.reset(); chatLog.text = ""; lastBox.visibility = View.GONE
            code.setText(""); show(panelJoin)
        }
    }

    /** Con targetSdk 35 la app dibuja bajo las barras del sistema: se agrega el espacio necesario. */
    @Suppress("DEPRECATION")
    private fun applyInsets() {
        val scroll = findViewById<View>(R.id.scroll)
        val base = (20 * resources.displayMetrics.density).toInt()
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                i.top to i.bottom
            } else {
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            findViewById<View>(R.id.root).setPadding(base, base, base, base)
            insets
        }
    }

    private fun show(panel: View) {
        panelJoin.visibility = if (panel === panelJoin) View.VISIBLE else View.GONE
        panelSession.visibility = if (panel === panelSession) View.VISIBLE else View.GONE
        panelEnded.visibility = if (panel === panelEnded) View.VISIBLE else View.GONE
        if (panel !== panelSession) timer.visibility = View.GONE
    }

    private fun render(s: UiState) {
        if (s.running) {
            hadSession = true
            show(panelSession)
            title.text = s.company.ifEmpty { getString(R.string.app_name) }
            val tech = s.techName.ifEmpty { "tu técnico" }
            status.text = when {
                s.status == "waiting" -> if (s.queuePos > 0) "Estás en la fila (lugar ${s.queuePos}). Un técnico te atenderá en un momento." else "Un técnico te atenderá en un momento."
                !s.techOnline -> "Esperando a tu técnico…"
                !s.connected -> "Conectando la llamada con $tech…"
                s.sharing -> "🟢 En llamada con $tech · está viendo tu pantalla"
                else -> "🟢 En llamada con $tech"
            }
            btnShare.text = getString(if (s.sharing) R.string.stop_share else R.string.share)
            btnShare.setBackgroundResource(if (s.sharing) R.drawable.bg_btn_danger else R.drawable.bg_btn)
            btnMic.text = getString(if (s.micOn) R.string.mic_on else R.string.mic_off)
            btnOverlay.visibility = if (!MarksOverlay.canShow(this)) View.VISIBLE else View.GONE
            shareTip.text = if (s.sharing)
                "Puedes salir de la app y abrir lo que tu técnico te indique: seguirá viendo tu pantalla. Para dejar de compartir usa la notificación."
            else "Al tocar «Compartir pantalla», Android te preguntará si quieres compartir. Elige «Toda la pantalla»."
        } else if (hadSession && (s.status == "ended" || s.status == "cancelled")) {
            show(panelEnded)
        }
    }

    private fun appendChat(l: ChatLine) {
        val who = when (l.sender) { "tech" -> l.author.ifEmpty { "Técnico" }; "client" -> "Tú"; else -> "" }
        val line = if (who.isEmpty()) "· ${l.body}" else "$who: ${l.body}"
        chatLog.append(if (chatLog.text.isEmpty()) line else "\n$line")
        if (l.sender == "tech") {
            lastBox.visibility = View.VISIBLE
            lastWho.text = "${l.author.ifEmpty { "Tu técnico" }} dice:"
            lastBody.text = l.body
        }
    }

    /* ------------------------------------------------------------------ entrar */

    /** Devuelve true si el enlace trae un pase para tomar una sesión desde la página web. */
    private fun handleIntent(i: Intent?): Boolean {
        val data = i?.data ?: return false
        if (data.scheme != "soporty") return false
        val c = data.getQueryParameter("code")?.filter { it.isDigit() }.orEmpty()
        val h = data.getQueryParameter("h").orEmpty()
        if (c.length == 9) code.setText(c)
        if (c.length == 9 && h.isNotEmpty() && !Hub.state.running) {
            // Viene de la página de soporte: tomar la sesión sin volver a pedir datos
            withPermissions { claim(c, h) }
            return true
        }
        return false
    }

    private fun maybeResume() {
        if (Hub.state.running) { hadSession = true; render(Hub.state); return }
        val saved = Prefs.load(this) ?: return
        Thread {
            try {
                val r = Api.post("api/app.php", JSONObject().put("action", "resume"), saved.auth)
                val st = r.optString("status")
                main.post {
                    if (st == "active" || st == "waiting") withPermissions { startSession(saved) }
                    else { hadSession = true; Hub.update { it.copy(status = st, running = false) }; show(panelEnded) }
                }
            } catch (e: ApiException) {
                main.post { Prefs.clear(this) }
            } catch (_: IOException) {}
        }.start()
    }

    private fun join() {
        joinError.visibility = View.GONE
        val c = code.text.toString().filter { it.isDigit() }
        val n = name.text.toString().trim()
        when {
            c.length != 9 -> return showJoinError("El código debe tener 9 números.")
            n.isEmpty() -> return showJoinError("Escribe tu nombre.")
            !consent.isChecked -> return showJoinError("Necesitamos tu autorización para continuar.")
        }
        Prefs.saveName(this, n)
        withPermissions {
            btnJoin.isEnabled = false
            Thread {
                try {
                    val r = Api.post("api/app.php", JSONObject().put("action", "join").put("code", c).put("name", n).put("app_version", BuildConfig.VERSION_NAME))
                    main.post { startSession(fromResponse(r)) }
                } catch (e: IOException) {
                    main.post { showJoinError(e.message ?: "Sin conexión"); btnJoin.isEnabled = true }
                }
            }.start()
        }
    }

    private fun claim(c: String, h: String) {
        show(panelJoin); btnJoin.isEnabled = false; joinError.visibility = View.GONE
        Thread {
            try {
                val r = Api.post("api/app.php", JSONObject().put("action", "claim").put("code", c).put("h", h).put("app_version", BuildConfig.VERSION_NAME))
                main.post { startSession(fromResponse(r)) }
            } catch (e: IOException) {
                main.post { showJoinError(e.message ?: "Sin conexión"); btnJoin.isEnabled = true }
            }
        }.start()
    }

    private fun fromResponse(r: JSONObject) = SavedSession(r.getInt("id"), r.getString("token"), r.optString("code"), r.optString("company"), r.optString("name"))

    private fun startSession(s: SavedSession) {
        Prefs.save(this, s)
        btnJoin.isEnabled = true
        hadSession = true
        Hub.reset()
        chatLog.text = ""
        lastBox.visibility = View.GONE
        Hub.update { it.copy(company = s.company, running = true, status = "active") }
        SupportService.send(this, SupportService.ACTION_START)
        show(panelSession)
    }

    private fun showJoinError(t: String) { joinError.text = t; joinError.visibility = View.VISIBLE }

    /* ------------------------------------------------------------------ permisos */

    private fun withPermissions(then: () -> Unit) {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) { then(); return }
        afterPerms = then
        requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            afterPerms?.invoke()
        } else {
            showJoinError("La app necesita el micrófono para la llamada con tu técnico. Puedes silenciarlo cuando quieras.")
            btnJoin.isEnabled = true
        }
        afterPerms = null
    }

    /* ------------------------------------------------------------------ pantalla */

    private fun requestScreen() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val i = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_SCREEN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCREEN) return
        if (resultCode == RESULT_OK && data != null) {
            SupportService.send(this, SupportService.ACTION_SHARE) { putExtra(SupportService.EXTRA_DATA, data) }
            toast("Compartiendo tu pantalla. Ya puedes salir de la app.")
        } else toast("No se compartió la pantalla.")
    }

    /* ------------------------------------------------------------------ eventos */

    private fun onHubEvent(e: HubEvent) {
        when (e) {
            is HubEvent.Toast -> toast(e.text)
            is HubEvent.RequestScreen -> AlertDialog.Builder(this).setTitle("Tu técnico quiere ver tu pantalla")
                .setMessage("Android te preguntará si quieres compartir. Tu técnico solo podrá ver, no tocar.")
                .setPositiveButton("Compartir pantalla") { _, _ -> requestScreen() }.setNegativeButton("Ahora no", null).show()
            is HubEvent.ScreenCut -> AlertDialog.Builder(this).setTitle("Se dejó de compartir tu pantalla")
                .setMessage("Pasa cuando el teléfono se bloquea o se detiene desde la notificación. Tu técnico sigue en la llamada.")
                .setPositiveButton("Volver a compartir") { _, _ -> requestScreen() }.setNegativeButton("Ahora no", null).show()
            is HubEvent.RequestCapture -> AlertDialog.Builder(this).setTitle("Tu técnico te pide ver tu pantalla")
                .setMessage("Con la app puedes compartirla en vivo en lugar de mandar capturas.")
                .setPositiveButton("Compartir pantalla") { _, _ -> requestScreen() }.setNegativeButton("Cerrar", null).show()
            is HubEvent.Image -> showImage(e.url)
            is HubEvent.Fatal -> { toast(e.text); Prefs.clear(this); hadSession = false; show(panelJoin) }
        }
    }

    private fun showImage(url: String) {
        val saved = Prefs.load(this) ?: return
        Thread {
            try {
                val b = Api.bytes(url, saved.auth)
                val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return@Thread
                main.post {
                    if (isFinishing) return@post
                    val iv = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; setPadding(24, 24, 24, 24) }
                    AlertDialog.Builder(this).setTitle("Tu técnico te marcó dónde está").setView(iv).setPositiveButton("Entendido", null).show()
                }
            } catch (_: IOException) {}
        }.start()
    }

    private fun sendMsg() {
        val t = msg.text.toString().trim()
        if (t.isEmpty() || !Hub.state.running) return
        msg.setText("")
        SupportService.send(this, SupportService.ACTION_MESSAGE) { putExtra(SupportService.EXTRA_TEXT, t) }
    }

    private fun rate() {
        val saved = Prefs.load(this) ?: return
        val r = rating.rating.toInt()
        if (r < 1) { toast("Elige de 1 a 5 estrellas."); return }
        Thread {
            try {
                Api.post("api/rtc.php", JSONObject().put("action", "rate").put("id", saved.id).put("role", "client").put("rating", r), saved.auth)
                main.post { toast("¡Gracias por tu calificación!"); findViewById<Button>(R.id.btnRate).isEnabled = false; Prefs.clear(this) }
            } catch (e: IOException) { main.post { toast(e.message ?: "No se pudo enviar") } }
        }.start()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
