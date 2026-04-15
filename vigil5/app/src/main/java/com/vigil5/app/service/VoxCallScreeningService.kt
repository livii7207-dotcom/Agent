package com.vigil5.app.service

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * VOX — CallScreeningService stub.
 *
 * Ultimate behavior (not wired yet):
 *  - Allow known contacts through untouched.
 *  - For unknown / spam-scored numbers, hand off to VoxInCallService which
 *    answers, plays a TTS greeting, records + transcribes the caller, and
 *    notifies the owner with a summary.
 *
 * Scope reminder: VOX is screen + transcribe + notify only. It does NOT
 * inject synthesized audio back to the remote party.
 */
class VoxCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        // Pass through for now. Real policy lands with the VOX implementation.
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )
    }
}
