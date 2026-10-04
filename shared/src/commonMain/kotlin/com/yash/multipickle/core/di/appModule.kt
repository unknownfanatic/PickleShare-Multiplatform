package com.yash.multipickle.core.di

import com.yash.multipickle.core.permissionchecker.BlePermissionChecker
import com.yash.multipickle.core.permissionchecker.createBlePermissionChecker
import com.yash.multipickle.core.permissionchecker.usecases.CheckBlePermissionsUseCase
import com.yash.multipickle.core.storage.FileDestinationManager
import com.yash.multipickle.core.storage.createFileDestinationManager
import com.yash.multipickle.core.transfer.FileTransferClient
import com.yash.multipickle.core.transfer.FileTransferServer
import com.yash.multipickle.core.transfer.createFileTransferClient
import com.yash.multipickle.core.transfer.createFileTransferServer
import com.yash.multipickle.core.udp.UdpBroadcastManager
import com.yash.multipickle.core.udp.createUdpBroadcastManager
import com.yash.multipickle.ui.MainViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single<UdpBroadcastManager> { createUdpBroadcastManager() }
    single<BlePermissionChecker> { createBlePermissionChecker() }
    single<FileDestinationManager> { createFileDestinationManager() }
    single<FileTransferServer> { createFileTransferServer(get()) }
    single<FileTransferClient> { createFileTransferClient() }
    factory { CheckBlePermissionsUseCase(get()) }
    viewModel { MainViewModel(get(), get(), get()) }
}
