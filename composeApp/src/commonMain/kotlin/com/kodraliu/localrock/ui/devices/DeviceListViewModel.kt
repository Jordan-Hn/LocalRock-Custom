package com.kodraliu.localrock.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kodraliu.localrock.shared.AppContainer
import com.kodraliu.localrock.shared.model.LastKnownStatus
import com.kodraliu.localrock.shared.model.Scene
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a home card shows besides the device itself: its last reported state and its routines. */
data class HomeCardInfo(
    val status: LastKnownStatus? = null,
    val routines: List<Scene> = emptyList(),
)

class DeviceListViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val devices = container.deviceRepository.devices
    val productsByDuid = container.deviceRepository.productsByDuid
    val awayFromHome = container.deviceRepository.offline
    val reachability = container.deviceRepository.reachability
    val unpaired = container.deviceRepository.unpaired

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _cards = MutableStateFlow<Map<String, HomeCardInfo>>(emptyMap())
    val cards: StateFlow<Map<String, HomeCardInfo>> = _cards.asStateFlow()

    /** A short confirmation or failure after a routine tile is tapped; cleared by the screen once shown. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null
            try {
                container.deviceRepository.refresh()
                container.deviceRepository.probeReachability()
            } catch (e: Throwable) {
                _error.value = e.message ?: "Failed to load devices"
            } finally {
                _refreshing.value = false
            }
            loadCards()
        }
    }

    /** Each card's status and routines load on their own, so one slow robot never holds up the list. */
    private fun loadCards() {
        for (device in devices.value) {
            val duid = device.duid
            viewModelScope.launch {
                val status = runCatching { container.deviceRepository.lastKnown(duid, includeMap = false)?.status }.getOrNull()
                _cards.update { it + (duid to (it[duid] ?: HomeCardInfo()).copy(status = status)) }
            }
            viewModelScope.launch {
                val routines = runCatching { container.deviceRepository.scenes(duid) }.getOrDefault(emptyList())
                _cards.update { it + (duid to (it[duid] ?: HomeCardInfo()).copy(routines = routines.filter { r -> r.enabled })) }
            }
        }
    }

    fun runRoutine(scene: Scene) {
        viewModelScope.launch {
            _message.value = runCatching { container.deviceRepository.runScene(scene.id) }
                .fold(
                    onSuccess = { if (it.accepted) "Starting “${scene.name}”" else "The robot didn't accept “${scene.name}”" },
                    onFailure = { "Couldn't start “${scene.name}”: ${it.message ?: "unknown error"}" },
                )
        }
    }

    fun messageShown() { _message.value = null }

    fun logout() = container.authRepository.logout()
}
