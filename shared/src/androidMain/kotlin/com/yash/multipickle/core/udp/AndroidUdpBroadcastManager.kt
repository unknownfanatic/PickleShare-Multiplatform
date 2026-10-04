package com.yash.multipickle.core.udp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.yash.multipickle.core.PickleContext
import com.yash.multipickle.core.storage.getPlatformDeviceModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class AndroidUdpBroadcastManager(
    private val context: Context? = PickleContext.context,
    private val appId: String = "PickleShare",
) : UdpBroadcastManager {

    private val multicastGroup = "224.0.0.167"
    private val port: Int = 53317
    private var isBroadcasting: Boolean = false
    private var shouldListen = false
    private var wannaSend = true
    private val networkJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val activePeerMap = ConcurrentHashMap<String, Peer>()
    private val _peersFlow = MutableStateFlow<List<Peer>>(emptyList())
    override val peerFlow: StateFlow<List<Peer>> = _peersFlow.asStateFlow()
    private val _isScanningFlow = MutableStateFlow(false)
    override val isScanningFlow: StateFlow<Boolean> = _isScanningFlow.asStateFlow()
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        start()
    }

    override fun start() {
        managerScope.launch {
            startBroadcasting()
        }
        managerScope.launch {
            startListening()
        }
        managerScope.launch {
            startPruningPeers()
        }
        managerScope.launch {
            startAutoSubnetScanner()
        }
    }

    override fun stop() {
        stopBroadcasting()
        stopListening()
        managerScope.cancel()
    }

    private fun processPacket(packet: DatagramPacket): PacketData? {
        val jsonString = String(packet.data, packet.offset, packet.length)
        val packetData = try {
            networkJson.decodeFromString<PacketData>(jsonString)
        } catch (e: SerializationException) {
            Log.e("SERIALIZATION_EXCEPTION", e.message.toString())
            null
        } catch (e: IllegalArgumentException) {
            Log.e("ILLEGAL_ARGUMENT_EXCEPTION", e.message.toString())
            null
        }
        if (packetData != null) {
            Log.i(
                "SUCCESS",
                "Sender: ${packetData.senderName}, App ID: ${packetData.appId}, Announce: ${packetData.announce}, Wanna Send: ${packetData.wannaSend}"
            )
        }
        return packetData
    }

    override suspend fun startBroadcasting() {
        isBroadcasting = true
        val interfaces = withContext(Dispatchers.IO) {
            NetworkInterface.getNetworkInterfaces()
        }
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?: emptyList()
        val deviceName = getPlatformDeviceModel()
        while (isBroadcasting) {
            val jsonMessage = Json.encodeToString(
                PacketData.serializer(),
                PacketData(
                    announce = true,
                    senderName = deviceName,
                    appId = appId,
                    wannaSend = wannaSend
                )
            )
            for (networkInterface in interfaces) {
                sendMulticast(
                    message = jsonMessage,
                    groupAddress = multicastGroup,
                    port = port,
                    networkInterface,
                    true
                )
            }
            delay(1000.milliseconds)
        }
    }

    override fun stopBroadcasting() {
        isBroadcasting = false
    }

    private fun sendDirectReply(targetIp: String, deviceName: String) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            val replyData = PacketData(
                announce = false,
                senderName = deviceName,
                appId = appId,
                wannaSend = wannaSend
            )
            val jsonMessage = Json.encodeToString(PacketData.serializer(), replyData).toByteArray(Charsets.UTF_8)
            val targetAddress = InetAddress.getByName(targetIp)
            val packet = DatagramPacket(jsonMessage, jsonMessage.size, targetAddress, port)
            socket.send(packet)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket?.close()
        }
    }

    private suspend fun sendMulticast(
        message: String,
        groupAddress: String,
        port: Int,
        networkInterface: NetworkInterface,
        broadcast: Boolean
    ) = withContext(Dispatchers.IO) {
        val sendData = message.toByteArray()

        try {
            MulticastSocket().use { socket ->
                socket.timeToLive = 1
                socket.networkInterface = networkInterface
                val group = InetAddress.getByName(groupAddress)
                val packet = DatagramPacket(sendData, sendData.size, group, port)
                socket.send(packet)
            }
        } catch (e: Exception) {
            Log.e("sendMulticast", "Failed sending on ${networkInterface.name}: ${e.message}")
        }

        if (broadcast) {
            for (interfaceAddress in networkInterface.interfaceAddresses) {
                val broadcastAddress = interfaceAddress.broadcast
                if (broadcastAddress != null) {
                    try {
                        DatagramSocket().use { bSocket ->
                            bSocket.broadcast = true
                            val bPacket =
                                DatagramPacket(sendData, sendData.size, broadcastAddress, port)
                            bSocket.send(bPacket)
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "UdpDiscovery",
                            "Broadcast failed on ${broadcastAddress.hostAddress}: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    override suspend fun startListening(
        groupAddress: String,
        port: Int,
    ) = withContext(
        Dispatchers.IO
    ) {
        shouldListen = true
        while (shouldListen) {
            val actualContext = context ?: PickleContext.context
            val wifiManager =
                actualContext?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager

            val multicastLock = wifiManager?.createMulticastLock("UdpMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val group = InetAddress.getByName(groupAddress)

            try {
                MulticastSocket(port).use { socket ->
                    socket.joinGroup(group)
                    socket.soTimeout = 1000

                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)

                    while (isActive) {
                        try {
                            socket.receive(packet)
                            val packetAddress = packet.address
                            val isOwnIp = NetworkInterface.getByInetAddress(packetAddress) != null
                            val packetData: PacketData?
                            if (!isOwnIp) {
                                packetData = processPacket(packet)
                                val targetIp = packetAddress.hostAddress ?: continue
                                if (packetData?.announce == true) {
                                    sendDirectReply(
                                        targetIp,
                                        deviceName = getPlatformDeviceModel()
                                    )
                                }
                                if (packetData != null) {
                                    activePeerMap[targetIp] = Peer(
                                        deviceName = packetData.senderName,
                                        lastSeen = System.currentTimeMillis(),
                                        peerIp = targetIp,
                                        wannaSend = packetData.wannaSend
                                    )
                                    _peersFlow.value = activePeerMap.values.toList()
                                }
                            }
                        } catch (_: Exception) {
                            continue
                        }
                    }
                    socket.leaveGroup(group)
                }
            } catch (e: Exception) {
                Log.e("startListening", e.message.toString())
            } finally {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
    }

    private suspend fun startPruningPeers() {
        while (managerScope.isActive) {
            delay(1000.milliseconds)
            val now = System.currentTimeMillis()
            var changed = false
            val iterator = activePeerMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastSeen > 10_000L) {
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) {
                _peersFlow.value = activePeerMap.values.toList()
            }
        }
    }

    override fun stopListening() {
        shouldListen = false
    }

    override fun setSendMode(value: Boolean) {
        wannaSend = value
    }

    private suspend fun startAutoSubnetScanner() {
        delay(2500.milliseconds)
        while (managerScope.isActive) {
            val needsScan = if (wannaSend) {
                activePeerMap.values.none { !it.wannaSend }
            } else {
                activePeerMap.isEmpty()
            }
            if (needsScan) {
                scanSubnet(timeoutMs = 300)
            }
            delay(4000.milliseconds)
        }
    }

    private fun getLocalSubnetIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback } ?: return emptyList()

            for (ni in interfaces) {
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val prefix = ia.networkPrefixLength.toInt()
                        val ipBytes = addr.address
                        val ipInt = ((ipBytes[0].toInt() and 0xFF) shl 24) or
                                ((ipBytes[1].toInt() and 0xFF) shl 16) or
                                ((ipBytes[2].toInt() and 0xFF) shl 8) or
                                (ipBytes[3].toInt() and 0xFF)

                        val effectivePrefix = if (prefix in 24..30) prefix else 24
                        val mask = (-1 shl (32 - effectivePrefix))
                        val network = ipInt and mask
                        val broadcast = network or mask.inv()

                        for (hostInt in (network + 1) until broadcast) {
                            if (hostInt == ipInt) continue
                            val b0 = (hostInt ushr 24) and 0xFF
                            val b1 = (hostInt ushr 16) and 0xFF
                            val b2 = (hostInt ushr 8) and 0xFF
                            val b3 = hostInt and 0xFF
                            ips.add("$b0.$b1.$b2.$b3")
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ips.distinct()
    }

    private fun probeIp(ip: String, timeoutMs: Int): Peer? {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, 53318), timeoutMs)
            socket.soTimeout = timeoutMs

            val out = socket.getOutputStream()
            val request = "GET /api/info HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n\r\n"
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val statusLine = input.readLine() ?: return null
            if (!statusLine.contains("200")) return null

            var contentLength = -1
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx != -1) {
                    val headerName = line.substring(0, colonIdx).trim().lowercase()
                    val headerVal = line.substring(colonIdx + 1).trim()
                    if (headerName == "content-length") {
                        contentLength = headerVal.toIntOrNull() ?: -1
                    }
                }
            }

            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = input.read(chars, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                String(chars, 0, readTotal)
            } else {
                input.readText()
            }

            val jsonObj = try {
                networkJson.parseToJsonElement(body).jsonObject
            } catch (_: Exception) {
                return null
            }

            val respAppId = jsonObj["appId"]?.jsonPrimitive?.contentOrNull
            if (respAppId != appId) return null

            val peerDeviceName = jsonObj["deviceName"]?.jsonPrimitive?.contentOrNull ?: "Unknown Device"
            val peerWannaSend = jsonObj["wannaSend"]?.jsonPrimitive?.booleanOrNull ?: false

            return Peer(
                deviceName = peerDeviceName,
                lastSeen = System.currentTimeMillis(),
                peerIp = ip,
                wannaSend = peerWannaSend
            )
        } catch (_: Exception) {
            return null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    override suspend fun scanSubnet(timeoutMs: Int): List<Peer> = withContext(Dispatchers.IO) {
        _isScanningFlow.value = true
        try {
            val ips = getLocalSubnetIps()
            if (ips.isEmpty()) return@withContext emptyList()

            // 1. Fast unicast UDP ping to all IPs on port 53317
            try {
                val deviceName = getPlatformDeviceModel()
                val jsonMessage = networkJson.encodeToString(
                    PacketData.serializer(),
                    PacketData(
                        announce = true,
                        senderName = deviceName,
                        appId = appId,
                        wannaSend = wannaSend
                    )
                ).toByteArray(Charsets.UTF_8)

                DatagramSocket().use { udpSocket ->
                    for (ip in ips) {
                        try {
                            val addr = InetAddress.getByName(ip)
                            val packet = DatagramPacket(jsonMessage, jsonMessage.size, addr, port)
                            udpSocket.send(packet)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}

            // 2. Concurrently probe TCP port 53318 (/api/info) with timeoutMs
            val discoveredPeers = CopyOnWriteArrayList<Peer>()
            val semaphore = Semaphore(256)
            coroutineScope {
                ips.map { ip ->
                    launch {
                        semaphore.withPermit {
                            val peer = probeIp(ip, timeoutMs)
                            if (peer != null) {
                                discoveredPeers.add(peer)
                                activePeerMap[peer.peerIp] = peer
                                _peersFlow.value = activePeerMap.values.toList()
                            }
                        }
                    }
                }.joinAll()
            }
            discoveredPeers.toList()
        } finally {
            _isScanningFlow.value = false
        }
    }
}

actual fun createUdpBroadcastManager(appId: String): UdpBroadcastManager {
    return AndroidUdpBroadcastManager(PickleContext.context, appId)
}

fun UdpBroadcastManager(context: Context, appId: String = "PickleShare"): UdpBroadcastManager {
    return AndroidUdpBroadcastManager(context, appId)
}
