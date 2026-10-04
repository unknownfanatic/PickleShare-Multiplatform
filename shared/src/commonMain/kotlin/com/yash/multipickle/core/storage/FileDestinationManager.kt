package com.yash.multipickle.core.storage

interface FileDestinationManager {
    suspend fun getDestinationFolder(): String?
}

expect fun createFileDestinationManager(): FileDestinationManager

expect fun getPlatformDeviceModel(): String
expect fun getPlatformDeviceType(): String
