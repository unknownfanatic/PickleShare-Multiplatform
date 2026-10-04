package com.yash.multipickle.core.udp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IosUdpBroadcastManager(
    private val appId: String = "PickleShare",
) : UdpBroadcastManager {

    private val _peersFlow = MutableStateFlow<List<Peer>>(emptyList())
    override val peerFlow: StateFlow<List<Peer>> = _peersFlow.asStateFlow()

    private val _isScanningFlow = MutableStateFlow(false)
    override val isScanningFlow: StateFlow<Boolean> = _isScanningFlow.asStateFlow()

    override fun start() {}
    override fun stop() {}
    override suspend fun startBroadcasting() {}
    override fun stopBroadcasting() {}
    override suspend fun startListening(groupAddress: String, port: Int) {}
    override fun stopListening() {}
    override fun setSendMode(value: Boolean) {}
    override suspend fun scanSubnet(timeoutMs: Int): List<Peer> = emptyList()
}

actual fun createUdpBroadcastManager(appId: String): UdpBroadcastManager {
    return IosUdpBroadcastManager(appId)
}
