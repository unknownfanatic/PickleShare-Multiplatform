package com.yash.multipickle.core.udp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class Peer(
    val deviceName: String,
    var lastSeen: Long,
    val peerIp: String,
    val wannaSend: Boolean = false
)

@Serializable
data class PacketData(
    @SerialName("announce") val announce: Boolean,
    @SerialName("sender_name") val senderName: String,
    @SerialName("app_id") val appId: String,
    @SerialName("wanna_send") val wannaSend: Boolean = false
)
