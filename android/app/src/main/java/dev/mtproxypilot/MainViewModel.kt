package dev.mtproxypilot

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mtproxypilot.domain.Availability
import dev.mtproxypilot.domain.ChannelCursor
import dev.mtproxypilot.domain.MtProtoProxy
import dev.mtproxypilot.domain.NewProxyUpdateScanner
import dev.mtproxypilot.domain.ProxyAvailabilityPolicy
import dev.mtproxypilot.domain.ProxyAvailability
import dev.mtproxypilot.tdlib.ReflectiveTdJsonClient
import dev.mtproxypilot.tdlib.TdLibAuthorizationController
import dev.mtproxypilot.tdlib.TdLibCredentials
import dev.mtproxypilot.tdlib.TdLibNewMessageDecoder
import dev.mtproxypilot.tdlib.TdLibProxyChecker
import dev.mtproxypilot.tdlib.TdLibSubscribedChannelSource
import dev.mtproxypilot.tdlib.TelegramLoginState
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppStage {
    CONFIGURATION_REQUIRED,
    TDLIB_MISSING,
    STARTING,
    PHONE,
    CODE,
    PASSWORD,
    MONITORING,
    UNSUPPORTED,
}

data class MainUiState(
    val stage: AppStage = AppStage.STARTING,
    val channelsCount: Int = 0,
    val results: List<ProxyAvailability> = emptyList(),
    val checksInProgress: Int = 0,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var transport: ReflectiveTdJsonClient? = null
    private var controller: TdLibAuthorizationController? = null
    private var monitorJob: Job? = null
    private var scanner: NewProxyUpdateScanner? = null

    init {
        initialize()
    }

    fun submitPhone(value: String) = controller?.submitPhoneNumber(value)
    fun submitCode(value: String) = controller?.submitCode(value)
    fun submitPassword(value: String) = controller?.submitPassword(value)

    private fun initialize() {
        if (BuildConfig.TELEGRAM_API_ID <= 0 || BuildConfig.TELEGRAM_API_HASH.isBlank()) {
            _uiState.value = MainUiState(stage = AppStage.CONFIGURATION_REQUIRED)
            return
        }
        val client = runCatching { ReflectiveTdJsonClient() }.getOrElse {
            _uiState.value = MainUiState(
                stage = AppStage.TDLIB_MISSING,
                error = "В эту сборку не встроена официальная библиотека TDLib",
            )
            return
        }
        transport = client
        controller = TdLibAuthorizationController(
            transport = client,
            scope = viewModelScope,
            credentials = TdLibCredentials(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH),
            databaseDirectory = getApplication<Application>().getDir("tdlib", Application.MODE_PRIVATE),
            languageCode = Locale.getDefault().toLanguageTag(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            systemVersion = "Android ${Build.VERSION.RELEASE}",
        ).also { auth ->
            viewModelScope.launch {
                auth.state.collect(::renderLoginState)
            }
            auth.start()
        }
    }

    private suspend fun renderLoginState(login: TelegramLoginState) {
        when (login) {
            TelegramLoginState.Starting -> _uiState.update { it.copy(stage = AppStage.STARTING) }
            TelegramLoginState.PhoneNumber -> _uiState.update { it.copy(stage = AppStage.PHONE, error = null) }
            TelegramLoginState.Code -> _uiState.update { it.copy(stage = AppStage.CODE, error = null) }
            TelegramLoginState.Password -> _uiState.update { it.copy(stage = AppStage.PASSWORD, error = null) }
            TelegramLoginState.Ready -> startMonitoring()
            is TelegramLoginState.Failed -> _uiState.update { it.copy(error = login.message) }
            is TelegramLoginState.OtherDevice -> _uiState.update {
                it.copy(stage = AppStage.UNSUPPORTED, error = "Откройте Telegram и подтвердите вход: ${login.link}")
            }
            is TelegramLoginState.Unsupported -> _uiState.update {
                it.copy(stage = AppStage.UNSUPPORTED, error = login.reason)
            }
        }
    }

    private suspend fun startMonitoring() {
        if (monitorJob != null) return
        val client = transport ?: return
        val startedAt = System.currentTimeMillis() / 1_000
        val subscriptions = runCatching { TdLibSubscribedChannelSource(client).list() }
            .getOrElse { failure ->
                _uiState.update {
                    it.copy(stage = AppStage.MONITORING, error = failure.message ?: "Не удалось загрузить каналы")
                }
                emptyList()
            }
        scanner = NewProxyUpdateScanner(
            subscriptions.associate { channel ->
                channel.chatId to ChannelCursor(startedAt, channel.latestMessageId)
            }
        )
        _uiState.update {
            it.copy(stage = AppStage.MONITORING, channelsCount = subscriptions.size, error = null)
        }
        val checker = TdLibProxyChecker(client)
        monitorJob = viewModelScope.launch {
            client.updates.collect { raw ->
                val message = TdLibNewMessageDecoder.decode(raw) ?: return@collect
                scanner?.accept(message).orEmpty().forEach { proxy -> checkProxy(checker, proxy) }
            }
        }
    }

    private fun checkProxy(checker: TdLibProxyChecker, proxy: MtProtoProxy) {
        if (_uiState.value.results.any { it.proxy.key == proxy.key }) return
        viewModelScope.launch {
            _uiState.update { it.copy(checksInProgress = it.checksInProgress + 1) }
            val samples = buildList<Long?> {
                repeat(3) { index ->
                    add(runCatching { checker.ping(proxy) }.getOrNull())
                    if (index < 2) delay(350)
                }
            }
            val result = ProxyAvailabilityPolicy.evaluate(proxy, samples)
            _uiState.update { state ->
                val retained = (state.results + result)
                    .filter { it.availability != Availability.UNAVAILABLE }
                    .sortedWith(
                        compareByDescending<ProxyAvailability> { it.availability == Availability.AVAILABLE }
                            .thenBy { it.medianLatencyMs ?: Long.MAX_VALUE }
                    )
                state.copy(results = retained, checksInProgress = (state.checksInProgress - 1).coerceAtLeast(0))
            }
        }
    }

    override fun onCleared() {
        transport?.close()
        super.onCleared()
    }
}
