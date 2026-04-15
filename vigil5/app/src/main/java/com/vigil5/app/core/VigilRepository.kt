package com.vigil5.app.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

/**
 * Enumerated agent identifiers used throughout VIGIL-5.
 *
 * CORE      — orchestrator / HUD / shared context broker
 * VOX       — call screener (InCallService, screen-and-transcribe only)
 * LEX       — comms intercept + reply drafting (human-in-the-loop send)
 * SAGE      — headless research (Ktor + Jsoup)
 * LISTINGS  — marketplace draft + share-intent helper
 *             (replaces NEXUS; no AccessibilityService automation)
 */
enum class AgentId { CORE, VOX, LEX, SAGE, LISTINGS }

/**
 * VigilContext — the shared, thread-safe blackboard all agents read and write.
 *
 * Immutable snapshot; state transitions go through VigilRepository.update,
 * which swaps the StateFlow value atomically.
 */
data class VigilContext(
    val orchestratorRunning: Boolean = false,
    val agentStates: Map<AgentId, VigilRepository.AgentState> =
        AgentId.values().associateWith { VigilRepository.AgentState.OFFLINE },
    val agentHeartbeats: Map<AgentId, Instant> = emptyMap(),

    // Rolling memory shared across agents. Kept small on purpose.
    val recentCallTranscripts: List<CallTranscript> = emptyList(),
    val recentMessages: List<InboundMessage> = emptyList(),
    val detectedEntities: Map<String, EntityRecord> = emptyMap(),
    val pendingListings: List<ListingDraft> = emptyList(),
    val pendingReplies: List<DraftReply> = emptyList(),
    val lastResearchResult: ResearchResult? = null
)

data class CallTranscript(
    val id: String = UUID.randomUUID().toString(),
    val callerNumber: String,
    val callerName: String? = null,
    val transcript: String,
    val summary: String? = null,
    val receivedAt: Instant = Instant.now()
)

data class InboundMessage(
    val id: String = UUID.randomUUID().toString(),
    val source: String,          // "sms", "whatsapp", "signal", ...
    val sender: String,
    val body: String,
    val receivedAt: Instant = Instant.now()
)

data class EntityRecord(
    val name: String,
    val kind: String,            // "person", "place", "product", ...
    val lastSeenAt: Instant = Instant.now(),
    val sourceAgent: AgentId
)

data class ListingDraft(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val priceCents: Long,
    val photos: List<String> = emptyList(),
    val createdAt: Instant = Instant.now()
)

data class DraftReply(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String,
    val recipient: String,
    val body: String,
    val createdAt: Instant = Instant.now()
)

data class ResearchResult(
    val query: String,
    val summary: String,
    val citations: List<String> = emptyList(),
    val createdAt: Instant = Instant.now()
)

/**
 * AgentFault — propagated via the faults SharedFlow. If [autoRestart] is true
 * the orchestrator will respawn the agent's coroutine scope once before
 * escalating to the HUD.
 */
data class AgentFault(
    val agent: AgentId,
    val throwable: Throwable,
    val at: Instant = Instant.now(),
    val autoRestart: Boolean = true
)

/**
 * VigilRepository — process-wide singleton. Owns the StateFlow<VigilContext>
 * and a SharedFlow<AgentFault> for fault propagation.
 *
 * All mutations go through update {} so readers always see a consistent
 * snapshot. Agents are expected to copy data into the context, not mutate it
 * in place.
 */
object VigilRepository {

    enum class AgentState { OFFLINE, STARTING, ONLINE, DEGRADED, FAULTED }

    private val _context = MutableStateFlow(VigilContext())
    val context: StateFlow<VigilContext> = _context.asStateFlow()

    private val _faults = MutableSharedFlow<AgentFault>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val faults: SharedFlow<AgentFault> = _faults.asSharedFlow()

    // ---------------------------------------------------------------
    // Orchestrator / lifecycle hooks
    // ---------------------------------------------------------------

    fun markOrchestratorRunning(running: Boolean) {
        _context.update { it.copy(orchestratorRunning = running) }
    }

    fun markAgentState(agent: AgentId, state: AgentState) {
        _context.update { ctx ->
            ctx.copy(agentStates = ctx.agentStates + (agent to state))
        }
    }

    fun heartbeat(agent: AgentId) {
        _context.update { ctx ->
            ctx.copy(agentHeartbeats = ctx.agentHeartbeats + (agent to Instant.now()))
        }
    }

    fun recordAgentFault(agent: AgentId, throwable: Throwable) {
        markAgentState(agent, AgentState.FAULTED)
        _faults.tryEmit(AgentFault(agent = agent, throwable = throwable))
    }

    // ---------------------------------------------------------------
    // VOX writes
    // ---------------------------------------------------------------

    fun pushCallTranscript(transcript: CallTranscript) {
        _context.update { ctx ->
            ctx.copy(
                recentCallTranscripts = (listOf(transcript) + ctx.recentCallTranscripts)
                    .take(MAX_CALL_HISTORY)
            )
        }
    }

    // ---------------------------------------------------------------
    // LEX writes
    // ---------------------------------------------------------------

    fun pushInboundMessage(message: InboundMessage) {
        _context.update { ctx ->
            ctx.copy(
                recentMessages = (listOf(message) + ctx.recentMessages)
                    .take(MAX_MESSAGE_HISTORY)
            )
        }
    }

    fun queueDraftReply(draft: DraftReply) {
        _context.update { ctx ->
            ctx.copy(pendingReplies = ctx.pendingReplies + draft)
        }
    }

    fun consumeDraftReply(draftId: String): DraftReply? {
        var removed: DraftReply? = null
        _context.update { ctx ->
            removed = ctx.pendingReplies.firstOrNull { it.id == draftId }
            ctx.copy(pendingReplies = ctx.pendingReplies.filterNot { it.id == draftId })
        }
        return removed
    }

    // ---------------------------------------------------------------
    // LISTINGS writes
    // ---------------------------------------------------------------

    fun queueListingDraft(draft: ListingDraft) {
        _context.update { ctx ->
            ctx.copy(pendingListings = ctx.pendingListings + draft)
        }
    }

    fun consumeListingDraft(draftId: String): ListingDraft? {
        var removed: ListingDraft? = null
        _context.update { ctx ->
            removed = ctx.pendingListings.firstOrNull { it.id == draftId }
            ctx.copy(pendingListings = ctx.pendingListings.filterNot { it.id == draftId })
        }
        return removed
    }

    // ---------------------------------------------------------------
    // SAGE writes
    // ---------------------------------------------------------------

    fun publishResearchResult(result: ResearchResult) {
        _context.update { it.copy(lastResearchResult = result) }
    }

    // ---------------------------------------------------------------
    // Cross-agent entity memory
    // ---------------------------------------------------------------

    fun rememberEntity(record: EntityRecord) {
        _context.update { ctx ->
            ctx.copy(detectedEntities = ctx.detectedEntities + (record.name to record))
        }
    }

    fun lookupEntity(name: String): EntityRecord? =
        _context.value.detectedEntities[name]

    // ---------------------------------------------------------------

    private const val MAX_CALL_HISTORY = 25
    private const val MAX_MESSAGE_HISTORY = 50
}
