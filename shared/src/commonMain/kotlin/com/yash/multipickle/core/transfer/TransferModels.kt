package com.yash.multipickle.core.transfer

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val alias: String,
    val deviceModel: String,
    val deviceType: String
)

@Serializable
data class FileMetadata(
    val fileName: String,
    val size: Long,
    val fileType: String
)

@Serializable
data class TransferRequest(
    val info: DeviceInfo,
    val files: Map<String, FileMetadata>
)

@Serializable
data class TransferResponse(
    val status: String,
    val message: String? = null
)

data class IncomingTransferPrompt(
    val request: TransferRequest,
    val onAccept: () -> Unit,
    val onDeny: () -> Unit
)
