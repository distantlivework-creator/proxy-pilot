package dev.mtproxypilot.tdlib

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class TdLibCredentials(val apiId: Int, val apiHash: String) {
    init {
        require(apiId > 0) { "Telegram api_id is required" }
        require(apiHash.isNotBlank()) { "Telegram api_hash is required" }
    }
}

sealed interface TelegramLoginState {
    data object Starting : TelegramLoginState
    data object PhoneNumber : TelegramLoginState
    data object Code : TelegramLoginState
    data object Password : TelegramLoginState
    data object Ready : TelegramLoginState
    data class OtherDevice(val link: String) : TelegramLoginState
    data class Unsupported(val reason: String) : TelegramLoginState
    data class Failed(val message: String) : TelegramLoginState
}

class TdLibAuthorizationController(
    private val transport: TdRawTransport,
    private val scope: CoroutineScope,
    private val credentials: TdLibCredentials,
    private val databaseDirectory: File,
    private val languageCode: String,
    private val deviceModel: String,
    private val systemVersion: String,
) {
    private val _state = MutableStateFlow<TelegramLoginState>(TelegramLoginState.Starting)
    val state: StateFlow<TelegramLoginState> = _state.asStateFlow()
    private var updatesJob: Job? = null

    fun start() {
        if (updatesJob != null) return
        updatesJob = scope.launch { transport.updates.collect(::handleUpdate) }
        scope.launch {
            runRequest(JSONObject().put("@type", "getAuthorizationState"))
                ?.let(::handleAuthorizationState)
        }
    }

    fun submitPhoneNumber(phone: String) = submit(
        JSONObject()
            .put("@type", "setAuthenticationPhoneNumber")
            .put("phone_number", phone.trim())
            .put("settings", JSONObject.NULL),
    )

    fun submitCode(code: String) = submit(
        JSONObject().put("@type", "checkAuthenticationCode").put("code", code.trim()),
    )

    fun submitPassword(password: String) = submit(
        JSONObject().put("@type", "checkAuthenticationPassword").put("password", password),
    )

    private fun submit(request: JSONObject) {
        scope.launch { runRequest(request) }
    }

    private suspend fun handleUpdate(raw: String) {
        val update = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (update.optString("@type") == "updateAuthorizationState") {
            update.optJSONObject("authorization_state")?.let { handleAuthorizationState(it) }
        }
    }

    private suspend fun handleAuthorizationState(state: JSONObject) {
        when (state.optString("@type")) {
            "authorizationStateWaitTdlibParameters" -> sendParameters()
            "authorizationStateWaitPhoneNumber" -> _state.value = TelegramLoginState.PhoneNumber
            "authorizationStateWaitCode" -> _state.value = TelegramLoginState.Code
            "authorizationStateWaitPassword" -> _state.value = TelegramLoginState.Password
            "authorizationStateWaitOtherDeviceConfirmation" -> {
                _state.value = TelegramLoginState.OtherDevice(state.optString("link"))
            }
            "authorizationStateReady" -> _state.value = TelegramLoginState.Ready
            "authorizationStateWaitRegistration" -> {
                _state.value = TelegramLoginState.Unsupported("Новый аккаунт нужно сначала создать в Telegram")
            }
            "authorizationStateWaitEmailAddress", "authorizationStateWaitEmailCode" -> {
                _state.value = TelegramLoginState.Unsupported("Для аккаунта требуется подтверждение email")
            }
            "authorizationStateClosing", "authorizationStateClosed", "authorizationStateLoggingOut" -> {
                _state.value = TelegramLoginState.Starting
            }
        }
    }

    private suspend fun sendParameters() {
        databaseDirectory.mkdirs()
        runRequest(
            JSONObject()
                .put("@type", "setTdlibParameters")
                .put("use_test_dc", false)
                .put("database_directory", databaseDirectory.absolutePath)
                .put("files_directory", File(databaseDirectory, "files").absolutePath)
                .put("database_encryption_key", "")
                .put("use_file_database", false)
                .put("use_chat_info_database", true)
                .put("use_message_database", true)
                .put("use_secret_chats", false)
                .put("api_id", credentials.apiId)
                .put("api_hash", credentials.apiHash)
                .put("system_language_code", languageCode.ifBlank { "ru" })
                .put("device_model", deviceModel.ifBlank { "Android" })
                .put("system_version", systemVersion)
                .put("application_version", "0.2.0"),
        )
    }

    private suspend fun runRequest(request: JSONObject): JSONObject? {
        val result = runCatching { JSONObject(transport.request(request.toString())) }
            .getOrElse {
                _state.value = TelegramLoginState.Failed(it.message ?: "Ошибка TDLib")
                return null
            }
        if (result.optString("@type") == "error") {
            _state.value = TelegramLoginState.Failed(result.optString("message", "Ошибка Telegram"))
            return null
        }
        return result
    }
}
