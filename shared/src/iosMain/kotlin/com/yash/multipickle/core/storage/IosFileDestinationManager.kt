package com.yash.multipickle.core.storage

class IosFileDestinationManager : FileDestinationManager {
    override suspend fun getDestinationFolder(): String? = null
}

actual fun createFileDestinationManager(): FileDestinationManager = IosFileDestinationManager()

actual fun getPlatformDeviceModel(): String = "iPhone"
actual fun getPlatformDeviceType(): String = "mobile"
