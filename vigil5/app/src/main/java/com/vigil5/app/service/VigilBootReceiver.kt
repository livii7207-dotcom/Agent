package com.vigil5.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restarts the orchestrator after device reboot or app upgrade. The user
 * still has to grant REQUEST_IGNORE_BATTERY_OPTIMIZATIONS for this to stick
 * long term on most OEMs.
 */
class VigilBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val svc = Intent(context, VigilOrchestratorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, svc)
        } else {
            context.startService(svc)
        }
    }
}
