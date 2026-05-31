package com.maxmini

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker
import java.io.*
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.*

/**
 * TCP+TLS клиент для протокола MAX.
 *
 * Формат фрейма (PyMax TcpPacketFramer):
 *   [ver:1][cmd:1][seq:2][opcode:2][flags+len:4][payload:...]
 *   header = 10 байт
 *   cmd: 0=REQUEST, 1=RESPONSE, 2=EVENT, 3=ERROR
 *   flags+len: flags(8bit) | payload_length(24bit)
 *   payload: msgpack
 */
class MaxTcpClient(
    private val host: String = "api.oneme.ru",
    private val port: Int = 443,
    private val useSsl: Boolean = true
) {
    companion object {
        private const val TAG = "MaxTcpClient"
        private const val HEADER_SIZE = 10
        private const val READ_TIMEOUT = 30000L
        private const val WRITE_TIMEOUT = 15000L

        // Commands
        const val CMD_REQUEST = 0
        const val CMD_RESPONSE = 1
        const val CMD_EVENT = 2
        const val CMD_ERROR = 3
    }

    @Volatile
    var isConnected: Boolean = false
        private set
    @Volatile
    var isClosed: Boolean = false
        private set

    private var socket: SSLSocket? = null
    private var reader: DataInputStream? = null
    private var writer: DataOutputStream? = null
    private var readerJob: Job? = null
    private var coroutineScope: CoroutineScope? = null
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<Frame>>()
    private val mutex = Mutex()
    private var seqCounter = 0

    // Коллбэки
    var onFrame: ((Frame) -> Unit)? = null
    var onDisconnect: ((cause: String?) -> Unit)? = null
    var onConnect: (() -> Unit)? = null

    data class Frame(
        val ver: Int,
        val cmd: Int,
        val seq: Int,
        val opcode: Int,
        val flags: Int,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return ver == other.ver && cmd == other.cmd && seq == other.seq &&
                opcode == other.opcode && flags == other.flags &&
                payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int {
            return ver + cmd + seq + opcode + flags + payload.contentHashCode()
        }
    }

    suspend fun connect(): Boolean = mutex.withLock {
        if (isConnected) return true
        isClosed = false
        try {
            Log.d(TAG, "connect: $host:$port ssl=$useSsl")
            val sslSocket = createSslSocket()
            sslSocket.connect(InetSocketAddress(host, port), 10000)
            sslSocket.soTimeout = READ_TIMEOUT.toInt()
            sslSocket.startHandshake()
            socket = sslSocket
            reader = DataInputStream(BufferedInputStream(sslSocket.inputStream))
            writer = DataOutputStream(BufferedOutputStream(sslSocket.outputStream))
            isConnected = true
            Log.d(TAG, "connect: OK")

            coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            readerJob = coroutineScope?.launch { startReaderLoop() }
            onConnect?.invoke()
            true
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            closeInternal("connect_failed: ${e.message}")
            false
        }
    }

    private fun createSslSocket(): SSLSocket {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())
        val factory = sslContext.socketFactory
        return factory.createSocket() as SSLSocket
    }

    private suspend fun startReaderLoop() {
        Log.d(TAG, "readerLoop: entered")
        try {
            while (isConnected && !isClosed) {
                val frame = readFrame() ?: continue
                // Если есть ожидающий запрос по seq — доставляем
                val deferred = pendingRequests[frame.seq]
                if (deferred != null) {
                    deferred.complete(frame)
                    continue
                }
                // Иначе через коллбэк
                onFrame?.invoke(frame)
            }
        } catch (e: EOFException) {
            Log.w(TAG, "readerLoop: EOF")
            onDisconnect?.invoke("EOF")
            closeInternal("EOF")
        } catch (e: CancellationException) {
            Log.d(TAG, "readerLoop: cancelled")
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "readerLoop: timeout")
            onDisconnect?.invoke("timeout")
            closeInternal("timeout")
        } catch (e: Exception) {
            Log.e(TAG, "readerLoop: ${e.message}")
            onDisconnect?.invoke("reader_error: ${e.message}")
            closeInternal("reader_error")
        }
    }

    /**
     * Читает один фрейм:
     *   header: ver(1) | cmd(1) | seq(2) | opcode(2) | flags_len(4) = 10 байт
     *   payload: [payload_len] байт msgpack
     */
    private fun readFrame(): Frame? {
        val r = reader ?: return null
        return try {
            val header = ByteArray(HEADER_SIZE)
            r.readFully(header)
            val buf = ByteBuffer.wrap(header).order(java.nio.ByteOrder.BIG_ENDIAN)
            val ver = buf.get().toInt() and 0xFF
            val cmd = buf.get().toInt() and 0xFF
            val seq = buf.getShort().toInt() and 0xFFFF
            val opcode = buf.getShort().toInt() and 0xFFFF
            val packedLen = buf.getInt()
            val flags = (packedLen shr 24) and 0xFF
            val payloadLen = packedLen and 0x00FFFFFF

            val payload = if (payloadLen > 0) {
                val p = ByteArray(payloadLen)
                r.readFully(p)
                p
            } else byteArrayOf()

            Frame(ver, cmd, seq, opcode, flags, payload)
        } catch (e: EOFException) { throw e
        } catch (e: SocketTimeoutException) { throw e
        } catch (e: Exception) {
            Log.w(TAG, "readFrame: skip bad: ${e.message}")
            null
        }
    }

    /**
     * Отправляет фрейм и ждёт ответ.
     */
    suspend fun request(opcode: Int, payload: ByteArray, timeoutMs: Long = WRITE_TIMEOUT): Frame? {
        val seq = nextSeq()
        val deferred = CompletableDeferred<Frame>()
        pendingRequests[seq] = deferred

        try {
            sendFrame(CMD_REQUEST, seq, opcode, 0, payload)
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "request timeout opcode=$opcode seq=$seq")
            return null
        } finally {
            pendingRequests.remove(seq)
        }
    }

    /**
     * Отправляет ответ (для обработки входящих).
     */
    suspend fun respond(seq: Int, opcode: Int, payload: ByteArray) {
        sendFrame(CMD_RESPONSE, seq, opcode, 0, payload)
    }

    private suspend fun sendFrame(cmd: Int, seq: Int, opcode: Int, flags: Int, payload: ByteArray) {
        val payloadLen = payload.size
        if (payloadLen > 0xFFFFFF) throw IOException("Payload too large: $payloadLen")
        val packedLen = ((flags and 0xFF) shl 24) or (payloadLen and 0x00FFFFFF)

        mutex.withLock {
            val w = writer ?: throw IOException("Not connected")
            val header = ByteBuffer.allocate(HEADER_SIZE).order(java.nio.ByteOrder.BIG_ENDIAN).also {
                it.put(10.toByte())                 // ver = 10
                it.put(cmd.toByte())                 // cmd
                it.putShort(seq.toShort())           // seq
                it.putShort(opcode.toShort())        // opcode
                it.putInt(packedLen)                 // flags+len
            }.array()
            w.write(header)
            if (payloadLen > 0) w.write(payload)
            w.flush()
        }
    }

    private fun nextSeq(): Int {
        seqCounter++
        if (seqCounter > 0xFFFF) seqCounter = 1
        return seqCounter
    }

    suspend fun close() {
        Log.d(TAG, "close: enter")
        try {
            withTimeout(5000) { closeInternal("user_close") }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "close: timeout")
        } catch (e: Exception) {
            Log.w(TAG, "close: ${e.message}")
        }
    }

    private suspend fun closeInternal(cause: String?) {
        if (isClosed) return
        isClosed = true
        isConnected = false
        readerJob?.cancel()
        readerJob = null
        coroutineScope?.cancel()
        coroutineScope = null
        for ((_, d) in pendingRequests) { d.cancel() }
        pendingRequests.clear()
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null; reader = null; socket = null
        onDisconnect?.invoke(cause)
    }
}
