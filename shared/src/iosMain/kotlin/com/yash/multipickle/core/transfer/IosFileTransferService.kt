package com.yash.multipickle.core.transfer

import com.yash.multipickle.core.storage.FileDestinationManager
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IosFileTransferServer : FileTransferServer {
    private val _incomingPrompt = MutableStateFlow<IncomingTransferPrompt?>(null)
    override val incomingPromptFlow: StateFlow<IncomingTransferPrompt?> = _incomingPrompt.asStateFlow()

    private val _progressFlow = MutableStateFlow(0f)
    override val progressFlow: StateFlow<Float> = _progressFlow.asStateFlow()

    private val _fileProgressFlow = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val fileProgressFlow: StateFlow<Map<String, Float>> = _fileProgressFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow<String?>(null)
    override val statusFlow: StateFlow<String?> = _statusFlow.asStateFlow()

    override fun start(port: Int) {}
    override fun stop() {}
}

class IosFileTransferClient : FileTransferClient {
    override suspend fun sendFiles(
        targetIp: String,
        port: Int,
        senderInfo: DeviceInfo,
        files: List<PlatformFile>,
        useCompression: Boolean,
        onProgress: (Float) -> Unit,
        onFileProgress: (fileName: String, progress: Float) -> Unit,
        onStatusChange: (String) -> Unit
    ): Result<Unit> = Result.failure(Exception("Not implemented on iOS"))
}

actual fun createFileTransferServer(destinationManager: FileDestinationManager): FileTransferServer {
    return IosFileTransferServer()
}

actual fun createFileTransferClient(): FileTransferClient {
    return IosFileTransferClient()
}
