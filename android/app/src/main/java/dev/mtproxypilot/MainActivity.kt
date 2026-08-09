package dev.mtproxypilot

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF17324D)
private val Blue = Color(0xFF1B76E5)
private val Green = Color(0xFF15B987)
private val Canvas = Color(0xFFF8FAFC)
private val Muted = Color(0xFF718096)
private val Line = Color(0xFFE4EAF0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val model: MainViewModel = viewModel()
                val state by model.uiState.collectAsStateWithLifecycle()
                PilotScreen(
                    state = state,
                    onRefresh = { model.refresh(true) },
                    onConnect = { state.status?.bestProxyUrl?.let(::openTelegram) },
                    onOpenSettings = model::openSettings,
                    onCloseSettings = model::closeSettings,
                    onSaveSettings = model::saveServer,
                )
            }
        }
    }

    private fun openTelegram(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.replace("tg://proxy", "https://t.me/proxy"))))
        }
    }
}

@Composable
private fun PilotScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onSaveSettings: (String) -> Unit,
) {
    val ready = state.status?.bestProxyUrl != null
    Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("PROXY PILOT", color = Ink, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Settings, "Настройки сервера", tint = Ink)
                }
            }

            Spacer(Modifier.weight(0.65f))
            Text(
                if (ready) "Прокси готов" else "Ищем рабочий прокси",
                color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    state.isRefreshing -> "Проверяем доступность…"
                    ready -> "Нажмите, чтобы открыть в Telegram"
                    else -> "Запустите проверку или проверьте сервер"
                },
                color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(42.dp))

            Box(
                modifier = Modifier
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(if (ready) Green else Color.White)
                    .border(2.dp, if (ready) Green else Line, CircleShape)
                    .clickable(enabled = ready, role = Role.Button, onClick = onConnect)
                    .semantics { contentDescription = if (ready) "Подключить лучший прокси" else "Рабочий прокси не найден"; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(color = Blue, strokeWidth = 3.dp, modifier = Modifier.size(52.dp))
                } else {
                    Icon(Icons.Rounded.Bolt, null, tint = if (ready) Color.White else Muted, modifier = Modifier.size(62.dp))
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(if (ready) "ПОДКЛЮЧИТЬ" else "НЕ ГОТОВ", color = if (ready) Green else Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

            Spacer(Modifier.weight(1f))
            state.status?.bestHost?.let { host ->
                ProxyCard(host, state.status.latencyMs, state.status.aliveCount)
                Spacer(Modifier.height(16.dp))
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp))
            }
            Button(
                onClick = onRefresh,
                enabled = !state.isRefreshing,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                Spacer(Modifier.size(10.dp))
                Text("Проверить сейчас", fontSize = 16.sp)
            }
        }
    }
    if (state.settingsOpen) ServerDialog(state.serverUrl, onCloseSettings, onSaveSettings)
}

@Composable
private fun ProxyCard(host: String, latency: Int?, aliveCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(Green, CircleShape))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(host, color = Ink, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("$aliveCount рабочих в резерве", color = Muted, fontSize = 13.sp)
        }
        Text(latency?.let { "$it мс" } ?: "—", color = Green, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ServerDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сервер проверки") },
        text = {
            Column {
                Text("Адрес backend-сервиса Proxy Pilot.", color = Muted)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value, { value = it }, label = { Text("URL") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(value) }) { Text("Сохранить") } },
        dismissButton = { Button(onClick = onDismiss, colors = ButtonDefaults.textButtonColors()) { Text("Отмена", color = Ink) } },
    )
}

