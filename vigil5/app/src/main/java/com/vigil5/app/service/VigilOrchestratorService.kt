package com.vigil5.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vigil5.app.R
import com.vigil5.app.core.AgentId
import com.vigil5.app.core.VigilRepository
import com.vigil5.app.ui.MainActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * VIGIL-5 — VigilOrchestratorService
 *
 * Persistent foreground service that owns one supervised CoroutineScope per
 * agent. Runs as FOREGROUND_SERVICE_SPECIAL_USE (subtype
 * "personal_automation_orchestrator") and returns START_STICKY so the OS
 * restarts it if killed under memory pressure.
 *
 * The service itself is the "CORE" runtime process. Each agent — VOX, LEX,
 * SAGE, LISTINGS, CORE — runs inside its own isolated scope so a crash in one
 * cannot take the others down. All shared state flows through VigilRepository.
 */
class VigilOrchestratorService : LifecycleService() {

    private val agentScopes: MutableMap<AgentId, CoroutineScope> = mutableMapOf()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        spawnAgentScopes()
        observeSharedContext()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()
        VigilRepository.markOrchestratorRunning(true)
        handleCommand(intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        VigilRepository.markOrchestratorRunning(false)
        agentScopes.values.forEach { it.cancel() }
        agentScopes.clear()
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Foreground / Notification
    // ---------------------------------------------------------------

    private fun startInForeground() {
        val notification = buildStatusNotification(
            title = "VIGIL-5 active",
            text = "5 agents online"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VIGIL-5 Orchestrator",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Persistent status for VIGIL-5 agent orchestrator."
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildStatusNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vigil_status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateStatusNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildStatusNotification("VIGIL-5 active", text))
    }

    // ---------------------------------------------------------------
    // Agent scope lifecycle
    // ---------------------------------------------------------------

    private fun spawnAgentScopes() {
        AgentId.values().forEach { agent ->
            agentScopes[agent] = newAgentScope(agent)
            launchAgent(agent)
        }
    }

    private fun newAgentScope(agent: AgentId): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, throwable ->
            VigilRepository.recordAgentFault(agent, throwable)
        }
        return CoroutineScope(
            SupervisorJob() + Dispatchers.Default + handler
        )
    }

    /**
     * Each agent is bootstrapped here. Heavy lifting lives in the agent's own
     * module — this function just starts the long-running coroutine that
     * subscribes to VigilContext and kicks off the agent's work loop.
     */
    private fun launchAgent(agent: AgentId) {
        val scope = agentScopes[agent] ?: return
        scope.launch {
            VigilRepository.markAgentState(agent, VigilRepository.AgentState.STARTING)
            try {
                // Agent modules are wired in from their own files; this loop
                // keeps the scope alive and reports heartbeats to the repo so
                // the HUD can show liveness.
                VigilRepository.markAgentState(agent, VigilRepository.AgentState.ONLINE)
                while (true) {
                    VigilRepository.heartbeat(agent)
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            } finally {
                VigilRepository.markAgentState(agent, VigilRepository.AgentState.OFFLINE)
            }
        }
    }

    private fun restartAgent(agent: AgentId) {
        agentScopes[agent]?.cancel()
        agentScopes[agent] = newAgentScope(agent)
        launchAgent(agent)
    }

    // ---------------------------------------------------------------
    // Shared context observation
    // ---------------------------------------------------------------

    private fun observeSharedContext() {
        VigilRepository.context
            .onEach { ctx ->
                val online = ctx.agentStates.count {
                    it.value == VigilRepository.AgentState.ONLINE
                }
                updateStatusNotification("$online / ${AgentId.values().size} agents online")
            }
            .launchIn(lifecycleScope)

        VigilRepository.faults
            .onEach { fault ->
                // Auto-restart a faulted agent once; further faults are
                // surfaced to the HUD for the user to act on.
                if (fault.autoRestart) restartAgent(fault.agent)
            }
            .launchIn(lifecycleScope)
    }

    // ---------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------

    private fun handleCommand(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_RESTART_AGENT -> {
                val name = intent.getStringExtra(EXTRA_AGENT_ID) ?: return
                runCatching { AgentId.valueOf(name) }.getOrNull()?.let(::restartAgent)
            }
            ACTION_SHUTDOWN -> stopSelf()
        }
    }

    companion object {
        private const val CHANNEL_ID = "vigil5_orchestrator"
        private const val NOTIFICATION_ID = 0x71601
        private const val HEARTBEAT_INTERVAL_MS = 15_000L

        const val ACTION_RESTART_AGENT = "com.vigil5.app.action.RESTART_AGENT"
        const val ACTION_SHUTDOWN = "com.vigil5.app.action.SHUTDOWN"
        const val EXTRA_AGENT_ID = "com.vigil5.app.extra.AGENT_ID"
    }
}
