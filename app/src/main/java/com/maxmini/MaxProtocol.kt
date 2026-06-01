package com.maxmini

import android.util.Log
import kotlinx.coroutines.*
import org.msgpack.core.MessagePack
import org.msgpack.value.ValueType
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.TimeZone

/**
 * Протокол MAX — реализация на основе pymax.
 *
 * Opcode из PyMax:
 *   PING = 1, SESSION_INIT = 6, AUTH_REQUEST = 17, AUTH = 18, LOGIN = 19
 *   CONTACT_SEARCH = 37, CHAT_HISTORY = 49, CHATS_LIST = 53, MSG_SEND = 64
 */
class MaxProtocol(private val client: MaxTcpClient) {
    companion object {
        private const val TAG = "MaxProtocol"
        const val OP_PING = 1
        const val OP_SESSION_INIT = 6
        const val OP_AUTH_REQUEST = 17
        const val OP_AUTH = 18
        const val OP_LOGIN = 19
        const val OP_CONTACT_SEARCH = 37
        const val OP_CHAT_HISTORY = 49
        const val OP_CHATS_LIST = 53
        const val OP_MSG_SEND = 64
        const val OP_FILE_DOWNLOAD = 88

        const val AUTH_TYPE_START = 0
        const val AUTH_TYPE_CHECK_CODE = 1
    }

    // ─── Состояние (только AppState — единый источник истины) ─────────────
    // A8: убраны дублирующие поля — всё через AppState

    // Token
    private var savedToken: String? = null
    private var currentPhone: String? = null

    // Сессия
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null

    // Callbacks
    var onAuthenticated: ((token: String) -> Unit)? = null
    var onMessage: ((Map<String, Any?>) -> Unit)? = null
    var onConnectionLost: ((String?) -> Unit)? = null
    var onChatsLoaded: ((List<Map<String, Any?>>) -> Unit)? = null

    // Ping task
    private var pingJob: Job? = null

    // ─── LZ4 Decompression (как в Pymax) ──────────────────────────────────

    /**
     * LZ4 block decompression — портировано из Pymax Lz4BlockCompression.
     */
    private fun lz4Decompress(src: ByteArray, maxOutput: Int = 5 * 1024 * 1024): ByteArray {
        val dst = ByteArrayOutputStream(maxOutput)
        var pos = 0
        while (pos < src.size) {
            val token = src[pos].toInt() and 0xFF
            pos++

            var litLen = (token shr 4) and 0x0F
            if (litLen == 15) {
                while (pos < src.size) {
                    val b = src[pos].toInt() and 0xFF
                    pos++
                    litLen += b
                    if (b != 255) break
                }
            }

            if (litLen > 0) {
                if (pos + litLen > src.size) throw IOException("LZ4: literal length out of bounds")
                dst.write(src, pos, litLen)
                pos += litLen
                if (dst.size() > maxOutput) throw IOException("LZ4: output too large")
            }

            if (pos >= src.size) break

            if (pos + 2 > src.size) throw IOException("LZ4: incomplete offset")
            val offset = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            if (offset == 0) throw IOException("LZ4: zero offset")

            var matchLen = (token and 0x0F) + 4
            if ((token and 0x0F) == 0x0F) {
                while (pos < src.size) {
                    val b = src[pos].toInt() and 0xFF
                    pos++
                    matchLen += b
                    if (b != 255) break
                }
            }

            val matchPos = dst.size() - offset
            if (matchPos < 0) throw IOException("LZ4: match out of bounds")

            val buf = dst.toByteArray()
            for (i in 0 until matchLen) {
                dst.write(buf[matchPos + (i % offset)].toInt())
            }

            if (dst.size() > maxOutput) throw IOException("LZ4: output too large")
        }
        return dst.toByteArray()
    }

    /**
     * Распаковка payload с учётом flags (как Pymax TcpPayloadDecoder.decode).
     * flags & 0x03 — LZ4 compression.
     */
    private fun decompressPayload(payload: ByteArray, flags: Int): ByteArray {
        if (payload.isEmpty()) return payload
        return if ((flags and 0x03) != 0) {
            try {
                val decompressed = lz4Decompress(payload)
                Log.d(TAG, "LZ4 decompressed: ${payload.size} → ${decompressed.size} bytes")
                decompressed
            } catch (e: Exception) {
                Log.w(TAG, "LZ4 decompress failed, using raw: ${e.message}")
                payload
            }
        } else {
            payload
        }
    }

    // ─── Msgpack helpers ─────────────────────────────────────────────────

    private fun msgpackMap(vararg pairs: Pair<String, Any?>): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(pairs.size)
        for ((k, v) in pairs) {
            packer.packString(k)
            packValue(packer, v)
        }
        packer.close()
        return packer.toByteArray()
    }

    private fun packValue(packer: org.msgpack.core.MessageBufferPacker, v: Any?) {
        when (v) {
            null -> packer.packNil()
            is String -> packer.packString(v)
            is Int -> packer.packInt(v)
            is Long -> packer.packLong(v)
            is Boolean -> packer.packBoolean(v)
            is Double -> packer.packDouble(v)
            is Float -> packer.packFloat(v)
            is Map<*, *> -> {
                packer.packMapHeader(v.size)
                for ((mk, mv) in v) {
                    packer.packString(mk.toString())
                    packValue(packer, mv)
                }
            }
            is List<*> -> {
                packer.packArrayHeader(v.size)
                for (item in v) packValue(packer, item)
            }
            else -> packer.packString(v.toString())
        }
    }

    // ─── Msgpack helpers ─────────────────────────────────────────────────

    /**
     * Ручной рекурсивный разбор msgpack через MessageUnpacker.
     * Принимает флаги для декомпрессии (как Pymax).
     */
    private fun unpackMap(data: ByteArray, flags: Int = 0): Map<String, Any?> {
        val payload = decompressPayload(data, flags)
        if (payload.isEmpty()) return emptyMap()
        try {
            val unpacker = MessagePack.newDefaultUnpacker(payload)
            val fmt = unpacker.getNextFormat()
            if (fmt.getValueType() == ValueType.MAP) {
                return unpackValue(unpacker)
            }
            // Не map — логируем и скипаем
            Log.w(TAG, "unpackMap: not a map, fmt=$fmt raw=${payload.take(32).joinToString("") { "%02x".format(it) }}")
            unpacker.skipValue()
            return emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "unpackMap error: ${e.message}, raw=${payload.take(64).joinToString("") { "%02x".format(it) }}")
            return emptyMap()
        }
    }

    /**
     * Ручной рекурсивный разбор msgpack через MessageUnpacker.
     * Не использует unpackValue() — у msgpack-core он требует string-ключи в map.
     */
    private fun unpackValue(unpacker: org.msgpack.core.MessageUnpacker): Map<String, Any?> {
        val mapSize = unpacker.unpackMapHeader()
        val result = linkedMapOf<String, Any?>()
        for (i in 0 until mapSize) {
            val key = unpackKey(unpacker)
            val value = unpackAny(unpacker)
            result[key] = value
        }
        return result
    }

    private fun unpackKey(unpacker: org.msgpack.core.MessageUnpacker): String {
        val fmt = unpacker.getNextFormat()
        return when (fmt.getValueType()) {
            org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
            org.msgpack.value.ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                val bytes = ByteArray(len)
                unpacker.readPayload(bytes)
                bytes.decodeToString()
            }
            org.msgpack.value.ValueType.INTEGER -> unpacker.unpackLong().toString()
            org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble().toString()
            else -> {
                unpacker.skipValue()
                fmt.toString()
            }
        }
    }

    private fun unpackAny(unpacker: org.msgpack.core.MessageUnpacker): Any? {
        val fmt = unpacker.getNextFormat()
        return when (fmt.getValueType()) {
            org.msgpack.value.ValueType.NIL -> { unpacker.unpackNil(); null }
            org.msgpack.value.ValueType.BOOLEAN -> unpacker.unpackBoolean()
            org.msgpack.value.ValueType.INTEGER -> unpacker.unpackLong()
            org.msgpack.value.ValueType.FLOAT -> unpacker.unpackDouble()
            org.msgpack.value.ValueType.STRING -> unpacker.unpackString()
            org.msgpack.value.ValueType.BINARY -> {
                val len = unpacker.unpackBinaryHeader()
                val bytes = ByteArray(len)
                unpacker.readPayload(bytes)
                bytes.decodeToString()
            }
            org.msgpack.value.ValueType.ARRAY -> {
                val size = unpacker.unpackArrayHeader()
                (0 until size).map { unpackAny(unpacker) }
            }
            org.msgpack.value.ValueType.MAP -> unpackValue(unpacker)
            else -> { unpacker.skipValue(); null }
        }
    }

    private fun userAgentMap(): Map<String, Any?> = mapOf(
        "deviceType" to "android",
        "appVersion" to "2.1.1",
        "osVersion" to android.os.Build.VERSION.RELEASE,
        "timezone" to TimeZone.getDefault().id,
        "screen" to "1080x1920",
        "locale" to "ru",
        "deviceLocale" to "ru",
        "deviceName" to android.os.Build.MODEL
    )

    // ─── Проверка ERROR-фреймов ──────────────────────────────────────────

    /**
     * A2: проверяет cmd ответа. Если ERROR (3) — парсит ошибку и возвращает
     * null с установкой connectError.
     */
    private fun checkError(frame: MaxTcpClient.Frame?, opName: String): Boolean {
        if (frame == null) {
            connectError = "Нет ответа на $opName"
            return false
        }
        if (frame.cmd == MaxTcpClient.CMD_ERROR) {
            val errorData = unpackMap(frame.payload, frame.flags)
            val errorMsg = errorData["message"] as? String
                ?: errorData["error"] as? String
                ?: errorData["description"] as? String
                ?: "Ошибка сервера ($opName)"
            connectError = errorMsg
            AppStateHelper.addLogEntry("$opName ERROR: $errorData")
            return false
        }
        return true
    }

    // ─── Handshake ────────────────────────────────────────────────────────

    /**
     * SESSION_INIT — как в pymax: отправляем handshake, но ответ игнорируем.
     */
    private suspend fun doHandshake(): Boolean {
        AppStateHelper.addLogEntry("Handshake...")
        val payload = msgpackMap(
            "mt_instanceid" to "",
            "userAgent" to userAgentMap(),
            "clientSessionId" to 42,
            "deviceId" to AppStateHelper.deviceId
        )
        val resp = client.request(OP_SESSION_INIT, payload)
        // A1: даже pymax игнорирует ответ SESSION_INIT — не баг
        if (!checkError(resp, "SESSION_INIT")) {
            return false
        }
        AppStateHelper.addLogEntry("Handshake OK")
        return true
    }

    // ─── Основной API ────────────────────────────────────────────────────

    /**
     * A10: Попробовать войти по сохранённому токену.
     */
    suspend fun tryLoginByToken(): Boolean {
        val token = loadToken()
        if (token == null) {
            AppStateHelper.addLogEntry("Сохранённый токен не найден")
            return false
        }
        AppStateHelper.addLogEntry("Найден сохранённый токен, пробуем вход...")

        val connected = client.connect()
        if (!connected) {
            AppStateHelper.addLogEntry("TCP подключение не удалось")
            return false
        }
        AppState.connectionAlive = true

        if (!doHandshake()) return false

        return loginWithToken(token)
    }

    private suspend fun loginWithToken(token: String): Boolean {
        savedToken = token
        AppStateHelper.addLogEntry("Логин по токену...")
        val loginPayload = msgpackMap(
            "userAgent" to userAgentMap(),
            "token" to token,
            "chatsSync" to -1,
            "contactsSync" to -1,
            "draftsSync" to -1,
            "interactive" to true,
            "presenceSync" to -1
        )
        val loginResp = client.request(OP_LOGIN, loginPayload)
        if (!checkError(loginResp, "LOGIN")) return false

        val loginData = unpackMap(loginResp!!.payload, loginResp.flags)
        if (loginData["chats"] == null && loginData["profile"] == null) {
            connectError = "LOGIN не удался"
            return false
        }

        val newToken = loginData["token"] as? String
        if (!newToken.isNullOrEmpty()) {
            savedToken = newToken
            saveToken(newToken)
        }

        AppState.isAuthenticated = true
        AppState.connectionAlive = true
        startPing()
        AppStateHelper.addLogEntry("Вход по токену успешен!")
        return true
    }

    /**
     * Начать аутентификацию по SMS.
     */
    suspend fun startAuth(phone: String): Boolean {
        currentPhone = phone
        AppState.isConnecting = true
        AppState.connectionAlive = false
        connectError = null

        AppStateHelper.addLogEntry("Начинаем подключение к MAX для номера $phone")

        try {
            // 1. TCP connect
            val connected = client.connect()
            if (!connected) {
                connectError = "Не удалось подключиться"
                AppState.isConnecting = false
                return false
            }
            AppState.connectionAlive = true
            AppStateHelper.addLogEntry("TCP подключено")

            // 2. Handshake
            if (!doHandshake()) {
                AppState.isConnecting = false
                return false
            }

            // 3. AUTH_REQUEST — запрос SMS
            AppStateHelper.addLogEntry("Запрос SMS-кода...")
            val authReqPayload = msgpackMap(
                "phone" to phone,
                "type" to AUTH_TYPE_START,
                "language" to "ru"
            )
            val authReqResp = client.request(OP_AUTH_REQUEST, authReqPayload)
            if (!checkError(authReqResp, "AUTH_REQUEST")) {
                AppState.isConnecting = false; return false
            }
            val authReqData = unpackMap(authReqResp!!.payload, authReqResp.flags)
            AppStateHelper.addLogEntry("Ответ AUTH_REQUEST: ${filterSensitive(authReqData)}")
            val authToken = authReqData["token"] as? String
            if (authToken.isNullOrEmpty()) {
                connectError = "Не получен токен авторизации"
                val errStr = String(authReqResp.payload, Charsets.UTF_8)
                AppStateHelper.addLogEntry("Ошибка: нет токена, сырой ответ: ${errStr.take(200)}")
                AppState.isConnecting = false; return false
            }
            AppStateHelper.addLogEntry("SMS-код отправлен")

            // 4. Ждём код от пользователя
            val code = waitForAuthCode()
            if (code.isNullOrEmpty()) {
                connectError = "Код не получен"
                AppState.isConnecting = false; return false
            }

            // 5. AUTH — отправка кода
            AppStateHelper.addLogEntry("Проверка SMS-кода...")
            val authPayload = msgpackMap(
                "token" to authToken,
                "verify_code" to code,
                "authTokenType" to AUTH_TYPE_CHECK_CODE
            )
            val authResp = client.request(OP_AUTH, authPayload)
            if (!checkError(authResp, "AUTH")) {
                AppState.isConnecting = false; return false
            }
            val authData = unpackMap(authResp!!.payload, authResp.flags)
            AppStateHelper.addLogEntry("Ответ AUTH: ${filterSensitive(authData)}")

            // Проверка на 2FA
            val passwordChallenge = authData["passwordChallenge"] as? Map<String, Any?>
            if (passwordChallenge != null) {
                connectError = "Требуется 2FA пароль"
                AppStateHelper.addLogEntry("Требуется 2FA: trackId=${passwordChallenge["trackId"]}")
                AppState.isConnecting = false; return false
            }

            // Извлекаем login token из tokenAttrs.LOGIN
            val tokenAttrs = authData["tokenAttrs"] as? Map<String, Any?>
            val loginField = tokenAttrs?.get("LOGIN") as? Map<String, Any?>
            val loginToken = loginField?.get("token") as? String
                ?: tokenAttrs?.get("login") as? String

            if (loginToken.isNullOrEmpty()) {
                connectError = "Не получен токен входа"
                AppStateHelper.addLogEntry("Ошибка: нет LOGIN токена в ответе AUTH")
                AppState.isConnecting = false; return false
            }
            AppStateHelper.addLogEntry("Токен входа получен")
            savedToken = loginToken
            saveToken(loginToken)

            // 6. LOGIN — вход с токеном
            return loginWithToken(loginToken)
        } catch (e: CancellationException) {
            Log.w(TAG, "startAuth cancelled"); AppState.isConnecting = false; AppState.connectionAlive = false; return false
        } catch (e: Exception) {
            Log.e(TAG, "startAuth: ${e.message}")
            connectError = e.message; AppState.isConnecting = false; AppState.connectionAlive = false; return false
        } finally {
            AppState.isConnecting = false
        }
    }

    fun provideAuthCode(code: String) {
        AppState.provideAuthCode(code)
    }

    private suspend fun waitForAuthCode(): String? {
        if (AppState.authCode != null) return AppState.authCode
        AppState.authEventArrived = false
        var waited = 0
        while (!AppState.authEventArrived && AppState.authCode == null && waited < 1200) {
            delay(100)
            waited++
        }
        return AppState.authCode
    }

    // ─── Подписка на события ──────────────────────────────────────────────

    /**
     * Настраивает reader-loop на приём входящих событий (EVENT-фреймов).
     * Вызывается после успешного LOGIN.
     */
    fun startEventListener() {
        client.onFrame = { frame ->
            if (frame.cmd == MaxTcpClient.CMD_EVENT) {
                scope.launch {
                    handleEvent(frame)
                }
            }
        }
    }

    private suspend fun handleEvent(frame: MaxTcpClient.Frame) {
        try {
            val data = unpackMap(frame.payload, frame.flags)
            Log.d(TAG, "EVENT opcode=${frame.opcode} data=${filterSensitive(data)}")
            when (frame.opcode) {
                OP_PING -> {
                    // Сервер иногда шлёт ping как event — отвечаем
                    client.respond(frame.seq, OP_PING, byteArrayOf())
                }
                // Новое сообщение
                128 -> { // NOTIF_MESSAGE
                    val msg = data["message"] as? Map<String, Any?>
                    if (msg != null) {
                        onMessage?.invoke(msg)
                        val chatId = (msg["chatId"] as? Number)?.toLong()
                            ?: (msg["chat_id"] as? Number)?.toLong()
                        if (chatId != null) {
                            val msgs = AppState.messagesCache.computeIfAbsent(chatId) {
                                java.util.concurrent.CopyOnWriteArrayList()
                            }
                            msgs.add(msg)
                            AppState.newMessages.add(msg)
                        }
                    }
                }
                else -> {
                    Log.d(TAG, "UNHANDLED EVENT opcode=${frame.opcode}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleEvent error: ${e.message}")
        }
    }

    // ─── Heartbeat / Ping ────────────────────────────────────────────────

    /**
     * Пинг как в pymax: отправляем {"interactive": true} каждые 30с.
     */
    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (AppState.isAuthenticated && isActive) {
                delay(30000)
                try {
                    val pongPayload = msgpackMap("interactive" to true)
                    val pong = client.request(OP_PING, pongPayload)
                    if (pong == null) {
                        AppState.connectionAlive = false
                        Log.w(TAG, "Ping timeout")
                        onConnectionLost?.invoke("Ping timeout")
                    }
                } catch (e: Exception) {
                    AppState.connectionAlive = false
                    onConnectionLost?.invoke(e.message)
                }
            }
        }
    }

    // ─── API методы ──────────────────────────────────────────────────────

    suspend fun fetchChats(): List<Map<String, Any?>> {
        val payload = msgpackMap("limit" to 100)
        val resp = client.request(OP_CHATS_LIST, payload) ?: return emptyList()
        if (resp.cmd == MaxTcpClient.CMD_ERROR) {
            AppStateHelper.addLogEntry("fetchChats ERROR: ${String(resp.payload, Charsets.UTF_8)}")
            return emptyList()
        }
        val data = unpackMap(resp.payload, resp.flags)
        val chats = data["chats"] as? List<*> ?: data["CHATS_LIST"] as? List<*>
        return chats?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
    }

    suspend fun fetchHistory(chatId: Long, count: Int = 50): List<Map<String, Any?>> {
        val payload = msgpackMap("chat_id" to chatId, "limit" to count)
        val resp = client.request(OP_CHAT_HISTORY, payload) ?: return emptyList()
        if (resp.cmd == MaxTcpClient.CMD_ERROR) {
            AppStateHelper.addLogEntry("fetchHistory ERROR: ${String(resp.payload, Charsets.UTF_8)}")
            return emptyList()
        }
        val data = unpackMap(resp.payload, resp.flags)
        val msgs = data["messages"] as? List<*> ?: data["CHAT_HISTORY"] as? List<*>
        return msgs?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
    }

    suspend fun sendMessage(chatId: Long, text: String): String? {
        val payload = msgpackMap(
            "chat_id" to chatId,
            "text" to text,
            "type" to "text"
        )
        val resp = client.request(OP_MSG_SEND, payload) ?: return null
        if (resp.cmd == MaxTcpClient.CMD_ERROR) {
            AppStateHelper.addLogEntry("sendMessage ERROR: ${String(resp.payload, Charsets.UTF_8)}")
            return null
        }
        val data = unpackMap(resp.payload, resp.flags)
        return (data["id"] as? Number)?.toString()
    }

    suspend fun searchByPhone(phone: String): Map<String, Any?>? {
        val payload = msgpackMap("phone" to phone)
        val resp = client.request(OP_CONTACT_SEARCH, payload) ?: return null
        if (resp.cmd == MaxTcpClient.CMD_ERROR) return null
        val data = unpackMap(resp.payload, resp.flags)
        return data["user"] as? Map<String, Any?>
    }

    // ─── Токен ───────────────────────────────────────────────────────────

    private fun saveToken(token: String) {
        if (token.isEmpty()) return
        try {
            val prefs = AppState.filesDir.resolve("sessions/token_prefs")
            prefs.parentFile?.mkdirs()
            prefs.writeText(token)
            Log.i(TAG, "Token saved")
        } catch (e: Exception) {
            Log.w(TAG, "saveToken: ${e.message}")
        }
    }

    private fun loadToken(): String? {
        return try {
            val prefs = AppState.filesDir.resolve("sessions/token_prefs")
            if (prefs.exists()) prefs.readText().trim().ifEmpty { null } else null
        } catch (e: Exception) { null }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun filterSensitive(data: Map<String, Any?>): Map<String, Any?> {
        val filtered = linkedMapOf<String, Any?>()
        for ((k, v) in data) {
            filtered[k] = when {
                k.contains("token", ignoreCase = true) && v is String ->
                    v.take(8) + "..."
                k.contains("phone", ignoreCase = true) && v is String ->
                    v.take(5) + "***"
                else -> v
            }
        }
        return filtered
    }

    @Volatile var connectError: String? = null; private set

    // ─── Закрытие ────────────────────────────────────────────────────────

    suspend fun close() {
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        AppState.isAuthenticated = false
        AppState.isConnecting = false
        AppState.connectionAlive = false
        savedToken = null
        scope.cancel()
        try { withTimeout(5000) { client.close() } } catch (_: Exception) {}
    }
}
