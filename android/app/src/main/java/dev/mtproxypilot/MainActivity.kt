package dev.mtproxypilot

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mtproxypilot.domain.Availability
import dev.mtproxypilot.domain.ProxyAvailability

private val Ink = Color(0xFF122928)
private val Green = Color(0xFF0B8A68)
private val Canvas = Color(0xFFF3F6F4)
private val Muted = Color(0xFF607572)

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
                    onSubmitPhone = model::submitPhone,
                    onSubmitCode = model::submitCode,
                    onSubmitPassword = model::submitPassword,
                    onOpenProxy = { openTelegram(it.proxy.tgDeepLink()) },
                )
            }
        }
    }

    private fun openTelegram(url: String) {
        val telegram = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(telegram)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.replace("tg://proxy", "https://t.me/proxy"))))
        }
    }
}

@Composable
private fun PilotScreen(
    state: MainUiState,
    onSubmitPhone: (String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onSubmitPassword: (String) -> Unit,
    onOpenProxy: (ProxyAvailability) -> Unit,
) {
    Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text("PP  PROXY PILOT", color = Ink, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
            Spacer(Modifier.height(28.dp))
            when (state.stage) {
                AppStage.PHONE -> LoginForm(
                    title = "Войдите в Telegram",
                    hint = "Номер телефона в международном формате",
                    button = "Получить код",
                    onSubmit = onSubmitPhone,
                )
                AppStage.CODE -> LoginForm(
                    title = "Введите код Telegram",
                    hint = "Код из сообщения Telegram",
                    button = "Продолжить",
                    onSubmit = onSubmitCode,
                )
                AppStage.PASSWORD -> LoginForm(
                    title = "Облачный пароль",
                    hint = "Пароль двухэтапной проверки",
                    button = "Войти",
                    password = true,
                    onSubmit = onSubmitPassword,
                )
                AppStage.MONITORING -> MonitoringScreen(state, onOpenProxy)
                AppStage.CONFIGURATION_REQUIRED -> InfoScreen(
                    "Сборка ещё не настроена",
                    "Для входа нужны api_id и api_hash приложения Proxy Pilot. Они добавляются в защищённые секреты сборки и не вводятся посетителями сайта.",
                )
                AppStage.TDLIB_MISSING -> InfoScreen(
                    "Нужна сборка с TDLib",
                    "Эта оболочка проверяет интерфейс. Установите APK, собранный с официальной библиотекой Telegram TDLib.",
                )
                AppStage.UNSUPPORTED -> InfoScreen("Нужно подтверждение", state.error.orEmpty())
                AppStage.STARTING -> LoadingScreen("Запускаем локальную проверку Telegram…")
            }
            if (state.error != null && state.stage != AppStage.UNSUPPORTED) {
                Spacer(Modifier.height(16.dp))
                Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun LoginForm(
    title: String,
    hint: String,
    button: String,
    password: Boolean = false,
    onSubmit: (String) -> Unit,
) {
    var value by remember(title) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Вход нужен только на этом устройстве, чтобы читать новые сообщения ваших подписанных каналов. Сессия не отправляется на сервер Proxy Pilot.",
            color = Muted,
            lineHeight = 22.sp,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(hint) },
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { onSubmit(value) },
            enabled = value.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
        ) { Text(button) }
    }
}

@Composable
private fun MonitoringScreen(state: MainUiState, onOpenProxy: (ProxyAvailability) -> Unit) {
    Text("Слушаем новые сообщения", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        "${state.channelsCount} каналов · старые сообщения пропущены · сохраняем все ответившие прокси",
        color = Muted,
        lineHeight = 21.sp,
    )
    Spacer(Modifier.height(22.dp))
    if (state.checksInProgress > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Green)
            Spacer(Modifier.size(10.dp))
            Text("Проверяем с этого телефона: ${state.checksInProgress}", color = Ink)
        }
        Spacer(Modifier.height(18.dp))
    }
    if (state.results.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Text(
                "Ждём новый пост со ссылкой MTProto.\nКогда он появится, проверка начнётся автоматически.",
                color = Muted,
                textAlign = TextAlign.Center,
                lineHeight = 23.sp,
            )
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.results, key = { it.proxy.key }) { result ->
                ProxyRow(result, onOpenProxy)
            }
        }
    }
}

@Composable
private fun ProxyRow(result: ProxyAvailability, onOpenProxy: (ProxyAvailability) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(11.dp).background(
                if (result.availability == Availability.AVAILABLE) Green else Color(0xFFE29A35),
                CircleShape,
            )
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${result.proxy.server}:${result.proxy.port}", color = Ink, fontWeight = FontWeight.SemiBold)
            Text(
                if (result.availability == Availability.AVAILABLE) "стабильно отвечает" else "ответил один раз",
                color = Muted,
                fontSize = 12.sp,
            )
        }
        Text(result.medianLatencyMs?.let { "$it мс" } ?: "—", color = Green, fontWeight = FontWeight.Bold)
        androidx.compose.material3.IconButton(onClick = { onOpenProxy(result) }) {
            Icon(Icons.Rounded.OpenInNew, "Открыть прокси в Telegram", tint = Ink)
        }
    }
}

@Composable
private fun LoadingScreen(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Green)
            Spacer(Modifier.height(16.dp))
            Text(text, color = Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun InfoScreen(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, fontSize = 16.sp, lineHeight = 23.sp)
    }
}
