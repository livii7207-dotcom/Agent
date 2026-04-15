package com.vigil5.app.service

import android.telecom.Call
import android.telecom.InCallService

/**
 * VOX — InCallService stub.
 *
 * The final implementation will:
 *  1. Detect unknown incoming calls flagged by VoxCallScreeningService.
 *  2. Answer the call.
 *  3. Play a short pre-recorded / TTS greeting to the caller.
 *  4. Record the caller's audio, pipe to STT, then to ApiSwitchboard.stream(
 *     AgentPersona.VOX, ...) to produce a summary.
 *  5. Push a CallTranscript into VigilRepository and raise a notification so
 *     Joshua can pick up or dismiss.
 *
 * Explicitly NOT in scope: injecting the LLM's response audio back to the
 * remote caller. VOX screens and transcribes only.
 */
class VoxInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        // TODO: hook into call state, stage the screening flow.
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        // TODO: flush any in-progress transcript to VigilRepository.
    }
}
