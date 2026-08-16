package com.hermes.mobile.ui.ops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.data.repo.OpsRepository
import com.hermes.mobile.data.repo.asObject
import com.hermes.mobile.data.repo.str
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class OpsViewModel @Inject constructor(
    private val opsRepository: OpsRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow<JsonObject?>(null)
    val stats: StateFlow<JsonObject?> = _stats.asStateFlow()

    private val _models = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // label to model-id
    val models: StateFlow<List<Pair<String, String>>> = _models.asStateFlow()

    private val _currentModel = MutableStateFlow<String?>(null)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    private val _skills = MutableStateFlow<List<String>>(emptyList())
    val skills: StateFlow<List<String>> = _skills.asStateFlow()

    private val _cron = MutableStateFlow<List<String>>(emptyList())
    val cron: StateFlow<List<String>> = _cron.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    fun refreshAll() {
        viewModelScope.launch {
            runCatching { opsRepository.systemStats() }
                .onSuccess { _stats.value = it }
                .onFailure { _userMessage.emit("stats: ${it.message}") }
            runCatching { opsRepository.modelOptions() }
                .onSuccess { el ->
                    val obj = el.asObject()
                    _currentModel.value = obj?.str("current") ?: obj?.str("model")
                    val opts = obj?.get("options") ?: obj?.get("models")
                    _models.value = (opts as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.asObject()?.str("id") ?: it.asObject()?.str("model") }
                        ?.map { it to it }
                        ?: emptyList()
                }
                .onFailure { _userMessage.emit("models: ${it.message}") }
            runCatching { opsRepository.skills() }
                .onSuccess { el ->
                    val arr = el.asObject()?.get("skills") ?: el
                    _skills.value = (arr as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.asObject()?.str("name") }
                        ?: emptyList()
                }
                .onFailure { _userMessage.emit("skills: ${it.message}") }
            runCatching { opsRepository.cronJobs() }
                .onSuccess { el ->
                    val arr = el.asObject()?.get("jobs") ?: el
                    _cron.value = (arr as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { o ->
                            o.asObject()?.let {
                                "${it.str("name") ?: it.str("id") ?: "?"} · ${it.str("schedule") ?: ""}"
                            }
                        } ?: emptyList()
                }
                .onFailure { _userMessage.emit("cron: ${it.message}") }
        }
    }

    fun switchModel(modelId: String) {
        viewModelScope.launch {
            runCatching { opsRepository.setModel(modelId) }
                .onSuccess {
                    _currentModel.value = modelId
                    _userMessage.emit("Model → $modelId")
                }
                .onFailure { _userMessage.emit("model switch failed: ${it.message}") }
        }
    }

    fun gateway(action: String) {
        viewModelScope.launch {
            runCatching { opsRepository.gatewayAction(action) }
                .onSuccess { _userMessage.emit("gateway $action sent") }
                .onFailure { _userMessage.emit("gateway $action failed: ${it.message}") }
        }
    }
}
