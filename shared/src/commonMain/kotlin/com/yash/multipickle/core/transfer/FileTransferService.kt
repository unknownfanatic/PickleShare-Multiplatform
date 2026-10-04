package com.yash.multipickle.core.transfer

import com.yash.multipickle.core.storage.FileDestinationManager
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.StateFlow

interface FileTransferServer {
    val incomingPromptFlow: StateFlow<IncomingTransferPrompt?>
    val progressFlow: StateFlow<Float>
    val fileProgressFlow: StateFlow<Map<String, Float>>
    val statusFlow: StateFlow<String?>
    fun start(port: Int = 53318)
    fun stop()
}

expect fun createFileTransferServer(destinationManager: FileDestinationManager): FileTransferServer

interface FileTransferClient {
    suspend fun sendFiles(
        targetIp: String,
        port: Int = 53318,
        senderInfo: DeviceInfo,
        files: List<PlatformFile>,
        useCompression: Boolean = true,
        onProgress: (Float) -> Unit,
        onFileProgress: (fileName: String, progress: Float) -> Unit = { _, _ -> },
        onStatusChange: (String) -> Unit
    ): Result<Unit>
}

expect fun createFileTransferClient(): FileTransferClient
