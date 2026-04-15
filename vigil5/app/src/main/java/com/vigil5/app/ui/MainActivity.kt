package com.vigil5.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vigil5.app.VigilApp
import com.vigil5.app.core.AgentId
import com.vigil5.app.core.DraftReply
import com.vigil5.app.core.VigilRepository
import com.vigil5.app.service.VigilOrchestratorService

/**
 * Minimal onboarding + status surface. The real product UI is the floating
 * HUD; this activity exists for one-shot actions the HUD can't trigger: key
 * entry, permission grants, and start/stop of the orchestrator.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = vigilDarkColors()) {
                Dashboard(
                    onStart = ::startOrchestrator,
                    onStop = ::stopOrchestrator
                )
            }
        }
    }

    private fun startOrchestrator() {
        val intent = Intent(this, VigilOrchestratorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOrchestrator() {
        val intent = Intent(this, VigilOrchestratorService::class.java).apply {
            action = VigilOrchestratorService.ACTION_SHUTDOWN
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dashboard(onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val app = remember { VigilApp.get(context) }
    val ctx by VigilRepository.context.collectAsState()

    var apiKeyInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("VIGIL-5", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    running = ctx.orchestratorRunning,
                    onStart = onStart,
                    onStop = onStop
                )
            }
            item {
                AgentGrid(states = ctx.agentStates)
            }
            if (!app.hasApiKey()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Anthropic API key required", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("sk-ant-...") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { app.saveApiKey(apiKeyInput); apiKeyInput = "" },
                                enabled = apiKeyInput.isNotBlank()
                            ) { Text("Save key") }
                        }
                    }
                }
            }
            item {
                Text(
                    "Pending drafts (LEX)",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(ctx.pendingReplies, key = { it.id }) { draft -> DraftRow(draft) }
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (running) "Orchestrator: ONLINE" else "Orchestrator: OFFLINE",
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !running) { Text("Start agents") }
                Button(onClick = onStop, enabled = running) { Text("Stop agents") }
            }
        }
    }
}

@Composable
private fun AgentGrid(states: Map<AgentId, VigilRepository.AgentState>) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            AgentId.values().forEach { agent ->
                val state = states[agent] ?: VigilRepository.AgentState.OFFLINE
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(agent.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text(
                        state.name,
                        color = when (state) {
                            VigilRepository.AgentState.ONLINE -> MaterialTheme.colorScheme.primary
                            VigilRepository.AgentState.FAULTED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftRow(draft: DraftReply) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("To: ${draft.recipient}", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(draft.body)
        }
    }
}

private fun vigilDarkColors() = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF0A0F1C),
    background = Color(0xFF0A0F1C),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE5E7EB),
    onBackground = Color(0xFFE5E7EB),
    error = Color(0xFFF87171)
)
