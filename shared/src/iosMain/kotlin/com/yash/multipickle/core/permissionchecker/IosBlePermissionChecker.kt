package com.yash.multipickle.core.permissionchecker

class IosBlePermissionChecker : BlePermissionChecker {
    override fun hasRequiredPermissions(): Boolean = true
}

actual fun createBlePermissionChecker(): BlePermissionChecker = IosBlePermissionChecker()
