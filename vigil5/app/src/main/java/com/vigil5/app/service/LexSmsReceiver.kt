package com.vigil5.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.vigil5.app.VigilApp
import com.vigil5.app.core.AgentPersona
import com.vigil5.app.core.DraftReply
import com.vigil5.app.core.InboundMessage
import com.vigil5.app.core.VigilRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives SMS broadcasts and feeds them into LEX's draft pipeline. This is
 * a complement to LexNotificationListenerService: notification-listener can
 * miss things when the user has notifications muted for a thread, whereas
 * the platform SMS broadcast always fires.
 *
 * A session-scope coroutine scope is kept alive inside the object because
 * BroadcastReceiver instances are short-lived. goAsync() extends the window
 * enough for the LLM call.
 */
class LexSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Concatenate multipart SMS from the same sender.
        val sender = messages.first().displayOriginatingAddress ?: "unknown"
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return

        val message = InboundMessage(source = "sms", sender = sender, body = body)
        VigilRepository.pushInboundMessage(message)

        val app = VigilApp.get(context)
        val switchboard = app.switchboard ?: run {
            Log.w(TAG, "LexSmsReceiver: no switchboard, skipping draft")
            return
        }

        // Async work must outlive onReceive; goAsync gives us ~10s of wall time.
        val pending = goAsync()
        scope.launch {
            try {
                val reply = runCatching {
                    switchboard.complete(
                        persona = AgentPersona.LEX,
                        userMessage = "Incoming SMS from $sender: \"$body\"\n\n" +
                            "Draft one short reply. Do not send it.",
                        maxTokens = 200
                    )
                }.getOrNull()?.trim().orEmpty()

                if (reply.isNotBlank()) {
                    VigilRepository.queueDraftReply(
                        DraftReply(
                            messageId = message.id,
                            recipient = sender,
                            body = reply
                        )
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "VigilLexSms"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
