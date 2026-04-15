package com.vigil5.app.core

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AgentPersona — system prompts sent to Anthropic on every request.
 *
 * Each agent has one persona. The prompts are intentionally terse: Joshua can
 * tune them in the app later, and shorter prompts are cheaper per request.
 */
enum class AgentPersona(val systemPrompt: String) {
    CORE(
        """
        You are CORE, the central orchestrator for VIGIL-5, a personal automation
        agent running on the owner's Android phone. You coordinate four other
        agents (VOX, LEX, SAGE, LISTINGS) and maintain shared context across them.
        Be concise, deterministic, and decisive. Never invent facts; if you do not
        know something, say so and ask CORE to route to SAGE.
        """.trimIndent()
    ),
    VOX(
        """
        You are VOX, a call-screening assistant. An unknown caller has reached
        the owner's phone. Your job is to: (1) politely greet the caller, (2)
        ask who they are and why they are calling, (3) capture any call-back
        information. You never pretend to be the owner. You never commit the
        owner to anything. Keep every utterance under 15 words. Speak plainly.
        """.trimIndent()
    ),
    LEX(
        """
        You are LEX, a personal communications secretary. You summarize
        incoming messages and draft short, friendly, context-appropriate
        replies that sound like the owner. Never send a reply yourself — the
        owner confirms every outgoing message. If a message looks urgent,
        mark it URGENT at the top of your summary. Keep drafts under 40 words
        unless the incoming message is long and clearly warrants more.
        """.trimIndent()
    ),
    SAGE(
        """
        You are SAGE, a research analyst. Given a question or topic, produce
        a tight, actionable summary with concrete facts and sources. Cite URLs
        inline when you have them. Refuse to speculate; if the evidence is
        thin, say the evidence is thin. Structure: (1) bottom line, (2) key
        facts as bullets, (3) sources.
        """.trimIndent()
    ),
    LISTINGS(
        """
        You are LISTINGS, a marketplace listing drafter. Given a photo
        description and raw notes about an item, produce a JSON object with
        keys: title (max 70 chars), description (2-4 sentences, buyer-
        friendly), price_usd (integer), category, condition. Respond with
        JSON only — no prose, no markdown fences.
        """.trimIndent()
    )
}

/**
 * Abstract LLM interface so the rest of the app doesn't hard-depend on
 * Anthropic. Swap ApiSwitchboard for a mock in tests, or for a different
 * provider later, without touching agent code.
 */
interface LlmClient {
    /** Blocking single-shot completion. */
    suspend fun complete(
        persona: AgentPersona,
        userMessage: String,
        contextBlock: String? = null,
        maxTokens: Int = 1024
    ): String

    /** Token stream — used by VOX so TTS can start speaking before the LLM
     *  finishes generating. Emits incremental text deltas. */
    fun stream(
        persona: AgentPersona,
        userMessage: String,
        contextBlock: String? = null,
        maxTokens: Int = 1024
    ): Flow<String>
}

/**
 * ApiSwitchboard — Ktor-based Anthropic Messages API client.
 *
 * Usage:
 *   val reply = switchboard.complete(AgentPersona.LEX, "Incoming: ...")
 *   switchboard.stream(AgentPersona.VOX, "...").collect { tts.speak(it) }
 */
class ApiSwitchboard(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: HttpClient = defaultHttpClient()
) : LlmClient {

    override suspend fun complete(
        persona: AgentPersona,
        userMessage: String,
        contextBlock: String?,
        maxTokens: Int
    ): String {
        val body = MessagesRequest(
            model = model,
            maxTokens = maxTokens,
            system = persona.systemPrompt,
            messages = listOf(
                Message(role = "user", content = composeUserContent(contextBlock, userMessage))
            ),
            stream = false
        )
        val response: MessagesResponse = http.post(ENDPOINT) {
            authHeaders()
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
        return response.content
            .filter { it.type == "text" }
            .joinToString(separator = "") { it.text.orEmpty() }
            .trim()
    }

    override fun stream(
        persona: AgentPersona,
        userMessage: String,
        contextBlock: String?,
        maxTokens: Int
    ): Flow<String> = flow {
        val collector = this
        val body = MessagesRequest(
            model = model,
            maxTokens = maxTokens,
            system = persona.systemPrompt,
            messages = listOf(
                Message(role = "user", content = composeUserContent(contextBlock, userMessage))
            ),
            stream = true
        )
        http.preparePost(ENDPOINT) {
            authHeaders()
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody(body)
        }.execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val event = runCatching {
                    strictJson.decodeFromString(StreamEvent.serializer(), payload)
                }.getOrNull() ?: continue
                if (event.type == "content_block_delta") {
                    event.delta?.text?.let { collector.emit(it) }
                }
                if (event.type == "message_stop") return@execute
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        header("x-api-key", apiKey)
        header("anthropic-version", API_VERSION)
    }

    private fun composeUserContent(contextBlock: String?, userMessage: String): String =
        if (contextBlock.isNullOrBlank()) userMessage
        else "<vigil_context>\n$contextBlock\n</vigil_context>\n\n$userMessage"

    // -----------------------------------------------------------------
    // Wire format (Anthropic Messages API)
    // -----------------------------------------------------------------

    @Serializable
    private data class MessagesRequest(
        val model: String,
        @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<Message>,
        val stream: Boolean = false
    )

    @Serializable
    private data class Message(
        val role: String,
        val content: String
    )

    @Serializable
    private data class MessagesResponse(
        val id: String? = null,
        val type: String? = null,
        val role: String? = null,
        val model: String? = null,
        val content: List<ContentBlock> = emptyList(),
        @kotlinx.serialization.SerialName("stop_reason") val stopReason: String? = null
    )

    @Serializable
    private data class ContentBlock(
        val type: String,
        val text: String? = null
    )

    @Serializable
    private data class StreamEvent(
        val type: String,
        val index: Int? = null,
        val delta: StreamDelta? = null
    )

    @Serializable
    private data class StreamDelta(
        val type: String? = null,
        val text: String? = null
    )

    companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val DEFAULT_MODEL = "claude-sonnet-4-5"

        private val strictJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
    }
}
