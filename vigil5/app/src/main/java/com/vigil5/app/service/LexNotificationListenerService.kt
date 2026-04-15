package com.vigil5.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.vigil5.app.VigilApp
import com.vigil5.app.core.AgentPersona
import com.vigil5.app.core.DraftReply
import com.vigil5.app.core.InboundMessage
import com.vigil5.app.core.LlmClient
import com.vigil5.app.core.VigilRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * LEX — The Secretary.
 *
 * Listens for incoming notifications from messaging apps, converts them into
 * InboundMessage records on the shared VigilContext, then asks the LLM to
 * draft a short reply. Drafts go into VigilRepository.pendingReplies where
 * the HUD/MainActivity picks them up. Nothing is auto-sent — the owner taps
 * to confirm.
 *
 * Safety notes:
 *  - We ignore our own notifications to avoid feedback loops.
 *  - We coalesce duplicate notifications (Android re-posts on update) by
 *    their (package, title, text) tuple for the current session.
 *  - We never read OTP / banking / 2FA notifications. The package allowlist
 *    below is explicit; everything else is skipped.
 */
class LexNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentKeys = linkedSetOf<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "LEX connected to notification stream")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "LEX disconnected from notification stream")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (pkg !in MESSAGING_ALLOWLIST) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getString(Notification.EXTRA_TITLE)
            ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getString(Notification.EXTRA_TEXT)
            ?: return

        if (title.isBlank() || text.isBlank()) return
        if (looksSensitive(text)) {
            Log.d(TAG, "LEX skipping sensitive notification from $pkg")
            return
        }

        val dedupKey = "$pkg|$title|$text"
        synchronized(recentKeys) {
            if (!recentKeys.add(dedupKey)) return
            // Trim the dedup set so it doesn't grow unbounded across a session.
            if (recentKeys.size > DEDUP_MAX) {
                val iterator = recentKeys.iterator()
                repeat(DEDUP_MAX / 4) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
            }
        }

        val message = InboundMessage(
            source = sourceLabel(pkg),
            sender = title.trim(),
            body = text.trim()
        )
        VigilRepository.pushInboundMessage(message)
        draftReply(message)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // LLM draft pipeline
    // -----------------------------------------------------------------

    private fun draftReply(message: InboundMessage) {
        val switchboard: LlmClient = VigilApp.get(this).switchboard ?: run {
            Log.w(TAG, "LEX has no switchboard — API key missing")
            return
        }
        scope.launch {
            val prompt = buildString {
                append("Source: ${message.source}\n")
                append("From: ${message.sender}\n")
                append("Message: ${message.body}\n\n")
                append("Draft one reply. If the message is a greeting, keep it warm and brief. ")
                append("If it is a question, answer plainly. If you do not have enough context ")
                append("to answer, draft a short clarifying question instead.")
            }
            val contextBlock = recentContextBlock()
            val reply = runCatching {
                switchboard.complete(
                    persona = AgentPersona.LEX,
                    userMessage = prompt,
                    contextBlock = contextBlock,
                    maxTokens = 256
                )
            }.onFailure { Log.w(TAG, "LEX draft failed: ${it.message}") }
                .getOrNull()?.trim().orEmpty()

            if (reply.isNotBlank()) {
                VigilRepository.queueDraftReply(
                    DraftReply(
                        messageId = message.id,
                        recipient = message.sender,
                        body = reply
                    )
                )
            }
        }
    }

    private fun recentContextBlock(): String {
        val ctx = VigilRepository.context.value
        val recent = ctx.recentMessages.take(5).joinToString("\n") {
            "- [${it.source}] ${it.sender}: ${it.body}"
        }
        return if (recent.isBlank()) "" else "Recent inbox:\n$recent"
    }

    private fun sourceLabel(pkg: String): String = when (pkg) {
        "com.google.android.apps.messaging", "com.android.mms" -> "sms"
        "com.whatsapp" -> "whatsapp"
        "org.thoughtcrime.securesms" -> "signal"
        "com.facebook.orca" -> "messenger"
        "org.telegram.messenger" -> "telegram"
        "com.discord" -> "discord"
        else -> pkg
    }

    /**
     * Heuristic to keep OTPs, banking alerts, and two-factor codes out of
     * the LLM. Not bulletproof, but catches the obvious cases. Apps on the
     * allowlist also tend not to send these in the first place.
     */
    private fun looksSensitive(text: String): Boolean {
        val lowered = text.lowercase()
        if ("verification code" in lowered) return true
        if ("one-time" in lowered || "one time" in lowered) return true
        if ("do not share" in lowered) return true
        if (Regex("""\b\d{6}\b""").containsMatchIn(text) &&
            ("code" in lowered || "otp" in lowered)
        ) return true
        return false
    }

    companion object {
        private const val TAG = "VigilLex"
        private const val DEDUP_MAX = 512

        private val MESSAGING_ALLOWLIST = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.whatsapp",
            "org.thoughtcrime.securesms",
            "com.facebook.orca",
            "org.telegram.messenger",
            "com.discord"
        )
    }
}
