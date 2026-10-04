package com.yash.multipickle.core.udp

import kotlinx.coroutines.flow.StateFlow

interface UdpBroadcastManager {
    val peerFlow: StateFlow<List<Peer>>
    val isScanningFlow: StateFlow<Boolean>
    fun start()
    fun stop()
    suspend fun startBroadcasting()
    fun stopBroadcasting()
    suspend fun startListening(
        groupAddress: String = "224.0.0.167",
        port: Int = 53317,
    )
    fun stopListening()
    fun setSendMode(value: Boolean)
    suspend fun scanSubnet(timeoutMs: Int = 300): List<Peer>
}

expect fun createUdpBroadcastManager(appId: String = "PickleShare"): UdpBroadcastManager
