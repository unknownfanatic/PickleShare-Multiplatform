package com.yash.multipickle.core.permissionchecker

interface BlePermissionChecker {
    fun hasRequiredPermissions(): Boolean
}

expect fun createBlePermissionChecker(): BlePermissionChecker
