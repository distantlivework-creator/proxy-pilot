package dev.mtproxypilot

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mtproxypilot.data.PilotApi
import dev.mtproxypilot.data.PilotPreferences
import dev.mtproxypilot.data.PilotStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val status: PilotStatus? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val serverUrl: String = "",
    val settingsOpen: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val api = PilotApi()
    private val preferences = PilotPreferences(application)
    private val _uiState = MutableStateFlow(
        MainUiState(status = preferences.cachedStatus(), serverUrl = preferences.serverUrl)
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh(runServerCheck: Boolean = false) {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    if (runServerCheck) api.requestSync(preferences.serverUrl)
                    api.loadStatus(preferences.serverUrl)
                }
            }.onSuccess { status ->
                preferences.cache(status)
                _uiState.update { it.copy(status = status, isRefreshing = false) }
            }.onFailure { failure ->
                _uiState.update {
                    it.copy(isRefreshing = false, error = failure.message ?: "Не удалось связаться с сервером")
                }
            }
        }
    }

    fun openSettings() = _uiState.update { it.copy(settingsOpen = true, error = null) }
    fun closeSettings() = _uiState.update { it.copy(settingsOpen = false) }

    fun saveServer(value: String) {
        runCatching { PilotApi.normalizeServerUrl(value) }
            .onSuccess { normalized ->
                preferences.serverUrl = normalized
                _uiState.update { it.copy(serverUrl = normalized, settingsOpen = false, error = null) }
                refresh()
            }
            .onFailure { failure -> _uiState.update { it.copy(error = failure.message) } }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}

