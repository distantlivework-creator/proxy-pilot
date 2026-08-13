package dev.mtproxypilot

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mtproxypilot.domain.Availability
import dev.mtproxypilot.domain.ProxyAvailability

private val Ink = Color(0xFF102725)
private val Green = Color(0xFF07805B)
private val Mint = Color(0xFF42D3A2)
private val Canvas = Color(0xFFF1F5F3)
private val Muted = Color(0xFF5C716E)
private val Amber = Color(0xFFE7794D)

class MainActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptSharedText(intent)
        setContent {
            MaterialTheme {
                val state by model.uiState.collectAsStateWithLifecycle()
                PilotScreen(
                    state = state,
                    onRefresh = model::refreshCatalog,
                    onDismissSharedMessage = model::dismissSharedMessage,
                    onOpenProxy = { openTelegram(it.proxy.telegramUrl()) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedText(intent)
    }

    private fun acceptSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            model.addSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
    }

    private fun openTelegram(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.replace("tg://proxy", "https://t.me/proxy"))))
        }
    }
}

@Composable
internal fun PilotScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onDismissSharedMessage: () -> Unit,
    onOpenProxy: (ProxyAvailability) -> Unit,
) {
    var showGuide by rememberSaveable { mutableStateOf(false) }
    Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            PilotHeader(onGuide = { showGuide = true }, onRefresh = onRefresh)
            Spacer(Modifier.height(18.dp))
            Text("PROXY RECORDS", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
            Text("Рабочие маршруты с вашего телефона", color = Ink, fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(7.dp))
            Text(
                "Без входа и привязки к Telegram. Берём все адреса, которые доступны именно в вашей текущей сети.",
                color = Muted,
                lineHeight = 21.sp,
            )
            state.sharedMessage?.let {
                Spacer(Modifier.height(12.dp))
                SharedNotice(it, onDismissSharedMessage)
            }
            Spacer(Modifier.height(15.dp))
            when (state.stage) {
                AppStage.LOADING -> LoadingContent("Загружаем свежий каталог…")
                AppStage.ERROR -> ErrorContent(state.error.orEmpty(), onRefresh)
                AppStage.READY -> ReadyContent(state, onOpenProxy)
            }
        }
    }
    if (showGuide) GuideDialog(onDismiss = { showGuide = false })
}

@Composable
private fun PilotHeader(onGuide: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = .9f), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFC9D5D1), RoundedCornerShape(16.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) { Text("PP", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp) }
        Spacer(Modifier.size(10.dp))
        Text("PROXY PILOT", color = Ink, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh")) {
            Icon(Icons.Rounded.Refresh, "Обновить каталог", tint = Ink)
        }
        FilledTonalButton(onClick = onGuide, modifier = Modifier.testTag("guide")) {
            Icon(Icons.Rounded.HelpOutline, null, Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Как это работает")
        }
    }
}

@Composable
private fun ReadyContent(state: MainUiState, onOpenProxy: (ProxyAvailability) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Ink, RoundedCornerShape(16.dp))
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.checksInProgress > 0) CircularProgressIndicator(Modifier.size(23.dp), color = Mint, strokeWidth = 2.dp)
            else Box(Modifier.size(12.dp).background(Mint, CircleShape))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.checksInProgress > 0) "Проверяем с этого устройства" else "Локальная проверка завершена",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Доступно ${state.results.size} из ${state.totalCandidates}" +
                        if (state.checksInProgress > 0) " · осталось ${state.checksInProgress}" else "",
                    color = Color(0xFFB8CAC5),
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(11.dp))
        Text(
            "Зелёный означает: телефон смог открыть маршрут. Окончательное подключение подтверждает официальный Telegram.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(11.dp))
        when {
            state.results.isNotEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("proxyList"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.results, key = { it.proxy.key }) { result -> ProxyRow(result, onOpenProxy) }
                item { ShareHint() }
            }
            state.checksInProgress > 0 -> LoadingContent("Проверяем адреса в вашей сети…")
            else -> NoRoutesContent()
        }
    }
}

@Composable
private fun ProxyRow(result: ProxyAvailability, onOpenProxy: (ProxyAvailability) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFD5DFDC), RoundedCornerShape(16.dp))
            .padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).background(if (result.availability == Availability.AVAILABLE) Green else Amber, CircleShape))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${result.proxy.server}:${result.proxy.port}", color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    if (result.availability == Availability.AVAILABLE) "маршрут ответил дважды" else "маршрут ответил один раз",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            Text(result.medianLatencyMs?.let { "$it мс" } ?: "—", color = Green, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onOpenProxy(result) },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("openProxy"),
            colors = ButtonDefaults.buttonColors(containerColor = Green),
        ) {
            Icon(Icons.Rounded.AddLink, null, Modifier.size(19.dp))
            Spacer(Modifier.size(8.dp))
            Text("Добавить в Telegram")
        }
    }
}

@Composable
private fun SharedNotice(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFE2F3ED), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Ink, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).testTag("sharedNotice"))
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Закрыть сообщение", tint = Ink) }
    }
}

@Composable
private fun ShareHint() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Нашли свежий прокси в канале?", color = Ink, fontWeight = FontWeight.Bold)
        Text(
            "В Telegram нажмите «Поделиться» → Proxy Pilot. Мы проверим все ссылки из сообщения.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("guideDialog"),
        onDismissRequest = onDismiss,
        title = { Text("Как пользоваться", color = Ink, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                GuideStep("1", "Откройте приложение", "Вход, регистрация и ваш Telegram-аккаунт не нужны.")
                GuideStep("2", "Дождитесь проверки", "Proxy Pilot возьмёт свежий каталог и проверит все маршруты из вашей текущей сети.")
                GuideStep("3", "Выберите любой зелёный", "Не обязательно брать самый быстрый: условия связи постоянно меняются.")
                GuideStep("4", "Добавьте в Telegram", "Нажмите кнопку и подтвердите подключение уже в официальном Telegram.")
                Text(
                    "Новая ссылка из канала: в Telegram нажмите «Поделиться» → Proxy Pilot. Приложение проверит её отдельно.",
                    color = Green,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "Важно: локальный статус показывает доступность маршрута с телефона. Proxy Pilot не читает переписку и не управляет вашим Telegram.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss, modifier = Modifier.testTag("guideDone")) { Text("Понятно") } },
        containerColor = Color.White,
    )
}

@Composable
private fun GuideStep(number: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(29.dp).background(Green, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, color = Color.White, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.size(10.dp))
        Column {
            Text(title, color = Ink, fontWeight = FontWeight.Bold)
            Text(body, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun LoadingContent(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Green)
            Spacer(Modifier.height(14.dp))
            Text(text, color = Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Каталог пока недоступен", color = Ink, fontWeight = FontWeight.Black, fontSize = 21.sp)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefresh) { Text("Повторить") }
    }
}

@Composable
private fun NoRoutesContent() {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("В этой сети ответы не получены", color = Ink, fontWeight = FontWeight.Black, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Это не значит, что прокси умерли. Смените Wi-Fi на мобильную сеть, измените местоположение или повторите позже.",
            color = Muted,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
    }
}
