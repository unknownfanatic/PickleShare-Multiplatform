package com.yash.multipickle.core.permissionchecker

class JvmBlePermissionChecker : BlePermissionChecker {
    override fun hasRequiredPermissions(): Boolean = true
}

actual fun createBlePermissionChecker(): BlePermissionChecker = JvmBlePermissionChecker()
