package com.ia.smallhome.network

import com.ia.smallhome.data.SettingsStore
import com.ia.smallhome.model.PanelRules
import com.ia.smallhome.security.SecureStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AiSessionManager(
    private val openRouterClient: OpenRouterClient,
    private val secureStore: SecureStore,
    private val settingsStore: SettingsStore,
) {
    private val mutex = Mutex()
    private val memory = AiSessionMemory()

    suspend fun start(sessionId: String) = mutex.withLock {
        memory.start(sessionId)
    }

    suspend fun end(sessionId: String) = mutex.withLock {
        memory.end(sessionId)
    }

    suspend fun ask(sessionId: String, prompt: String): ApiResult<String> = mutex.withLock {
        if (sessionId.isBlank() || prompt.isBlank()) return@withLock ApiResult.Failure("La pregunta de IA está vacía")
        val key = secureStore.get(SecureStore.SecretKeyName.OPENROUTER_KEY).orEmpty()
        val model = settingsStore.snapshot().openRouterModel
        val requestMessages = listOf(ChatMessage("system", SYSTEM_PROMPT)) + memory.request(sessionId, prompt)
        when (val result = openRouterClient.complete(key, model, requestMessages)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val concise = PanelRules.conciseAiText(result.value)
                memory.response(sessionId, concise)
                ApiResult.Success(concise)
            }
        }
    }

    suspend fun clear() = mutex.withLock { memory.clear() }

    private companion object {
        const val SYSTEM_PROMPT = "Respondes para una pantalla TFT de 172x320 píxeles. " +
            "Responde de forma clara y relativamente concisa, sin tablas ni formato innecesario, salvo que el usuario pida específicamente detalle."
    }
}

internal class AiSessionMemory(private val maxMessages: Int = 10) {
    private val sessions = mutableMapOf<String, MutableList<ChatMessage>>()

    fun start(sessionId: String) {
        if (sessionId.isNotBlank()) sessions[sessionId] = mutableListOf()
    }

    fun end(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun request(sessionId: String, prompt: String): List<ChatMessage> {
        val history = sessions.getOrPut(sessionId) { mutableListOf() }
        history += ChatMessage("user", prompt.take(320))
        trim(history)
        return history.toList()
    }

    fun response(sessionId: String, text: String) {
        val history = sessions.getOrPut(sessionId) { mutableListOf() }
        history += ChatMessage("assistant", text)
        trim(history)
    }

    fun messages(sessionId: String): List<ChatMessage> = sessions[sessionId]?.toList().orEmpty()
    fun contains(sessionId: String): Boolean = sessionId in sessions
    fun clear() = sessions.clear()

    private fun trim(history: MutableList<ChatMessage>) {
        while (history.size > maxMessages) history.removeAt(0)
    }
}
