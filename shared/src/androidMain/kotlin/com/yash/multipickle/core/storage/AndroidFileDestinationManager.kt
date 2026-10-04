package com.yash.multipickle.core.storage

import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.yash.multipickle.core.PickleContext
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context
import java.io.File

class AndroidFileDestinationManager : FileDestinationManager {
    private var cachedFolder: String? = null

    override suspend fun getDestinationFolder(): String? {
        val cached = cachedFolder
        if (cached != null) {
            val f = File(cached)
            if ((f.exists() || f.mkdirs()) && canWriteTo(f)) {
                return cached
            }
        }

        val context = PickleContext.context ?: try { FileKit.context } catch (_: Exception) { null }

        // 1. Try public Downloads/PickleShare
        try {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val publicDir = File(publicDownloads, "PickleShare")
            if ((publicDir.exists() || publicDir.mkdirs()) && canWriteTo(publicDir)) {
                val path = publicDir.absolutePath
                cachedFolder = path
                return path
            }
        } catch (_: Exception) {}

        // 2. Try app-specific external files dir: /storage/emulated/0/Android/data/.../files/Download/PickleShare
        if (context != null) {
            try {
                val extDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                if (extDownloads != null) {
                    val extDir = File(extDownloads, "PickleShare")
                    if ((extDir.exists() || extDir.mkdirs()) && canWriteTo(extDir)) {
                        val path = extDir.absolutePath
                        cachedFolder = path
                        return path
                    }
                }
            } catch (_: Exception) {}

            // 3. Fallback to app-specific internal files dir: /data/user/0/.../files/PickleShare
            try {
                val internalDir = File(context.filesDir, "PickleShare")
                if ((internalDir.exists() || internalDir.mkdirs()) && canWriteTo(internalDir)) {
                    val path = internalDir.absolutePath
                    cachedFolder = path
                    return path
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun canWriteTo(dir: File): Boolean {
        return try {
            val testFile = File(dir, ".test_${System.currentTimeMillis()}")
            if (testFile.createNewFile()) {
                testFile.delete()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}

actual fun createFileDestinationManager(): FileDestinationManager = AndroidFileDestinationManager()

actual fun getPlatformDeviceModel(): String {
    val context = PickleContext.context
    if (context != null) {
        try {
            val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            if (!name.isNullOrBlank()) return name
        } catch (_: Exception) {}
        try {
            val btName = Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            if (!btName.isNullOrBlank()) return btName
        } catch (_: Exception) {}
    }
    return Build.MODEL ?: "Android Device"
}
actual fun getPlatformDeviceType(): String = "mobile"
