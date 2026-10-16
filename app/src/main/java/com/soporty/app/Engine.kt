package com.soporty.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Motor de la sesión del lado del cliente (rol "client", el lado «cortés» de la negociación).
 *
 * Implementa el mismo protocolo que la página web de Soporty:
 *  - Sondeo HTTP a api/rtc.php (poll) y envío agrupado de señales (signal).
 *  - Señales: hello / ack / desc / ice / ctl, todas con el identificador de instancia "pid".
 *  - Negociación "perfecta" de WebRTC; el técnico crea el canal de datos "ctl".
 */
class Engine(private val ctx: Context, private val session: SavedSession) {

    private val tag = "SoportyEngine"
    private val auth = session.auth
    private val sid = session.id

    // Todo lo de WebRTC y la señalización entrante se procesa en este hilo, en orden.
    private val exec = SafeExec(Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sp-rtc") })
    // Envío de señales (agrupadas) y peticiones sueltas.
    private val io = SafeExec(Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sp-io") })

    @Volatile private var running = false
    private val pid = UUID.randomUUID().toString().replace("-", "").take(16)
    private var remotePid: String? = null
    private var after = 0L; private var mAfter = 0L; private var fAfter = 0L
    private var status = ""
    @Volatile var connected = false; private set

    private lateinit var egl: EglBase
    private lateinit var adm: JavaAudioDeviceModule
    private lateinit var factory: PeerConnectionFactory
    private lateinit var audioSource: AudioSource
    private lateinit var audioTrack: AudioTrack
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private val senders = HashMap<String, RtpSender>()
    private val pendingIce = ArrayList<IceCandidate>()
    private var makingOffer = false

    // Pantalla
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    @Volatile private var stoppingScreen = false

    // Cola de señales salientes
    private val outbox = ArrayList<JSONObject>()
    @Volatile private var flushScheduled = false

    /* ------------------------------------------------------------------ arranque */

    fun start() {
        if (running) return
        running = true
        exec.execute { initRtc() }
        Thread({ pollLoop() }, "sp-poll").start()
    }

    private fun initRtc() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        egl = EglBase.create()
        adm = JavaAudioDeviceModule.builder(ctx.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val ac = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = factory.createAudioSource(ac)
        audioTrack = factory.createAudioTrack("mic0", audioSource)
        audioTrack.setEnabled(Hub.state.micOn)
        iceServers = loadIce()
    }

    private fun loadIce(): List<PeerConnection.IceServer> {
        return try {
            val j = Api.get("api/ice.php?id=$sid", auth)
            val arr = j.optJSONArray("iceServers") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val urls = when (val u = s.opt("urls")) {
                    is JSONArray -> (0 until u.length()).map { u.optString(it) }
                    is String -> listOf(u)
                    else -> emptyList()
                }.filter { it.isNotEmpty() }
                if (urls.isEmpty()) return@mapNotNull null
                val b = PeerConnection.IceServer.builder(urls)
                if (s.has("username")) b.setUsername(s.optString("username")).setPassword(s.optString("credential"))
                b.createIceServer()
            }
        } catch (e: IOException) {
            Log.w(tag, "ICE: ${e.message}")
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
    }

    /* ------------------------------------------------------------------ sondeo */

    private fun pollLoop() {
        var first = true
        var failures = 0
        while (running) {
            try {
                poll(first)
                if (first) { first = false; signal("hello", JSONObject()) }
                if (failures >= 3) Hub.emit(HubEvent.Toast("Conexión recuperada"))
                failures = 0
            } catch (e: ApiException) {
                if (e.status == 401 || e.status == 403) { Hub.emit(HubEvent.Fatal(e.message ?: "Sin acceso")); shutdown(); return }
                failures++
            } catch (e: Exception) {
                failures++
                if (failures == 3) Hub.emit(HubEvent.Toast("Sin conexión con el servidor. Reintentando…"))
            }
            try { Thread.sleep(if (connected) 1500 else 700) } catch (_: InterruptedException) { return }
        }
    }

    private fun poll(init: Boolean) {
        val body = JSONObject().put("action", "poll").put("id", sid).put("role", "client")
            .put("after", after).put("m_after", mAfter).put("f_after", fAfter).put("init", if (init) 1 else 0)
        val d = Api.post("api/rtc.php", body, auth)
        after = max(after, d.optLong("cursor", after))
        val st = d.optString("status")
        val tech = d.optJSONObject("tech")?.optString("name").orEmpty()
        val started = if (d.isNull("started")) 0L else d.optLong("started", 0)
        val skew = d.optLong("now", System.currentTimeMillis() / 1000) - System.currentTimeMillis() / 1000
        Hub.update { it.copy(status = st, techName = tech.ifEmpty { it.techName }, techOnline = d.optBoolean("peer_online"),
            queuePos = d.optInt("queue_pos", 0), startedAt = started, skew = skew, running = true) }

        d.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                mAfter = max(mAfter, m.optLong("id"))
                Hub.addChat(ChatLine(m.optString("sender"), m.optString("author"), m.optString("body")))
            }
        }
        d.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                fAfter = max(fAfter, f.optLong("id"))
                if (f.optString("uploader") == "tech" && f.optString("kind") == "annotated" && !init) Hub.emit(HubEvent.Image(f.optString("url")))
            }
        }
        d.optJSONArray("signals")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                exec.execute { try { onSignal(s) } catch (e: Exception) { Log.w(tag, "señal: ${e.message}") } }
            }
        }
        if (st == "ended" || st == "cancelled") { status = st; shutdown() }
        status = st
    }

    /* ------------------------------------------------------------------ señales */

    private fun signal(type: String, payload: JSONObject) {
        if (!running) return
        payload.put("pid", pid)
        synchronized(outbox) { outbox.add(JSONObject().put("type", type).put("payload", payload)) }
        if (!flushScheduled) { flushScheduled = true; io.schedule({ flush() }, 40, TimeUnit.MILLISECONDS) }
    }

    private fun flush() {
        flushScheduled = false
        val batch: List<JSONObject>
        synchronized(outbox) { batch = outbox.take(80); repeat(batch.size) { outbox.removeAt(0) } }
        if (batch.isEmpty()) return
        try {
            Api.post("api/rtc.php", JSONObject().put("action", "signal").put("id", sid).put("role", "client").put("signals", JSONArray(batch)), auth)
        } catch (e: ApiException) {
            Log.w(tag, "signal: ${e.message}")
        } catch (e: IOException) {
            synchronized(outbox) { outbox.addAll(0, batch) }       // sin red: reintentar
            flushScheduled = true
            io.schedule({ flush() }, 1500, TimeUnit.MILLISECONDS)
            return
        }
        if (synchronized(outbox) { outbox.isNotEmpty() } && !flushScheduled) { flushScheduled = true; io.execute { flush() } }
    }

    private fun onSignal(s: JSONObject) {
        val p = s.optJSONObject("payload") ?: JSONObject()
        if (s.optString("sender") == "system") {
            if (s.optString("type") == "ended") shutdown()
            return
        }
        val from = p.optString("pid")
        when (s.optString("type")) {
            "hello" -> { remotePid = from; rebuild(); signal("ack", JSONObject()) }
            "ack" -> if (from != remotePid) { remotePid = from; rebuild() }
            "desc" -> if (from == remotePid && pc != null) p.optJSONObject("sdp")?.let { onDescription(it) }
            "ice" -> if (from == remotePid && pc != null) p.optJSONObject("c")?.let { onRemoteIce(it) }
            "ctl" -> p.optJSONObject("data")?.let { onCtl(it) }
        }
    }

    /* ------------------------------------------------------------------ WebRTC */

    private inner class Obs : PeerConnection.Observer {
        var owner: PeerConnection? = null
        private fun mine() = owner != null && owner === pc

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}   // el audio remoto suena solo

        override fun onIceCandidate(c: IceCandidate?) {
            if (c == null) return
            exec.execute {
                if (!mine()) return@execute
                signal("ice", JSONObject().put("c", JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)))
            }
        }
        override fun onDataChannel(channel: DataChannel?) { if (channel != null) exec.execute { if (mine()) bindDc(channel) } }
        override fun onRenegotiationNeeded() { exec.execute { if (mine()) negotiate() } }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            exec.execute {
                if (!mine()) return@execute
                val now = state == PeerConnection.PeerConnectionState.CONNECTED
                if (now != connected) {
                    connected = now
                    Hub.update { it.copy(connected = now) }
                    if (now) {
                        ctl(JSONObject().put("t", "device").put("d", "mobile").put("share", true).put("app", true))
                        ctl(JSONObject().put("t", "screen").put("on", videoTrack != null))
                    }
                }
            }
        }
    }

    /** Rehace la conexión desde cero (cuando el otro lado entra o recarga). */
    private fun rebuild() {
        closePc()
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val obs = Obs()
        val p = factory.createPeerConnection(cfg, obs) ?: return
        obs.owner = p
        pc = p
        senders["audio"] = p.addTrack(audioTrack, listOf("s0"))
        videoTrack?.let { senders["video"] = p.addTrack(it, listOf("s0")) }
    }

    private fun closePc() {
        try { dc?.unregisterObserver(); dc?.close() } catch (_: Exception) {}
        dc = null
        try { pc?.close(); pc?.dispose() } catch (_: Exception) {}
        pc = null
        senders.clear(); pendingIce.clear(); makingOffer = false
        if (connected) { connected = false; Hub.update { it.copy(connected = false) } }
    }

    private fun negotiate() {
        val p = pc ?: return
        if (p.signalingState() != PeerConnection.SignalingState.STABLE) return   // se reintenta al volver a estable
        try {
            makingOffer = true
            val offer = createSdp(p, true) ?: return
            if (!setLocal(p, offer)) return
            signal("desc", JSONObject().put("sdp", JSONObject().put("type", "offer").put("sdp", offer.description)))
        } finally { makingOffer = false }
    }

    private fun onDescription(j: JSONObject) {
        val p = pc ?: return
        val type = j.optString("type")
        val sdp = j.optString("sdp")
        if (type.isEmpty() || sdp.isEmpty()) return
        val collision = type == "offer" && (makingOffer || p.signalingState() != PeerConnection.SignalingState.STABLE)
        // Somos el lado «cortés»: ante un choque de ofertas descartamos la nuestra.
        if (collision) setLocal(p, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
        if (!setRemote(p, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))) return
        pendingIce.forEach { p.addIceCandidate(it) }
        pendingIce.clear()
        if (type == "offer") {
            val answer = createSdp(p, false) ?: return
            if (setLocal(p, answer)) signal("desc", JSONObject().put("sdp", JSONObject().put("type", "answer").put("sdp", answer.description)))
        }
    }

    private fun onRemoteIce(c: JSONObject) {
        val cand = c.optString("candidate")
        if (cand.isEmpty()) return
        val ice = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), cand)
        val p = pc ?: return
        if (p.remoteDescription == null) pendingIce.add(ice) else p.addIceCandidate(ice)
    }

    /* Ayudas síncronas (se ejecutan en el hilo sp-rtc; WebRTC responde en su propio hilo). */
    private fun createSdp(p: PeerConnection, offer: Boolean): SessionDescription? {
        val latch = CountDownLatch(1); var out: SessionDescription? = null
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) { out = d; latch.countDown() }
            override fun onCreateFailure(e: String?) { Log.w(tag, "create: $e"); latch.countDown() }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }
        if (offer) p.createOffer(obs, MediaConstraints()) else p.createAnswer(obs, MediaConstraints())
        latch.await(10, TimeUnit.SECONDS)
        return out
    }
    private fun setLocal(p: PeerConnection, d: SessionDescription) = setSdp(p, d, true)
    private fun setRemote(p: PeerConnection, d: SessionDescription) = setSdp(p, d, false)
    private fun setSdp(p: PeerConnection, d: SessionDescription, local: Boolean): Boolean {
        val latch = CountDownLatch(1); var ok = false
        val obs = object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { ok = true; latch.countDown() }
            override fun onSetFailure(e: String?) { Log.w(tag, "set: $e"); latch.countDown() }
        }
        if (local) p.setLocalDescription(obs, d) else p.setRemoteDescription(obs, d)
        latch.await(10, TimeUnit.SECONDS)
        return ok
    }

    /* ------------------------------------------------------------------ canal de control */

    private fun bindDc(channel: DataChannel) {
        dc = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null || buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                try { val j = JSONObject(String(bytes, Charsets.UTF_8)); exec.execute { onCtl(j) } } catch (_: Exception) {}
            }
        })
    }

    private fun ctl(data: JSONObject) {
        val d = dc
        if (d != null && d.state() == DataChannel.State.OPEN) {
            val ok = d.send(DataChannel.Buffer(ByteBuffer.wrap(data.toString().toByteArray(Charsets.UTF_8)), false))
            if (ok) return
        }
        signal("ctl", JSONObject().put("data", data))
    }

    private fun onCtl(m: JSONObject) {
        when (m.optString("t")) {
            "ptr", "stroke", "clear" -> android.os.Handler(android.os.Looper.getMainLooper()).post { MarksOverlay.apply(m) }
            "req-screen" -> if (videoTrack == null) Hub.emit(HubEvent.RequestScreen)
            "req-capture" -> Hub.emit(HubEvent.RequestCapture)
            "hi" -> m.optString("name").takeIf { it.isNotEmpty() }?.let { n -> Hub.update { it.copy(techName = n) } }
        }
    }

    /* ------------------------------------------------------------------ pantalla */

    /** `data` es el Intent que devuelve el aviso de Android «¿Compartir pantalla?». */
    fun startScreen(data: Intent) = exec.execute {
        if (!::factory.isInitialized) return@execute
        stopScreenInternal(notify = false)
        try {
            val helper = SurfaceTextureHelper.create("sp-screen", egl.eglBaseContext)
            val src = factory.createVideoSource(true)
            val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                override fun onStop() {
                    // Android detuvo la transmisión (botón del sistema, bloqueo, etc.)
                    if (!stoppingScreen) exec.execute { stopScreenInternal(notify = true, cut = true) }
                }
            })
            cap.initialize(helper, ctx.applicationContext, src.capturerObserver)
            val (w, h) = captureSize()
            cap.startCapture(w, h, 15)
            val track = factory.createVideoTrack("screen0", src)
            surfaceHelper = helper; videoSource = src; capturer = cap; videoTrack = track
            setVideo(track)
            Hub.update { it.copy(sharing = true) }
            ctl(JSONObject().put("t", "screen").put("on", true))
        } catch (e: Exception) {
            Log.e(tag, "pantalla", e)
            Hub.emit(HubEvent.Toast("No se pudo compartir la pantalla: ${e.message}"))
            stopScreenInternal(notify = false)
        }
    }

    fun stopScreen() = exec.execute { stopScreenInternal(notify = true, cut = false) }

    private fun stopScreenInternal(notify: Boolean, cut: Boolean = false) {
        val had = videoTrack != null
        stoppingScreen = true
        try { capturer?.stopCapture() } catch (_: Exception) {}
        if (had) setVideo(null)
        try { capturer?.dispose() } catch (_: Exception) {}
        try { videoTrack?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        capturer = null; videoTrack = null; videoSource = null; surfaceHelper = null
        stoppingScreen = false
        if (had) {
            Hub.update { it.copy(sharing = false) }
            if (notify) ctl(JSONObject().put("t", "screen").put("on", false).put("cut", cut))
            if (cut) Hub.emit(HubEvent.ScreenCut)
        }
    }

    private fun setVideo(track: VideoTrack?) {
        val s = senders["video"]
        if (s != null) s.setTrack(track, false)
        else if (track != null) pc?.let { senders["video"] = it.addTrack(track, listOf("s0")) }
    }

    /** Al girar el teléfono, ajustar el tamaño de la captura. */
    fun onRotate() = exec.execute {
        val cap = capturer ?: return@execute
        val (w, h) = captureSize()
        try { cap.changeCaptureFormat(w, h, 15) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun captureSize(): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (rw, rh) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds; b.width() to b.height()
        } else {
            val m = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(m); m.widthPixels to m.heightPixels
        }
        val scale = minOf(1f, 1280f / max(rw, rh))
        fun even(v: Float) = ((v.roundToInt()) / 2) * 2
        return even(rw * scale) to even(rh * scale)
    }

    /* ------------------------------------------------------------------ acciones */

    fun setMic(on: Boolean) = exec.execute {
        if (::audioTrack.isInitialized) audioTrack.setEnabled(on)
        Hub.update { it.copy(micOn = on) }
    }

    fun sendMessage(text: String) = io.execute {
        try { Api.post("api/rtc.php", JSONObject().put("action", "message").put("id", sid).put("role", "client").put("body", text), auth) }
        catch (e: IOException) { Hub.emit(HubEvent.Toast(e.message ?: "No se pudo enviar")) }
    }

    /** Termina la sesión por decisión del cliente. */
    fun end() = io.execute {
        try { Api.post("api/rtc.php", JSONObject().put("action", "end").put("id", sid).put("role", "client"), auth) } catch (_: IOException) {}
        shutdown()
    }

    fun shutdown() {
        if (!running) return
        running = false
        exec.execute {
            stopScreenInternal(notify = false)
            closePc()
            try { if (::audioTrack.isInitialized) audioTrack.dispose() } catch (_: Exception) {}
            try { if (::audioSource.isInitialized) audioSource.dispose() } catch (_: Exception) {}
            try { if (::factory.isInitialized) factory.dispose() } catch (_: Exception) {}
            try { if (::adm.isInitialized) adm.release() } catch (_: Exception) {}
            try { if (::egl.isInitialized) egl.release() } catch (_: Exception) {}
        }
        exec.shutdown()
        io.schedule({ io.shutdown() }, 2, TimeUnit.SECONDS)
        Hub.update { it.copy(running = false, connected = false, sharing = false, status = if (it.status == "active" || it.status == "") "ended" else it.status) }
    }
}

/** Ejecutor que ignora tareas recibidas después de cerrar la sesión (en lugar de lanzar error). */
private class SafeExec(private val ex: ScheduledExecutorService) {
    fun execute(r: Runnable) {
        try { if (!ex.isShutdown) ex.execute(r) } catch (_: RejectedExecutionException) {}
    }
    fun schedule(r: Runnable, delay: Long, unit: TimeUnit) {
        try { if (!ex.isShutdown) ex.schedule(r, delay, unit) } catch (_: RejectedExecutionException) {}
    }
    fun shutdown() = ex.shutdown()
}
