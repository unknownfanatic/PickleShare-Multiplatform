package com.yash.multipickle.core.storage

import java.io.File

class JvmFileDestinationManager : FileDestinationManager {
    override suspend fun getDestinationFolder(): String {
        val home = System.getProperty("user.home") ?: "."
        val folder = File(home, "Downloads/PickleShare")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder.absolutePath
    }
}

actual fun createFileDestinationManager(): FileDestinationManager = JvmFileDestinationManager()

actual fun getPlatformDeviceModel(): String {
    val os = System.getProperty("os.name") ?: "Desktop"
    return "$os PC"
}

actual fun getPlatformDeviceType(): String = "desktop"
