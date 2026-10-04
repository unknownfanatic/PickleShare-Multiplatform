package com.yash.multipickle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yash.multipickle.core.storage.getPlatformDeviceModel
import com.yash.multipickle.core.storage.getPlatformDeviceType
import com.yash.multipickle.core.transfer.DeviceInfo
import com.yash.multipickle.core.transfer.FileTransferClient
import com.yash.multipickle.core.transfer.FileTransferServer
import com.yash.multipickle.core.transfer.IncomingTransferPrompt
import com.yash.multipickle.core.udp.Peer
import com.yash.multipickle.core.udp.UdpBroadcastManager
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private inline fun <R> syncLock(lock: Any, block: () -> R): R = block()

data class UiState(
    val isSenderMode: Boolean = true,
    val currentProgress: Float = 0f,
    val fileProgresses: Map<String, Float> = emptyMap(),
    val statusMessage: String? = null,
    val selectedFiles: List<PlatformFile>? = emptyList(),
    val peers: List<Peer> = emptyList(),
    val selectedPeer: Peer? = null,
    val hasPermissions: Boolean = false,
    val needsToRequestPermissions: Boolean = false,
    val thisDeviceName: String = "",
    val incomingPrompt: IncomingTransferPrompt? = null,
    val useCompression: Boolean = true,
    val pendingModeSwitch: Boolean? = null,
    val isSubnetScanning: Boolean = false
)

sealed interface AppEvent {
    data class OnSwitchMode(val value: Boolean) : AppEvent
    data class OnFilesSelected(val files: List<PlatformFile>?) : AppEvent
    data class OnSelectPeer(val peer: Peer) : AppEvent
    data class OnSendToPeer(val peer: Peer) : AppEvent
    data class OnToggleCompression(val enabled: Boolean) : AppEvent
    data object OnSendClick : AppEvent
    data object OnCancelClick : AppEvent
    data object OnSelectFiles : AppEvent
    data class OnPermissionResult(val result: Map<String, Boolean>) : AppEvent
    data object OnAcceptTransfer : AppEvent
    data object OnDenyTransfer : AppEvent
    data object OnConfirmModeSwitch : AppEvent
    data object OnDismissModeSwitch : AppEvent
    data object OnScanSubnet : AppEvent
}

sealed interface AppEffect {
    data object OnSelectFiles : AppEffect
}

class MainViewModel(
    private val udpBroadcastManager: UdpBroadcastManager,
    private val fileTransferServer: FileTransferServer,
    private val fileTransferClient: FileTransferClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    private val _sideEffect = Channel<AppEffect>(capacity = Channel.CONFLATED)
    val sideEffect = _sideEffect.receiveAsFlow()
    val uiState = _uiState.asStateFlow()

    private var activeTransferPeer: Peer? = null
    private var lastTargetPeer: Peer? = null
    private var isTransferring: Boolean = false
    private val activeJobs = mutableListOf<Job>()
    private val sentFileNames = mutableSetOf<String>()

    init {
        udpBroadcastManager.setSendMode(_uiState.value.isSenderMode)
        viewModelScope.launch(Dispatchers.IO) {
            val deviceModel = getPlatformDeviceModel()
            _uiState.update { it.copy(thisDeviceName = deviceModel) }
        }
        viewModelScope.launch {
            udpBroadcastManager.peerFlow.collect { activePeers ->
                _uiState.update { state ->
                    val filteredPeers = if (state.isSenderMode) {
                        activePeers.filter { !it.wannaSend }
                    } else {
                        activePeers
                    }
                    val validSelected = if (state.selectedPeer != null && filteredPeers.none { it.peerIp == state.selectedPeer.peerIp }) {
                        null
                    } else {
                        state.selectedPeer
                    }
                    state.copy(peers = filteredPeers, selectedPeer = validSelected)
                }
            }
        }
        viewModelScope.launch {
            fileTransferServer.incomingPromptFlow.collect { prompt ->
                _uiState.update { it.copy(incomingPrompt = prompt) }
            }
        }
        viewModelScope.launch {
            fileTransferServer.progressFlow.collect { progress ->
                if (!_uiState.value.isSenderMode) {
                    _uiState.update { it.copy(currentProgress = progress) }
                }
            }
        }
        viewModelScope.launch {
            fileTransferServer.fileProgressFlow.collect { fileProgressMap ->
                if (!_uiState.value.isSenderMode) {
                    _uiState.update { it.copy(fileProgresses = fileProgressMap) }
                }
            }
        }
        viewModelScope.launch {
            fileTransferServer.statusFlow.collect { status ->
                if (!_uiState.value.isSenderMode && status != null) {
                    _uiState.update { it.copy(statusMessage = status) }
                }
            }
        }
        viewModelScope.launch {
            udpBroadcastManager.isScanningFlow.collect { isScanning ->
                _uiState.update { it.copy(isSubnetScanning = isScanning) }
            }
        }
    }

    fun handleEvent(event: AppEvent) {
        when (event) {
            is AppEvent.OnSwitchMode -> onSwitchMode(event.value)
            is AppEvent.OnFilesSelected -> onSelectFile(event.files)
            is AppEvent.OnSelectPeer -> onSelectPeer(event.peer)
            is AppEvent.OnSendToPeer -> sendToPeer(event.peer)
            is AppEvent.OnSendClick -> onSendClick()
            is AppEvent.OnCancelClick -> onCancelClick()
            is AppEvent.OnPermissionResult -> {}
            is AppEvent.OnToggleCompression -> onToggleCompression(event.enabled)
            AppEvent.OnSelectFiles -> sendEffect(AppEffect.OnSelectFiles)
            AppEvent.OnAcceptTransfer -> onAcceptTransfer()
            AppEvent.OnDenyTransfer -> onDenyTransfer()
            AppEvent.OnConfirmModeSwitch -> onConfirmModeSwitch()
            AppEvent.OnDismissModeSwitch -> onDismissModeSwitch()
            AppEvent.OnScanSubnet -> onScanSubnet()
        }
    }

    fun onScanSubnet() {
        viewModelScope.launch {
            udpBroadcastManager.scanSubnet(timeoutMs = 300)
        }
    }

    fun onToggleCompression(value: Boolean) {
        _uiState.update { it.copy(useCompression = value) }
    }

    fun onUpdatePermissions(value: Boolean) {
        _uiState.update { it.copy(hasPermissions = value) }
    }

    private fun isTransferActive(): Boolean {
        val state = _uiState.value
        return if (state.isSenderMode) {
            isTransferring || (state.currentProgress > 0f && state.currentProgress < 1f)
        } else {
            (state.currentProgress > 0f && state.currentProgress < 1f) || state.incomingPrompt != null
        }
    }

    fun onSwitchMode(value: Boolean) {
        if (value == _uiState.value.isSenderMode) return
        if (isTransferActive()) {
            _uiState.update { it.copy(pendingModeSwitch = value) }
        } else {
            performModeSwitch(value)
        }
    }

    private fun onConfirmModeSwitch() {
        val target = _uiState.value.pendingModeSwitch ?: return
        performModeSwitch(target)
    }

    private fun onDismissModeSwitch() {
        _uiState.update { it.copy(pendingModeSwitch = null) }
    }

    private fun performModeSwitch(value: Boolean) {
        syncLock(activeJobs) {
            activeJobs.forEach { it.cancel() }
            activeJobs.clear()
        }
        isTransferring = false
        activeTransferPeer = null
        lastTargetPeer = null
        sentFileNames.clear()
        _uiState.value.incomingPrompt?.onDeny?.invoke()

        val allPeers = udpBroadcastManager.peerFlow.value
        val filteredPeers = if (value) {
            allPeers.filter { !it.wannaSend }
        } else {
            allPeers
        }
        _uiState.update {
            val validSelected = if (value && it.selectedPeer != null && filteredPeers.none { p -> p.peerIp == it.selectedPeer.peerIp }) {
                null
            } else {
                it.selectedPeer
            }
            it.copy(
                isSenderMode = value,
                peers = filteredPeers,
                selectedPeer = validSelected,
                statusMessage = null,
                currentProgress = 0f,
                fileProgresses = emptyMap(),
                pendingModeSwitch = null
            )
        }
        udpBroadcastManager.setSendMode(value)
        if (!value) {
            fileTransferServer.start()
        } else {
            fileTransferServer.stop()
        }
    }

    private fun launchSend(peer: Peer, filesToSend: List<PlatformFile>) {
        if (filesToSend.isEmpty()) return
        filesToSend.forEach { sentFileNames.add(it.name) }
        activeTransferPeer = peer
        lastTargetPeer = peer
        isTransferring = true

        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val senderInfo = DeviceInfo(
                    alias = _uiState.value.thisDeviceName.ifEmpty { getPlatformDeviceModel() },
                    deviceModel = getPlatformDeviceModel(),
                    deviceType = getPlatformDeviceType()
                )
                _uiState.update { it.copy(statusMessage = "Sending transfer request...") }
                val result = fileTransferClient.sendFiles(
                    targetIp = peer.peerIp,
                    senderInfo = senderInfo,
                    files = filesToSend,
                    useCompression = _uiState.value.useCompression,
                    onProgress = { /* Individual file progress updates overall */ },
                    onFileProgress = { fileName, progress ->
                        _uiState.update { state ->
                            val updatedMap = state.fileProgresses + (fileName to progress)
                            val avgProgress = if (updatedMap.isNotEmpty()) {
                                updatedMap.values.sum() / updatedMap.size
                            } else progress
                            state.copy(
                                fileProgresses = updatedMap,
                                currentProgress = avgProgress
                            )
                        }
                    },
                    onStatusChange = { status ->
                        _uiState.update { it.copy(statusMessage = status) }
                    }
                )
                if (!result.isSuccess) {
                    _uiState.update { it.copy(statusMessage = "Transfer failed: ${result.exceptionOrNull()?.message}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Transfer error: ${e.message}") }
            } finally {
                syncLock(activeJobs) {
                    activeJobs.remove(coroutineContext[Job])
                    if (activeJobs.isEmpty()) {
                        isTransferring = false
                        if (sentFileNames.size >= (_uiState.value.selectedFiles?.size ?: 0)) {
                            activeTransferPeer = null
                        }
                    }
                }
            }
        }
        syncLock(activeJobs) {
            activeJobs.add(job)
        }
    }

    fun onSelectFile(files: List<PlatformFile>?) {
        if (files.isNullOrEmpty()) return
        _uiState.update { currentState ->
            val currentFiles = currentState.selectedFiles ?: emptyList()
            val newProgresses = currentState.fileProgresses.toMutableMap()
            files.forEach { file ->
                if (!newProgresses.containsKey(file.name)) {
                    newProgresses[file.name] = 0f
                }
            }
            currentState.copy(
                selectedFiles = currentFiles + files,
                fileProgresses = newProgresses
            )
        }

        // When the sender adds another file mid transfer, prompt the receiver instantly
        val targetPeer = activeTransferPeer ?: lastTargetPeer ?: _uiState.value.selectedPeer
        if (targetPeer != null && (isTransferring || lastTargetPeer != null)) {
            val newFilesToSend = files.filter { it.name !in sentFileNames }
            if (newFilesToSend.isNotEmpty()) {
                launchSend(targetPeer, newFilesToSend)
            }
        }
    }

    private fun onSelectPeer(peer: Peer) {
        _uiState.update { it.copy(selectedPeer = peer) }
    }

    private fun sendToPeer(peer: Peer) {
        val files = _uiState.value.selectedFiles
        if (files.isNullOrEmpty()) {
            _uiState.update { it.copy(statusMessage = "Please select files to send first") }
            return
        }

        val unsentFiles = files.filter { it.name !in sentFileNames }
        if (unsentFiles.isNotEmpty()) {
            launchSend(peer, unsentFiles)
        } else {
            sentFileNames.clear()
            launchSend(peer, files)
        }
    }

    fun onSendClick() {
        val peer = _uiState.value.selectedPeer ?: _uiState.value.peers.firstOrNull()
        if (peer != null) {
            sendToPeer(peer)
        } else {
            _uiState.update { it.copy(statusMessage = "No peer selected or available") }
        }
    }

    fun onCancelClick() {
        syncLock(activeJobs) {
            activeJobs.forEach { it.cancel() }
            activeJobs.clear()
        }
        isTransferring = false
        activeTransferPeer = null
        lastTargetPeer = null
        sentFileNames.clear()
        _uiState.update {
            it.copy(
                selectedFiles = emptyList(),
                currentProgress = 0f,
                fileProgresses = emptyMap(),
                statusMessage = null
            )
        }
    }

    private fun onAcceptTransfer() {
        _uiState.value.incomingPrompt?.onAccept?.invoke()
    }

    private fun onDenyTransfer() {
        _uiState.value.incomingPrompt?.onDeny?.invoke()
    }

    fun sendEffect(effect: AppEffect) {
        _sideEffect.trySend(effect)
    }

    override fun onCleared() {
        super.onCleared()
        syncLock(activeJobs) {
            activeJobs.forEach { it.cancel() }
            activeJobs.clear()
        }
        fileTransferServer.stop()
    }
}
