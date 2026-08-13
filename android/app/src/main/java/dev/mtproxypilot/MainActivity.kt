package dev.mtproxypilot

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mtproxypilot.domain.Availability
import dev.mtproxypilot.domain.ProxyAvailability

private val DeckBlack = Color(0xFF071313)
private val DeckPanel = Color(0xFF0D2020)
private val DeckCard = Color(0xFF122A28)
private val DeckLine = Color(0xFF35534F)
private val DeckText = Color(0xFFF0F7F4)
private val DeckMuted = Color(0xFFA9BFBA)
private val PilotGreen = Color(0xFF35D7A5)
private val PilotDeepGreen = Color(0xFF07805B)
private val PilotOrange = Color(0xFFE7794D)

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
                    onCheckUpdates = { openWeb("https://distantlivework-creator.github.io/proxy-pilot/#androidDownload") },
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

    private fun openWeb(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
internal fun PilotScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onDismissSharedMessage: () -> Unit,
    onOpenProxy: (ProxyAvailability) -> Unit,
    onCheckUpdates: () -> Unit,
) {
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    Surface(color = DeckBlack, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("mainScroll"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PilotHeader(
                    onGuide = { showGuide = true },
                    onRefresh = onRefresh,
                    onAbout = { showAbout = true },
                    onCheckUpdates = onCheckUpdates,
                )
            }
            item { VinylHero(state) }
            state.sharedMessage?.let { message ->
                item { SharedNotice(message, onDismissSharedMessage) }
            }
            when (state.stage) {
                AppStage.LOADING -> item { LoadingCard("Загружаем свежий каталог…") }
                AppStage.ERROR -> item { ErrorCard(state.error.orEmpty(), onRefresh) }
                AppStage.READY -> {
                    if (state.results.isNotEmpty()) {
                        item {
                            Text(
                                "ДОСТУПНЫЕ МАРШРУТЫ",
                                color = PilotGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.7.sp,
                            )
                        }
                        items(state.results, key = { it.proxy.key }) { result ->
                            ProxyRow(result, onOpenProxy)
                        }
                        item { ShareHint() }
                    } else if (state.checksInProgress > 0) {
                        item { LoadingCard("Проверяем адреса в вашей сети…") }
                    } else {
                        item { NoRoutesCard(onRefresh) }
                    }
                }
            }
        }
    }

    if (showGuide) GuideDialog(onDismiss = { showGuide = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun PilotHeader(
    onGuide: () -> Unit,
    onRefresh: () -> Unit,
    onAbout: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeckPanel, RoundedCornerShape(18.dp))
            .border(1.dp, DeckLine, RoundedCornerShape(18.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PilotMark()
        Spacer(Modifier.size(10.dp))
        Text(
            "PROXY PILOT",
            color = DeckText,
            fontWeight = FontWeight.Black,
            fontSize = 13.sp,
            letterSpacing = 1.1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("brand"),
        )
        FilledIconButton(
            onClick = onGuide,
            modifier = Modifier.size(48.dp).testTag("guide"),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = Color(0xFF294A73),
                contentColor = DeckText,
            ),
        ) { Icon(Icons.Rounded.HelpOutline, "Как это работает") }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp).testTag("more")) {
                Icon(Icons.Rounded.MoreHoriz, "Открыть меню", tint = DeckText)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Обновить каталог") },
                    leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                    onClick = { menuOpen = false; onRefresh() },
                )
                DropdownMenuItem(
                    text = { Text("Проверить обновление") },
                    leadingIcon = { Icon(Icons.Rounded.SystemUpdate, null) },
                    onClick = { menuOpen = false; onCheckUpdates() },
                )
                DropdownMenuItem(
                    text = { Text("Как это работает") },
                    leadingIcon = { Icon(Icons.Rounded.HelpOutline, null) },
                    onClick = { menuOpen = false; onGuide() },
                )
                DropdownMenuItem(
                    text = { Text("О приложении") },
                    leadingIcon = { Icon(Icons.Rounded.MoreHoriz, null) },
                    onClick = { menuOpen = false; onAbout() },
                )
            }
        }
    }
}

@Composable
private fun PilotMark() {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF061817))
            .border(1.dp, Color(0xFF1E3E39), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("PP", color = DeckText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(7.dp, 13.dp, 19.dp).forEach { bar ->
                    Box(Modifier.size(width = 3.dp, height = bar).background(PilotGreen, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

@Composable
private fun VinylHero(state: MainUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            when (state.stage) {
                AppStage.LOADING -> "ПОДГОТОВКА КАТАЛОГА"
                AppStage.ERROR -> "НУЖНО ОБНОВИТЬ"
                AppStage.READY -> "ПРОВЕРКА С ЭТОГО ТЕЛЕФОНА"
            },
            color = PilotGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.7.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                state.stage == AppStage.ERROR -> "Каталог пока недоступен"
                state.checksInProgress > 0 -> "Проверяем все живые прокси"
                state.stage == AppStage.READY -> "Рабочие маршруты вашей сети"
                else -> "Ищем свежие маршруты"
            },
            color = DeckText,
            fontSize = 28.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            "Без входа и привязки к Telegram. Телефон проверяет все адреса из общего каталога.",
            color = DeckMuted,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        DeckStatus(state)
        Spacer(Modifier.height(8.dp))
        Turntable(spinning = state.stage == AppStage.LOADING || state.checksInProgress > 0)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                state.checksInProgress > 0 -> "ПРОВЕРЯЕМ: ${state.totalCandidates - state.checksInProgress} ИЗ ${state.totalCandidates}"
                state.stage == AppStage.READY -> "ГОТОВО: ${state.results.size} ИЗ ${state.totalCandidates}"
                state.stage == AppStage.ERROR -> "НАЖМИТЕ ОБНОВИТЬ"
                else -> "ЗАГРУЖАЕМ"
            },
            color = PilotGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.4.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { index -> CueDot(index + 1, active = state.results.size > index) }
        }
    }
}

@Composable
private fun DeckStatus(state: MainUiState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(DeckCard, RoundedCornerShape(14.dp))
            .border(1.dp, DeckLine, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag("deckStatus"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.stage == AppStage.LOADING || state.checksInProgress > 0) {
            CircularProgressIndicator(Modifier.size(22.dp), color = PilotGreen, strokeWidth = 2.dp)
        } else {
            Box(Modifier.size(12.dp).background(if (state.stage == AppStage.ERROR) PilotOrange else PilotGreen, CircleShape))
        }
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    state.stage == AppStage.LOADING -> "Загружаем каталог"
                    state.stage == AppStage.ERROR -> "Не удалось обновить каталог"
                    state.checksInProgress > 0 -> "Проверяем с этого устройства"
                    else -> "Локальная проверка завершена"
                },
                color = DeckText,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
            Text(
                when {
                    state.stage == AppStage.ERROR -> "Откройте меню и нажмите «Обновить каталог»"
                    state.stage == AppStage.LOADING -> "Это обычно занимает несколько секунд"
                    else -> "Доступно ${state.results.size} из ${state.totalCandidates}" +
                        if (state.checksInProgress > 0) " · осталось ${state.checksInProgress}" else ""
                },
                color = DeckMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun Turntable(spinning: Boolean) {
    val transition = rememberInfiniteTransition(label = "vinyl")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (spinning) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "vinylRotation",
    )
    Box(
        Modifier
            .fillMaxWidth(.78f)
            .aspectRatio(1f)
            .semantics { contentDescription = if (spinning) "Пластинка вращается, прокси проверяются" else "Проигрыватель Proxy Pilot" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * .37f
            val center = Offset(size.width * .46f, size.height * .53f)
            drawCircle(Color(0xFF132A28), radius * 1.28f, center, style = Stroke(width = 2f))
            drawCircle(Color(0xFF21453E), radius * 1.13f, center, style = Stroke(width = 2f))
            drawCircle(Color(0xFF020606), radius, center)
            repeat(10) { groove ->
                drawCircle(
                    color = if (groove % 2 == 0) Color(0xFF1C2826) else Color(0xFF0A100F),
                    radius = radius * (0.96f - groove * .055f),
                    center = center,
                    style = Stroke(width = 4f),
                )
            }
            val armTop = Offset(size.width * .80f, size.height * .16f)
            val armEnd = Offset(size.width * .91f, size.height * .72f)
            drawCircle(Color(0xFFBFCBC6), size.minDimension * .075f, armTop)
            drawCircle(Color(0xFF566965), size.minDimension * .035f, armTop)
            drawLine(Color(0xFFD6DEDB), armTop, armEnd, size.minDimension * .024f, StrokeCap.Round)
            drawLine(Color(0xFF6A7975), armTop, armEnd, size.minDimension * .007f, StrokeCap.Round)
            drawLine(
                Color(0xFF2F3937),
                armEnd,
                Offset(size.width * .95f, size.height * .77f),
                size.minDimension * .065f,
                StrokeCap.Round,
            )
        }
        Box(
            Modifier
                .size(74.dp)
                .offset(x = (-9).dp, y = 8.dp)
                .rotate(rotation)
                .clip(CircleShape)
                .background(PilotGreen)
                .border(4.dp, DeckText, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("PP", color = Color.White, fontWeight = FontWeight.Black, fontSize = 25.sp)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(8.dp, 15.dp, 22.dp).forEach { bar ->
                        Box(Modifier.size(width = 4.dp, height = bar).background(Color.White, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun CueDot(number: Int, active: Boolean) {
    Box(
        Modifier
            .size(34.dp)
            .background(if (active) PilotDeepGreen else Color.Transparent, CircleShape)
            .border(1.dp, if (active) PilotGreen else Color(0xFF718A84), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(number.toString(), color = if (active) DeckText else DeckMuted, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ProxyRow(result: ProxyAvailability, onOpenProxy: (ProxyAvailability) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DeckCard, RoundedCornerShape(16.dp))
            .border(1.dp, DeckLine, RoundedCornerShape(16.dp))
            .padding(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).background(if (result.availability == Availability.AVAILABLE) PilotGreen else PilotOrange, CircleShape))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${result.proxy.server}:${result.proxy.port}",
                    color = DeckText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (result.availability == Availability.AVAILABLE) "маршрут ответил дважды" else "маршрут ответил один раз",
                    color = DeckMuted,
                    fontSize = 12.sp,
                )
            }
            Text(result.medianLatencyMs?.let { "$it мс" } ?: "—", color = PilotGreen, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onOpenProxy(result) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("openProxy"),
            colors = ButtonDefaults.buttonColors(containerColor = PilotDeepGreen),
        ) {
            Icon(Icons.Rounded.AddLink, null, Modifier.size(19.dp))
            Spacer(Modifier.size(8.dp))
            Text("Добавить в Telegram", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SharedNotice(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF163A33), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = DeckText, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).testTag("sharedNotice"))
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Закрыть сообщение", tint = DeckText) }
    }
}

@Composable
private fun ShareHint() {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Нашли свежий прокси в канале?", color = DeckText, fontWeight = FontWeight.Bold)
        Text(
            "В Telegram нажмите «Поделиться» → Proxy Pilot. Мы проверим все ссылки из сообщения.",
            color = DeckMuted,
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
        title = { Text("Как пользоваться", color = DeckText, fontWeight = FontWeight.Black) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                item { GuideStep("1", "Откройте приложение", "Вход, регистрация и ваш Telegram-аккаунт не нужны.") }
                item { GuideStep("2", "Дождитесь проверки", "Proxy Pilot возьмёт свежий каталог и проверит все маршруты из вашей текущей сети.") }
                item { GuideStep("3", "Выберите любой зелёный", "Не обязательно брать самый быстрый: условия связи постоянно меняются.") }
                item { GuideStep("4", "Добавьте в Telegram", "Нажмите кнопку и подтвердите подключение уже в официальном Telegram.") }
                item {
                    Text(
                        "Новая ссылка из канала: в Telegram нажмите «Поделиться» → Proxy Pilot. Приложение проверит её отдельно.",
                        color = PilotGreen,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                item {
                    Text(
                        "Важно: локальный статус показывает доступность маршрута с телефона. Proxy Pilot не читает переписку и не управляет вашим Telegram.",
                        color = DeckMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("guideDone"),
                colors = ButtonDefaults.buttonColors(containerColor = PilotDeepGreen),
            ) { Text("Понятно") }
        },
        containerColor = DeckPanel,
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Proxy Pilot Android Beta", color = DeckText, fontWeight = FontWeight.Black) },
        text = {
            Text(
                "Приложение без регистрации загружает общий каталог и проверяет доступность маршрутов с этого телефона. Окончательное подключение подтверждает официальный Telegram.",
                color = DeckMuted,
                lineHeight = 21.sp,
            )
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = PilotDeepGreen)) { Text("Готово") }
        },
        containerColor = DeckPanel,
    )
}

@Composable
private fun GuideStep(number: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).background(PilotDeepGreen, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, color = DeckText, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DeckText, fontWeight = FontWeight.Bold)
            Text(body, color = DeckMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun LoadingCard(text: String) {
    Column(
        Modifier.fillMaxWidth().background(DeckCard, RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = PilotGreen)
        Spacer(Modifier.height(14.dp))
        Text(text, color = DeckMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ErrorCard(message: String, onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(DeckCard, RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Каталог пока недоступен", color = DeckText, fontWeight = FontWeight.Black, fontSize = 21.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, color = DeckMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = PilotDeepGreen)) { Text("Повторить") }
    }
}

@Composable
private fun NoRoutesCard(onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(DeckCard, RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("В этой сети ответы не получены", color = DeckText, fontWeight = FontWeight.Black, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Это не значит, что прокси умерли. Смените Wi‑Fi на мобильную сеть, измените местоположение или повторите позже.",
            color = DeckMuted,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = PilotDeepGreen)) { Text("Проверить снова") }
    }
}
