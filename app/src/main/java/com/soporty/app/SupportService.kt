package com.soporty.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder

/**
 * Servicio en primer plano: mantiene la llamada y la pantalla compartida aunque el cliente
 * salga de la app (que es justo lo que pasa cuando el técnico lo guía por otras apps).
 */
class SupportService : Service() {

    companion object {
        const val ACTION_START = "com.soporty.app.START"
        const val ACTION_SHARE = "com.soporty.app.SHARE"
        const val ACTION_STOP_SHARE = "com.soporty.app.STOP_SHARE"
        const val ACTION_MIC = "com.soporty.app.MIC"
        const val ACTION_END = "com.soporty.app.END"
        const val ACTION_MESSAGE = "com.soporty.app.MESSAGE"
        const val ACTION_OVERLAY = "com.soporty.app.OVERLAY"
        const val EXTRA_DATA = "data"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "session"
        private const val CHANNEL_ALERT = "alerts"
        private const val NOTIF_ID = 7

        fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, SupportService::class.java).setAction(action).apply(extras)
            if (action == ACTION_START || action == ACTION_SHARE) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private var engine: Engine? = null
    private var session: SavedSession? = null
    private var sharing = false
    private var prevAudioMode = AudioManager.MODE_NORMAL
    private val stateListener: (UiState) -> Unit = { s -> onState(s) }
    private val eventListener: (HubEvent) -> Unit = { e -> onEvent(e) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Avisos del técnico", NotificationManager.IMPORTANCE_HIGH))
        Hub.onState(stateListener)
        Hub.onEvent(eventListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val s = Prefs.load(this) ?: run { stopSelf(); return START_NOT_STICKY }
                goForeground(false)
                if (engine == null) {
                    session = s
                    Hub.update { it.copy(company = s.company, running = true) }
                    audioForCall(true)
                    engine = Engine(this, s).also { it.start() }
                }
                if (MarksOverlay.canShow(this)) MarksOverlay.show(this)
            }
            ACTION_SHARE -> {
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_DATA)
                if (data != null && engine != null) {
                    // Android 14+: el servicio debe declararse de proyección ANTES de crear la captura
                    goForeground(true)
                    sharing = true
                    engine?.startScreen(data)
                }
            }
            ACTION_STOP_SHARE -> engine?.stopScreen()
            ACTION_MIC -> engine?.setMic(!Hub.state.micOn)
            ACTION_MESSAGE -> intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { engine?.sendMessage(it.trim()) }
            ACTION_OVERLAY -> if (MarksOverlay.canShow(this)) MarksOverlay.show(this)
            ACTION_END -> engine?.end() ?: finish()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(withScreen: Boolean) {
        val n = buildNotification(Hub.state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (withScreen) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIF_ID, n, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && withScreen) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun onState(s: UiState) {
        if (engine == null) return
        if (sharing && !s.sharing) { sharing = false; try { goForeground(false) } catch (_: Exception) {} }
        if (!s.running && (s.status == "ended" || s.status == "cancelled")) { finish(); return }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
    }

    private fun onEvent(e: HubEvent) {
        when (e) {
            is HubEvent.RequestScreen -> alert("Tu técnico quiere ver tu pantalla", "Toca para compartirla.")
            is HubEvent.ScreenCut -> alert("Se dejó de compartir tu pantalla", "Toca para volver a compartirla.")
            is HubEvent.RequestCapture -> alert("Tu técnico te pide una captura", "Abre la app para ver la indicación.")
            is HubEvent.Fatal -> finish()
            else -> {}
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(act: String, req: Int): PendingIntent = PendingIntent.getService(this, req,
        Intent(this, SupportService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(s: UiState): Notification {
        val text = when {
            s.status == "waiting" -> "En la fila, un técnico te atenderá pronto"
            !s.connected -> "Conectando con ${s.techName.ifEmpty { "tu técnico" }}…"
            s.sharing -> "${s.techName.ifEmpty { "Tu técnico" }} está viendo tu pantalla"
            else -> "En llamada con ${s.techName.ifEmpty { "tu técnico" }}"
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (s.company.isNotEmpty()) "Soporte · ${s.company}" else getString(R.string.app_name))
            .setContentText(text + if (!s.micOn) " · micrófono silenciado" else "")
            .setOngoing(true)
            .setContentIntent(openApp())
            .setCategory(Notification.CATEGORY_CALL)
        if (s.sharing) b.addAction(Notification.Action.Builder(null, getString(R.string.stop_share), action(ACTION_STOP_SHARE, 1)).build())
        b.addAction(Notification.Action.Builder(null, if (s.micOn) "Silenciar" else "Activar micrófono", action(ACTION_MIC, 2)).build())
        b.addAction(Notification.Action.Builder(null, "Terminar", action(ACTION_END, 3)).build())
        return b.build()
    }

    private fun alert(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(openApp()).build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    /** Altavoz y modo llamada para escuchar al técnico mientras se mira la pantalla. */
    @Suppress("DEPRECATION")
    private fun audioForCall(on: Boolean) {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (on) {
            prevAudioMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            } else am.isSpeakerphoneOn = true
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
            am.mode = prevAudioMode
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engine?.onRotate()
    }

    private fun finish() {
        val hadEngine = engine != null
        engine = null
        MarksOverlay.hide(this)
        if (hadEngine) audioForCall(false)
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID + 1)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Hub.remove(stateListener, eventListener, null)
        engine?.shutdown()
        engine = null
        MarksOverlay.hide(this)
        super.onDestroy()
    }
}
