package dev.mtproxypilot

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mtproxypilot.data.ProxyCatalogRepository
import dev.mtproxypilot.data.ProxyPoolStore
import dev.mtproxypilot.data.TcpProxyChecker
import dev.mtproxypilot.domain.MtProtoLinkParser
import dev.mtproxypilot.domain.MtProtoProxy
import dev.mtproxypilot.domain.ProxyAvailability
import dev.mtproxypilot.domain.ProxyAvailabilityPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class AppStage { LOADING, READY, ERROR }

data class MainUiState(
    val stage: AppStage = AppStage.LOADING,
    val results: List<ProxyAvailability> = emptyList(),
    val checksInProgress: Int = 0,
    val totalCandidates: Int = 0,
    val catalogUpdatedAt: String? = null,
    val sharedLinksAdded: Int = 0,
    val sharedMessage: String? = null,
    val poolMessage: String? = null,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProxyCatalogRepository()
    private val checker = TcpProxyChecker()
    private val poolStore = ProxyPoolStore(application)
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val candidates = LinkedHashMap<String, MtProtoProxy>()
    private val results = ConcurrentHashMap<String, ProxyAvailability>()
    private var refreshGeneration = 0
    private var networkRefreshJob: Job? = null
    private var lastNetworkSignature: String? = currentNetworkSignature()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRefresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = scheduleNetworkRefresh()
    }

    init {
        registerNetworkObserver()
        refreshCatalog()
    }

    fun refreshCatalog() {
        val generation = ++refreshGeneration
        viewModelScope.launch {
            _uiState.update {
                it.copy(stage = AppStage.LOADING, checksInProgress = 0, error = null, sharedMessage = null, poolMessage = null)
            }
            runCatching { withContext(Dispatchers.IO) { repository.load() } }
                .onSuccess { catalog ->
                    if (generation != refreshGeneration) return@onSuccess
                    candidates.clear()
                    results.clear()
                    catalog.proxies.forEach { candidates[it.key] = it }
                    poolStore.mergeCandidates(catalog.proxies)
                    _uiState.update {
                        it.copy(
                            stage = AppStage.READY,
                            totalCandidates = candidates.size,
                            catalogUpdatedAt = catalog.updatedAt,
                            results = emptyList(),
                            poolMessage = "Свежий каталог получен · история проверок сохраняется на телефоне",
                        )
                    }
                    checkAll(candidates.values.toList(), generation)
                }
                .onFailure { failure ->
                    if (generation == refreshGeneration) {
                        val cached = poolStore.cachedCandidates()
                        if (cached.isNotEmpty()) {
                            candidates.clear()
                            results.clear()
                            cached.forEach { candidates[it.key] = it }
                            _uiState.update {
                                it.copy(
                                    stage = AppStage.READY,
                                    totalCandidates = cached.size,
                                    results = emptyList(),
                                    error = null,
                                    poolMessage = "Каталог недоступен · проверяем сохранённый пул с этого телефона",
                                )
                            }
                            checkAll(cached, generation)
                        } else {
                            _uiState.update {
                                it.copy(
                                    stage = AppStage.ERROR,
                                    error = failure.message ?: "Не удалось загрузить каталог",
                                )
                            }
                        }
                    }
                }
        }
    }

    fun addSharedText(text: String?) {
        val parsed = text?.let(MtProtoLinkParser::parseAll).orEmpty()
        if (parsed.isEmpty()) {
            _uiState.update {
                it.copy(sharedLinksAdded = 0, sharedMessage = "В сообщении нет ссылок MTProto Proxy")
            }
            return
        }
        val fresh = parsed.filter { proxy ->
            if (candidates.containsKey(proxy.key)) {
                false
            } else {
                candidates[proxy.key] = proxy
                true
            }
        }
        poolStore.mergeCandidates(parsed)
        _uiState.update {
            it.copy(
                stage = AppStage.READY,
                totalCandidates = candidates.size,
                sharedLinksAdded = parsed.size,
                sharedMessage = "Получено из Telegram: ${parsed.size}. Проверяем с этого устройства.",
                poolMessage = "Новые адреса сохранены в локальном пуле",
            )
        }
        if (fresh.isNotEmpty()) checkAll(fresh, refreshGeneration)
    }

    fun dismissSharedMessage() {
        _uiState.update { it.copy(sharedMessage = null) }
    }

    private fun recheckSavedPool() {
        val saved = poolStore.cachedCandidates()
        if (saved.isEmpty()) return
        val generation = ++refreshGeneration
        candidates.clear()
        results.clear()
        saved.forEach { candidates[it.key] = it }
        _uiState.update {
            it.copy(
                stage = AppStage.READY,
                results = emptyList(),
                checksInProgress = 0,
                totalCandidates = saved.size,
                error = null,
                poolMessage = "Сеть изменилась · перепроверяем сохранённые маршруты",
            )
        }
        checkAll(saved, generation)
    }

    private fun checkAll(proxies: List<MtProtoProxy>, generation: Int) {
        if (proxies.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(checksInProgress = it.checksInProgress + proxies.size) }
            val gate = Semaphore(4)
            val checked = proxies.mapIndexed { order, proxy ->
                async {
                    gate.withPermit {
                        val samples = mutableListOf<Long?>()
                        repeat(2) { attempt ->
                            samples += runCatching {
                                withContext(Dispatchers.IO) { checker.ping(proxy) }
                            }.getOrNull()
                            if (attempt == 0) delay(180)
                        }
                        order to ProxyAvailabilityPolicy.evaluate(proxy, samples)
                    }
                }
            }.awaitAll().sortedBy { it.first }
            if (generation != refreshGeneration) return@launch
            val histories = withContext(Dispatchers.IO) {
                poolStore.recordAll(checked.map { it.second })
            }
            checked.forEach { (_, current) ->
                if (generation != refreshGeneration) return@forEach
                val history = histories.getValue(current.proxy.key)
                val visible = poolStore.visibleResult(current, history)
                if (visible == null) results.remove(current.proxy.key) else results[current.proxy.key] = visible
                _uiState.update {
                    it.copy(
                        stage = AppStage.READY,
                        results = candidates.values.mapNotNull { proxy -> results[proxy.key] },
                        checksInProgress = (it.checksInProgress - 1).coerceAtLeast(0),
                    )
                }
            }
        }
    }

    private fun registerNetworkObserver() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback,
                )
            }
        }
    }

    private fun scheduleNetworkRefresh() {
        val signature = currentNetworkSignature() ?: return
        if (signature == lastNetworkSignature) return
        lastNetworkSignature = signature
        networkRefreshJob?.cancel()
        networkRefreshJob = viewModelScope.launch {
            delay(1_000)
            recheckSavedPool()
        }
    }

    private fun currentNetworkSignature(): String? = runCatching {
        val network = connectivityManager.activeNetwork ?: return@runCatching null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@runCatching null
        buildString {
            append(network.toString())
            append(':')
            append(
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
            )
        }
    }.getOrNull()

    override fun onCleared() {
        networkRefreshJob?.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        super.onCleared()
    }
}
