package com.soporty.app

import android.os.Handler
import android.os.Looper

/** Estado visible de la sesión, compartido entre el servicio y la pantalla. */
data class UiState(
    val status: String = "",          // waiting | pending | active | ended | cancelled
    val techName: String = "",
    val techOnline: Boolean = false,
    val connected: Boolean = false,
    val sharing: Boolean = false,
    val micOn: Boolean = true,
    val queuePos: Int = 0,
    val startedAt: Long = 0,          // segundos (hora del servidor)
    val skew: Long = 0,               // diferencia servidor - teléfono, en segundos
    val company: String = "",
    val running: Boolean = false,
)

data class ChatLine(val sender: String, val author: String, val body: String)

/** Eventos puntuales para la pantalla. */
sealed class HubEvent {
    data class Toast(val text: String) : HubEvent()
    object RequestScreen : HubEvent()
    object RequestCapture : HubEvent()
    object ScreenCut : HubEvent()
    data class Image(val url: String) : HubEvent()
    data class Fatal(val text: String) : HubEvent()
}

/**
 * Bus en memoria: el servicio publica, la actividad escucha. Todo se entrega en el hilo principal.
 */
object Hub {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var state = UiState(); private set
    val chat = ArrayList<ChatLine>()
    private val stateListeners = LinkedHashSet<(UiState) -> Unit>()
    private val eventListeners = LinkedHashSet<(HubEvent) -> Unit>()
    private val chatListeners = LinkedHashSet<(ChatLine) -> Unit>()

    fun update(block: (UiState) -> UiState) {
        main.post {
            state = block(state)
            stateListeners.toList().forEach { it(state) }
        }
    }

    fun emit(e: HubEvent) = main.post { eventListeners.toList().forEach { it(e) } }

    fun addChat(line: ChatLine) = main.post {
        chat.add(line)
        if (chat.size > 200) chat.removeAt(0)
        chatListeners.toList().forEach { it(line) }
    }

    fun reset() = main.post { state = UiState(); chat.clear(); stateListeners.toList().forEach { it(state) } }

    fun onState(l: (UiState) -> Unit) { stateListeners.add(l); l(state) }
    fun onEvent(l: (HubEvent) -> Unit) { eventListeners.add(l) }
    fun onChat(l: (ChatLine) -> Unit) { chatListeners.add(l) }
    fun remove(state: ((UiState) -> Unit)?, event: ((HubEvent) -> Unit)?, chat: ((ChatLine) -> Unit)?) {
        state?.let { stateListeners.remove(it) }; event?.let { eventListeners.remove(it) }; chat?.let { chatListeners.remove(it) }
    }
}
