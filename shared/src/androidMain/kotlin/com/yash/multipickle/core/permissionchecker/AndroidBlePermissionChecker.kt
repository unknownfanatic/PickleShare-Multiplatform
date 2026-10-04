package com.yash.multipickle.core.permissionchecker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.yash.multipickle.core.PickleContext

class AndroidBlePermissionChecker(
    private val context: Context
) : BlePermissionChecker {

    override fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            true
        }
    }
}

actual fun createBlePermissionChecker(): BlePermissionChecker {
    val context = PickleContext.context ?: error("Android context not initialized in PickleContext")
    return AndroidBlePermissionChecker(context)
}
