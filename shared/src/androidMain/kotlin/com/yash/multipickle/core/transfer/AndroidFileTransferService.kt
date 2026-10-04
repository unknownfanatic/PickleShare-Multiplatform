package com.yash.multipickle.core.transfer

import android.util.Log
import com.yash.multipickle.core.storage.FileDestinationManager
import com.yash.multipickle.core.storage.getPlatformDeviceModel
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.context
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val CHUNK_SIZE = 64 * 1024

private fun readHttpLine(input: InputStream): String? {
    val sb = StringBuilder()
    var c = input.read()
    if (c == -1) return null
    while (c != -1 && c != '\n'.code) {
        if (c != '\r'.code) {
            sb.append(c.toChar())
        }
        c = input.read()
    }
    return sb.toString()
}

private class HttpChunkedInputStream(private val input: InputStream) : InputStream() {
    private var currentChunkRemaining = 0L
    private var isEof = false

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (isEof) return -1

        while (currentChunkRemaining == 0L) {
            val sizeLine = readHttpLine(input)?.trim()
            if (sizeLine == null) {
                isEof = true
                return -1
            }
            if (sizeLine.isEmpty()) {
                continue
            }
            val chunkSize = try {
                sizeLine.split(";")[0].trim().toLong(16)
            } catch (_: Exception) {
                isEof = true
                return -1
            }
            if (chunkSize <= 0L) {
                while (true) {
                    val trailer = readHttpLine(input) ?: break
                    if (trailer.trim().isEmpty()) break
                }
                isEof = true
                return -1
            }
            currentChunkRemaining = chunkSize
            break
        }

        val toRead = minOf(len.toLong(), currentChunkRemaining).toInt()
        val readCount = input.read(b, off, toRead)
        if (readCount == -1) {
            isEof = true
            return -1
        }
        currentChunkRemaining -= readCount
        if (currentChunkRemaining == 0L) {
            readHttpLine(input) // Consume trailing CRLF after chunk data
        }
        return readCount
    }

    override fun available(): Int = minOf(currentChunkRemaining, input.available().toLong()).toInt()

    override fun close() {
        // Do not close input socket
    }
}

private class HttpFixedLengthInputStream(
    private val input: InputStream,
    private var remaining: Long
) : InputStream() {
    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n == -1) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0L) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val readCount = input.read(b, off, toRead)
        if (readCount == -1) {
            remaining = 0L
            return -1
        }
        remaining -= readCount
        return readCount
    }

    override fun available(): Int = minOf(remaining, input.available().toLong()).toInt()

    override fun close() {
        // Do not close input socket
    }
}

class AndroidFileTransferServer(
    private val destinationManager: FileDestinationManager
) : FileTransferServer {

    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private val _incomingPrompt = MutableStateFlow<IncomingTransferPrompt?>(null)
    override val incomingPromptFlow: StateFlow<IncomingTransferPrompt?> = _incomingPrompt.asStateFlow()

    private val _progressFlow = MutableStateFlow(0f)
    override val progressFlow: StateFlow<Float> = _progressFlow.asStateFlow()

    private val _fileProgressFlow = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val fileProgressFlow: StateFlow<Map<String, Float>> = _fileProgressFlow.asStateFlow()

    private val _statusFlow = MutableStateFlow<String?>(null)
    override val statusFlow: StateFlow<String?> = _statusFlow.asStateFlow()

    private val expectedFileSizes = ConcurrentHashMap<String, Long>()

    private var expectedTotalBytes = 0L
    private var totalReceivedBytes = 0L
    private var expectedFilesCount = 0
    private var receivedFilesCount = 0

    private val promptLock = Any()
    private val pendingPrompts = ArrayDeque<IncomingTransferPrompt>()
    private val transferLock = Any()
    private var activeTransfersCount = 0

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun start(port: Int) {
        if (serverSocket != null && !serverSocket!!.isClosed) return

        _progressFlow.value = 0f
        _fileProgressFlow.value = emptyMap()
        _statusFlow.value = null
        expectedFileSizes.clear()
        synchronized(transferLock) {
            expectedTotalBytes = 0L
            totalReceivedBytes = 0L
            expectedFilesCount = 0
            receivedFilesCount = 0
            activeTransfersCount = 0
        }
        synchronized(promptLock) {
            pendingPrompts.clear()
            _incomingPrompt.value = null
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        serverScope = scope

        scope.launch {
            try {
                val sSocket = ServerSocket()
                sSocket.reuseAddress = true
                sSocket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 50)
                serverSocket = sSocket
                Log.i("AndroidFileTransfer", "Started HTTP server on port $port (0.0.0.0)")

                while (isActive && !sSocket.isClosed) {
                    val client = try {
                        sSocket.accept()
                    } catch (e: Exception) {
                        if (sSocket.isClosed || !isActive) break
                        continue
                    }
                    launch {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                Log.e("AndroidFileTransfer", "Failed to bind port $port: ${e.message}", e)
            }
        }
    }

    override fun stop() {
        val sSocket = serverSocket
        serverSocket = null
        serverScope?.cancel()
        serverScope = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sSocket?.close()
            } catch (_: Exception) {}
        }
        synchronized(promptLock) {
            pendingPrompts.clear()
            _incomingPrompt.value = null
        }
        synchronized(transferLock) {
            expectedTotalBytes = 0L
            totalReceivedBytes = 0L
            expectedFilesCount = 0
            receivedFilesCount = 0
            activeTransfersCount = 0
        }
        expectedFileSizes.clear()
        _fileProgressFlow.value = emptyMap()
        _progressFlow.value = 0f
        _statusFlow.value = null
        Log.i("AndroidFileTransfer", "Stopped HTTP server")
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readHttpLine(input) ?: run {
                socket.close()
                return@withContext
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return@withContext
            }
            val method = parts[0].uppercase()
            val uri = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val headerLine = readHttpLine(input) ?: break
                if (headerLine.isEmpty()) break
                val colonIdx = headerLine.indexOf(':')
                if (colonIdx != -1) {
                    val key = headerLine.substring(0, colonIdx).trim().lowercase()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            when {
                method == "GET" && uri.startsWith("/api/info") -> {
                    val deviceName = getPlatformDeviceModel()
                    val responseJson = """{"deviceName":"$deviceName","appId":"PickleShare","wannaSend":false}"""
                    val resBytes = responseJson.toByteArray(Charsets.UTF_8)
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${resBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    out.write(resBytes)
                    out.flush()
                }

                method == "POST" && uri.startsWith("/api/transfer/request") -> {
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val count = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (count == -1) break
                        readTotal += count
                    }
                    val bodyString = String(bodyBytes, Charsets.UTF_8)
                    val transferRequest = json.decodeFromString(TransferRequest.serializer(), bodyString)

                    val decision = CompletableDeferred<Boolean>()
                    lateinit var prompt: IncomingTransferPrompt
                    prompt = IncomingTransferPrompt(
                        request = transferRequest,
                        onAccept = {
                            synchronized(promptLock) {
                                val next = pendingPrompts.removeFirstOrNull()
                                _incomingPrompt.value = next
                            }
                            _statusFlow.value = "Transfer accepted. Waiting for files..."
                            _progressFlow.value = 0.05f
                            decision.complete(true)
                        },
                        onDeny = {
                            synchronized(promptLock) {
                                val next = pendingPrompts.removeFirstOrNull()
                                _incomingPrompt.value = next
                            }
                            _statusFlow.value = "Transfer denied"
                            decision.complete(false)
                        }
                    )

                    synchronized(promptLock) {
                        if (_incomingPrompt.value == null) {
                            _incomingPrompt.value = prompt
                        } else {
                            pendingPrompts.addLast(prompt)
                        }
                    }

                    // Hold the connection open while prompt is pending
                    val accepted = try {
                        decision.await()
                    } catch (_: Exception) {
                        false
                    }

                    val out = socket.getOutputStream()
                    if (accepted) {
                        for (f in transferRequest.files.values) {
                            expectedFileSizes[f.fileName] = f.size
                        }
                        _fileProgressFlow.update { current ->
                            current + transferRequest.files.values.associate { it.fileName to 0f }
                        }
                        val reqBytes = transferRequest.files.values.sumOf { it.size }.coerceAtLeast(1L)
                        synchronized(transferLock) {
                            if (activeTransfersCount == 0 && (receivedFilesCount >= expectedFilesCount || totalReceivedBytes >= expectedTotalBytes)) {
                                expectedTotalBytes = reqBytes
                                totalReceivedBytes = 0L
                                expectedFilesCount = transferRequest.files.size
                                receivedFilesCount = 0
                            } else {
                                expectedTotalBytes += reqBytes
                                expectedFilesCount += transferRequest.files.size
                            }
                        }
                        val responseJson = """{"status":"accepted"}"""
                        val resBytes = responseJson.toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${resBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        out.write(resBytes)
                        out.flush()
                    } else {
                        val responseJson = """{"status":"denied","message":"Request denied"}"""
                        val resBytes = responseJson.toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: ${resBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        out.write(resBytes)
                        out.flush()
                    }
                }

                method == "POST" && uri.startsWith("/api/transfer/upload") -> {
                    val queryIdx = uri.indexOf('?')
                    var fileName = "received_file_${System.currentTimeMillis()}"
                    if (queryIdx != -1) {
                        val query = uri.substring(queryIdx + 1)
                        for (param in query.split("&")) {
                            val kv = param.split("=")
                            if (kv.size == 2 && kv[0] == "fileName") {
                                fileName = URLDecoder.decode(kv[1], "UTF-8")
                            }
                        }
                    }
                    headers["x-file-name"]?.let {
                        fileName = URLDecoder.decode(it, "UTF-8")
                    }
                    fileName = File(fileName).name

                    _statusFlow.value = "Receiving $fileName..."
                    val destFolder = destinationManager.getDestinationFolder()
                    if (destFolder == null) {
                        Log.e("AndroidFileTransfer", "Destination folder could not be determined")
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    } else {
                        val targetFile = File(destFolder, fileName)
                        targetFile.parentFile?.mkdirs()
                        Log.i("AndroidFileTransfer", "Saving incoming file to: ${targetFile.absolutePath}")
                        val isChunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
                        val isGzip = headers["content-encoding"]?.contains("gzip", ignoreCase = true) == true
                        val fileLength = headers["content-length"]?.toLongOrNull() ?: -1L
                        val expectedFileSize = expectedFileSizes[fileName] ?: if (fileLength > 0L) fileLength else 0L
                        var fileReceivedBytes = 0L
                        _fileProgressFlow.update { it + (fileName to 0.05f) }
                        synchronized(transferLock) {
                            activeTransfersCount++
                        }
                        try {
                            saveStreamToFile(input, targetFile, isChunked, fileLength, isGzip) { bytesRead ->
                                fileReceivedBytes += bytesRead
                                if (expectedFileSize > 0L) {
                                    val fileP = (fileReceivedBytes.toFloat() / expectedFileSize.toFloat()).coerceIn(0.01f, 0.99f)
                                    _fileProgressFlow.update { it + (fileName to fileP) }
                                }
                                val curTotal: Long
                                val expTotal: Long
                                synchronized(transferLock) {
                                    totalReceivedBytes += bytesRead
                                    curTotal = totalReceivedBytes
                                    expTotal = expectedTotalBytes
                                }
                                val targetTotal = if (expTotal > 1L) expTotal else if (fileLength > 0L) fileLength else 1L
                                val p = (curTotal.toFloat() / targetTotal.toFloat()).coerceIn(0.05f, 0.99f)
                                _progressFlow.value = p
                            }
                            _fileProgressFlow.update { it + (fileName to 1.0f) }

                            val done: Boolean
                            val rCount: Int
                            val eCount: Int
                            synchronized(transferLock) {
                                receivedFilesCount++
                                rCount = receivedFilesCount
                                eCount = expectedFilesCount
                                done = (receivedFilesCount >= expectedFilesCount)
                            }
                            if (done) {
                                _progressFlow.value = 1.0f
                                _statusFlow.value = "Files received successfully!"
                            } else {
                                _statusFlow.value = "Received $fileName ($rCount/$eCount)"
                            }

                            val out = socket.getOutputStream()
                            val responseJson = """{"status":"success"}"""
                            val resBytes = responseJson.toByteArray(Charsets.UTF_8)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${resBytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                            out.write(resBytes)
                            out.flush()
                        } catch (e: Exception) {
                            Log.e("AndroidFileTransfer", "Error writing file ${targetFile.absolutePath}: ${e.message}", e)
                            val out = socket.getOutputStream()
                            out.write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        } finally {
                            synchronized(transferLock) {
                                activeTransfersCount--
                            }
                        }
                    }
                }

                else -> {
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        } catch (e: Exception) {
            Log.e("AndroidFileTransfer", "Error handling client: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun saveStreamToFile(
        input: InputStream,
        targetFile: File,
        isChunked: Boolean,
        contentLength: Long,
        isGzip: Boolean,
        onChunkRead: (Int) -> Unit
    ) {
        val rawBodyStream: InputStream = when {
            isChunked -> HttpChunkedInputStream(input)
            contentLength >= 0 -> HttpFixedLengthInputStream(input, contentLength)
            else -> input
        }

        val decodedStream: InputStream = if (isGzip) {
            GZIPInputStream(rawBodyStream, CHUNK_SIZE)
        } else {
            rawBodyStream
        }

        FileOutputStream(targetFile).use { fos ->
            val buffer = ByteArray(CHUNK_SIZE)
            var read: Int
            while (decodedStream.read(buffer).also { read = it } != -1) {
                fos.write(buffer, 0, read)
                onChunkRead(read)
            }
            fos.flush()
        }
    }
}

class AndroidFileTransferClient : FileTransferClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun sendFiles(
        targetIp: String,
        port: Int,
        senderInfo: DeviceInfo,
        files: List<PlatformFile>,
        useCompression: Boolean,
        onProgress: (Float) -> Unit,
        onFileProgress: (fileName: String, progress: Float) -> Unit,
        onStatusChange: (String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onStatusChange("Waiting for receiver to accept...")
            onProgress(0.05f)

            val fileMap = files.mapIndexed { index, file ->
                val id = "file-id-${index + 1}"
                val size = try { file.size() } catch (_: Exception) { 0L }
                val type = file.mimeType()?.let { "${it.primaryType}/${it.subtype}" } ?: "application/octet-stream"
                id to FileMetadata(fileName = file.name, size = size, fileType = type)
            }.toMap()

            val request = TransferRequest(
                info = senderInfo,
                files = fileMap
            )
            val jsonBody = json.encodeToString(TransferRequest.serializer(), request)
            val jsonBytes = jsonBody.toByteArray(Charsets.UTF_8)

            Log.d("AndroidFileTransferClient", "Sending transfer request to $targetIp:$port with ${files.size} files: ${files.map { it.name }}")
            // Step 1: Send metadata request to receiver, holding connection open
            val requestUrl = URL("http://$targetIp:$port/api/transfer/request")
            val conn = (requestUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Length", jsonBytes.size.toString())
                connectTimeout = 30000
                readTimeout = 120000 // Keep waiting while receiver answers the prompt
            }

            conn.outputStream.use { os ->
                os.write(jsonBytes)
                os.flush()
            }

            val responseCode = try {
                conn.responseCode
            } catch (e: Exception) {
                Log.e("AndroidFileTransferClient", "Failed to connect to $targetIp:$port: ${e.message}", e)
                onStatusChange("Connection failed: ${e.message}")
                onProgress(0.0f)
                return@withContext Result.failure(e)
            }

            val responseText = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            Log.d("AndroidFileTransferClient", "Transfer request responseCode=$responseCode, responseText=$responseText")

            val transferResponse = try {
                json.decodeFromString(TransferResponse.serializer(), responseText)
            } catch (e: Exception) {
                TransferResponse(status = if (responseCode == 200) "accepted" else "denied")
            }

            if (transferResponse.status != "accepted") {
                Log.d("AndroidFileTransferClient", "Transfer request was denied by receiver")
                onStatusChange("Request denied")
                onProgress(0.0f)
                return@withContext Result.failure(Exception("Request denied"))
            }

            onStatusChange("Request accepted! Sending files...")

            // Step 2: Upload files
            val totalBytes = files.sumOf { try { it.size() } catch (_: Exception) { 0L } }.coerceAtLeast(1L)
            var transferredBytes = 0L

            for (file in files) {
                val fileTotalBytes = try { file.size() } catch (_: Exception) { 0L }.coerceAtLeast(1L)
                var fileTransferredBytes = 0L
                onFileProgress(file.name, 0.05f)

                val uploadUrl = URL("http://$targetIp:$port/api/transfer/upload?fileName=${URLEncoder.encode(file.name, "UTF-8")}")
                val uploadConn = (uploadUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setChunkedStreamingMode(CHUNK_SIZE)
                    setRequestProperty("Content-Type", "application/octet-stream")
                    if (useCompression) {
                        setRequestProperty("Content-Encoding", "gzip")
                    }
                    setRequestProperty("X-File-Name", URLEncoder.encode(file.name, "UTF-8"))
                    connectTimeout = 15000
                    readTimeout = 60000
                }

                Log.d("AndroidFileTransferClient", "Uploading ${file.name} to $uploadUrl (size: $fileTotalBytes, compression: $useCompression)")
                openInputStream(file).buffered().use { fis ->
                    uploadConn.outputStream.use { rawOs ->
                        if (useCompression) {
                            GZIPOutputStream(rawOs, CHUNK_SIZE).use { gzipOs ->
                                val buffer = ByteArray(CHUNK_SIZE)
                                var read: Int
                                while (fis.read(buffer).also { read = it } != -1) {
                                    gzipOs.write(buffer, 0, read)
                                    fileTransferredBytes += read
                                    transferredBytes += read
                                    onFileProgress(file.name, (fileTransferredBytes.toFloat() / fileTotalBytes.toFloat()).coerceIn(0.05f, 0.99f))
                                    onProgress((transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.05f, 0.99f))
                                }
                                gzipOs.finish()
                                gzipOs.flush()
                            }
                        } else {
                            val buffer = ByteArray(CHUNK_SIZE)
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                rawOs.write(buffer, 0, read)
                                fileTransferredBytes += read
                                transferredBytes += read
                                onFileProgress(file.name, (fileTransferredBytes.toFloat() / fileTotalBytes.toFloat()).coerceIn(0.05f, 0.99f))
                                onProgress((transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0.05f, 0.99f))
                            }
                            rawOs.flush()
                        }
                    }
                }

                val uploadResponseCode = uploadConn.responseCode
                Log.d("AndroidFileTransferClient", "Uploaded ${file.name}, responseCode=$uploadResponseCode")
                if (uploadResponseCode !in 200..299) {
                    Log.e("AndroidFileTransferClient", "Failed to upload ${file.name}, HTTP $uploadResponseCode")
                    onStatusChange("Failed to upload ${file.name}")
                    return@withContext Result.failure(Exception("Upload failed for ${file.name}"))
                }
                onFileProgress(file.name, 1.0f)
            }

            onProgress(1.0f)
            onStatusChange("Files sent successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AndroidFileTransferClient", "Error in sendFiles: ${e.message}", e)
            onStatusChange("Error: ${e.message}")
            onProgress(0.0f)
            Result.failure(e)
        }
    }

    private fun openInputStream(file: PlatformFile): InputStream {
        return when (val af = file.androidFile) {
            is AndroidFile.FileWrapper -> af.file.inputStream()
            is AndroidFile.UriWrapper -> {
                FileKit.context.contentResolver.openInputStream(af.uri)
                    ?: throw IllegalStateException("Could not open input stream for ${file.name}")
            }
        }
    }
}

actual fun createFileTransferServer(destinationManager: FileDestinationManager): FileTransferServer {
    return AndroidFileTransferServer(destinationManager)
}

actual fun createFileTransferClient(): FileTransferClient {
    return AndroidFileTransferClient()
}
