package dev.mtproxypilot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mtproxypilot.data.ProxyCatalogRepository
import dev.mtproxypilot.data.TcpProxyChecker
import dev.mtproxypilot.domain.Availability
import dev.mtproxypilot.domain.MtProtoLinkParser
import dev.mtproxypilot.domain.MtProtoProxy
import dev.mtproxypilot.domain.ProxyAvailability
import dev.mtproxypilot.domain.ProxyAvailabilityPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
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
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProxyCatalogRepository()
    private val checker = TcpProxyChecker()
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val candidates = LinkedHashMap<String, MtProtoProxy>()
    private val results = ConcurrentHashMap<String, ProxyAvailability>()
    private var refreshGeneration = 0

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
        val generation = ++refreshGeneration
        viewModelScope.launch {
            _uiState.update {
                it.copy(stage = AppStage.LOADING, checksInProgress = 0, error = null, sharedMessage = null)
            }
            runCatching { withContext(Dispatchers.IO) { repository.load() } }
                .onSuccess { catalog ->
                    if (generation != refreshGeneration) return@onSuccess
                    candidates.clear()
                    results.clear()
                    catalog.proxies.forEach { candidates[it.key] = it }
                    _uiState.update {
                        it.copy(
                            stage = AppStage.READY,
                            totalCandidates = candidates.size,
                            catalogUpdatedAt = catalog.updatedAt,
                            results = emptyList(),
                        )
                    }
                    checkAll(candidates.values.toList(), generation)
                }
                .onFailure { failure ->
                    if (generation == refreshGeneration) {
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

    fun addSharedText(text: String?) {
        val parsed = text?.let(MtProtoLinkParser::parseAll).orEmpty()
        if (parsed.isEmpty()) {
            _uiState.update {
                it.copy(sharedLinksAdded = 0, sharedMessage = "В сообщении нет ссылок MTProto Proxy")
            }
            return
        }
        val fresh = parsed.filter { candidates.putIfAbsent(it.key, it) == null }
        _uiState.update {
            it.copy(
                stage = AppStage.READY,
                totalCandidates = candidates.size,
                sharedLinksAdded = parsed.size,
                sharedMessage = "Получено из Telegram: ${parsed.size}. Проверяем с этого устройства.",
            )
        }
        if (fresh.isNotEmpty()) checkAll(fresh, refreshGeneration)
    }

    fun dismissSharedMessage() {
        _uiState.update { it.copy(sharedMessage = null) }
    }

    private fun checkAll(proxies: List<MtProtoProxy>, generation: Int) {
        if (proxies.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(checksInProgress = it.checksInProgress + proxies.size) }
            val gate = Semaphore(4)
            proxies.mapIndexed { order, proxy ->
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
            }.awaitAll().sortedBy { it.first }.forEach { (_, result) ->
                if (generation != refreshGeneration) return@forEach
                if (result.availability != Availability.UNAVAILABLE) results[result.proxy.key] = result
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
}
