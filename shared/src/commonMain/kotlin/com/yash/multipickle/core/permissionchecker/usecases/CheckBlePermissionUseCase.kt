package com.yash.multipickle.core.permissionchecker.usecases

import com.yash.multipickle.core.permissionchecker.BlePermissionChecker

class CheckBlePermissionsUseCase(
    private val permissionChecker: BlePermissionChecker
) {
    operator fun invoke(): Boolean {
        return permissionChecker.hasRequiredPermissions()
    }
}
