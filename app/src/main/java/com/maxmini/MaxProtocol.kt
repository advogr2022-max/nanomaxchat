package com.maxmini

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.msgpack.core.MessagePack
import org.msgpack.value.Value
import org.msgpack.value.ValueFactory
import java.io.File

/**
 * Протокол MAX — правильная реализация с msgpack.
 *
 * Opcode из PyMax:
 *   SESSION_INIT = 6
 *   AUTH_REQUEST = 17
 *   AUTH = 18
 *   LOGIN = 19
 *   CONTACT_SEARCH = 37
 *   CHAT_HISTORY = 49
 *   CHATS_LIST = 53
 *   MSG_SEND = 64
 *   FILE_DOWNLOAD = 88
 *   PING = 1
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

        // AuthType (из PyMax)
        const val AUTH_TYPE_START = 0
        const val AUTH_TYPE_CHECK_CODE = 1
    }

    // ─── Состояние (volatile для thread-safety) ──────────────────────────
    @Volatile var isAuthenticated: Boolean = false; private set
    @Volatile var isConnecting: Boolean = false; private set
    @Volatile var connectionAlive: Boolean = false; private set
    @Volatile var connectError: String? = null; private set

    @Volatile private var authCode: String? = null
    @Volatile private var authEventArrived: Boolean = false

    // Токен
    private var savedToken: String? = null
    private var currentPhone: String? = null

    // Сессия
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks
    var onAuthenticated: ((token: String) -> Unit)? = null
    var onMessage: ((Map<String, Any?>) -> Unit)? = null
    var onConnectionLost: ((String?) -> Unit)? = null

    // Ping task
    private var pingJob: Job? = null

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

    private fun unpackMap(data: ByteArray): Map<String, Any?> {
        if (data.isEmpty()) return emptyMap()
        try {
            val unpacker = MessagePack.newDefaultUnpacker(data)
            val value = unpacker.unpackValue()
            val map = value.asMapValue()
            val result = linkedMapOf<String, Any?>()
            for ((k, v) in map.entrySet()) {
                // Ключи могут быть строками или байтами (как в PyMax без use_bin_type)
                val key = when (k.valueType) {
                    org.msgpack.value.ValueType.STRING -> k.asStringValue().asString()
                    org.msgpack.value.ValueType.BINARY -> k.asBinaryValue().asByteArray().decodeToString()
                    org.msgpack.value.ValueType.INTEGER -> k.asIntegerValue().toLong().toString()
                    else -> k.toString()
                }
                result[key] = valueToAny(v)
            }
            return result
        } catch (e: Exception) {
            Log.w(TAG, "unpackMap error: ${e.message}")
            return emptyMap()
        }
    }

    private fun valueToAny(v: Value): Any? {
        return when (v.valueType) {
            org.msgpack.value.ValueType.NIL -> null
            org.msgpack.value.ValueType.BOOLEAN -> v.asBooleanValue().getBoolean()
            org.msgpack.value.ValueType.INTEGER -> v.asIntegerValue().toLong()
            org.msgpack.value.ValueType.FLOAT -> v.asFloatValue().toDouble()
            org.msgpack.value.ValueType.STRING -> v.asStringValue().asString()
            org.msgpack.value.ValueType.BINARY -> v.asBinaryValue().asByteArray().decodeToString()
            org.msgpack.value.ValueType.ARRAY -> {
                val arr = v.asArrayValue()
                (0 until arr.size()).map { valueToAny(arr.get(it)) }
            }
            org.msgpack.value.ValueType.MAP -> {
                val m = v.asMapValue()
                val result = linkedMapOf<String, Any?>()
                for ((k, v2) in m.entrySet()) {
                    val key = when (k.valueType) {
                        org.msgpack.value.ValueType.STRING -> k.asStringValue().asString()
                        org.msgpack.value.ValueType.BINARY -> k.asBinaryValue().asByteArray().decodeToString()
                        org.msgpack.value.ValueType.INTEGER -> k.asIntegerValue().toLong().toString()
                        else -> k.toString()
                    }
                    result[key] = valueToAny(v2)
                }
                result
            }
            else -> v.toString()
        }
    }

    // ─── Основной API ────────────────────────────────────────────────────

    /**
     * Начать аутентификацию по SMS.
     *
     * Flow (из PyMax):
     * 1. SESSION_INIT (opcode=6) — handshake с userAgent
     * 2. AUTH_REQUEST (opcode=17) — запрос SMS: {phone, type:0, language:"ru"}
     *    Ответ: {token, codeLength, requestMaxDuration, ...}
     * 3. AUTH (opcode=18) — отправка кода: {token, verify_code, authTokenType:1}
     *    Ответ: {tokenAttrs:{LOGIN:{token:"..."}}} или {passwordChallenge:{trackId, hint}}
     * 4. LOGIN (opcode=19) — логин: {userAgent, token, chatsSync:-1, ...}
     *    Ответ: {chats, profile, messages, token, ...}
     */
    suspend fun startAuth(phone: String): Boolean {
        currentPhone = phone
        isConnecting = true
        connectionAlive = false
        connectError = null
        authCode = null
        authEventArrived = false

        Log.i(TAG, "startAuth: $phone")
        AppStateHelper.addLogEntry("Начинаем подключение к MAX для номера $phone")

        try {
            // 1. TCP connect
            val connected = client.connect()
            if (!connected) {
                connectError = "Не удалось подключиться"
                isConnecting = false
                return false
            }
            connectionAlive = true
            AppStateHelper.addLogEntry("TCP подключено")

            // 2. SESSION_INIT — handshake
            AppStateHelper.addLogEntry("Handshake...")
            val handshakePayload = msgpackMap(
                "mt_instanceid" to "",
                "userAgent" to mapOf(
                    "deviceType" to "android",
                    "appVersion" to "2.1.1",
                    "osVersion" to android.os.Build.VERSION.RELEASE,
                    "timezone" to "Europe/Moscow",
                    "screen" to "1080x1920",
                    "locale" to "ru",
                    "deviceLocale" to "ru",
                    "deviceName" to android.os.Build.MODEL,
                    "headerUserAgent" to "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) AppleWebKit/537.36"
                ),
                "clientSessionId" to 42,
                "deviceId" to AppStateHelper.deviceId
            )
            val hsResp = client.request(OP_SESSION_INIT, handshakePayload)
            if (hsResp == null) {
                connectError = "Нет ответа на handshake"
                isConnecting = false; return false
            }
            AppStateHelper.addLogEntry("Handshake OK")

            // 3. AUTH_REQUEST — запрос SMS
            AppStateHelper.addLogEntry("Запрос SMS-кода...")
            val authReqPayload = msgpackMap(
                "phone" to phone,
                "type" to AUTH_TYPE_START,
                "language" to "ru"
            )
            val authReqResp = client.request(OP_AUTH_REQUEST, authReqPayload) ?: run {
                connectError = "Нет ответа на запрос SMS"
                isConnecting = false; return false
            }
            val authReqData = unpackMap(authReqResp.payload)
            AppStateHelper.addLogEntry("Ответ AUTH_REQUEST: $authReqData")
            val authToken = authReqData["token"] as? String
            if (authToken.isNullOrEmpty()) {
                connectError = "Не получен токен авторизации"
                val errStr = String(authReqResp.payload, Charsets.UTF_8)
                AppStateHelper.addLogEntry("Ошибка: нет токена, сырой ответ: ${errStr.take(200)}")
                isConnecting = false; return false
            }
            AppStateHelper.addLogEntry("SMS-код отправлен, токен: ${authToken.take(8)}...")

            // 4. Ждём код от пользователя
            val code = waitForAuthCode()
            if (code.isNullOrEmpty()) {
                connectError = "Код не получен"
                isConnecting = false; return false
            }

            // 5. AUTH — отправка кода
            AppStateHelper.addLogEntry("Проверка SMS-кода...")
            val authPayload = msgpackMap(
                "token" to authToken,
                "verify_code" to code,
                "authTokenType" to AUTH_TYPE_CHECK_CODE
            )
            val authResp = client.request(OP_AUTH, authPayload) ?: run {
                connectError = "Нет ответа на проверку кода"
                isConnecting = false; return false
            }
            val authData = unpackMap(authResp.payload)
            AppStateHelper.addLogEntry("Ответ AUTH: $authData")

            // Проверка на 2FA
            val passwordChallenge = authData["passwordChallenge"] as? Map<String, Any?>
            if (passwordChallenge != null) {
                connectError = "Требуется 2FA пароль"
                AppStateHelper.addLogEntry("Требуется 2FA: trackId=${passwordChallenge["trackId"]}")
                isConnecting = false; return false
            }

            // Извлекаем login token из tokenAttrs.LOGIN
            val tokenAttrs = authData["tokenAttrs"] as? Map<String, Any?>
            val loginField = tokenAttrs?.get("LOGIN") as? Map<String, Any?>
            val loginToken = loginField?.get("token") as? String
                ?: tokenAttrs?.get("login") as? String

            if (loginToken.isNullOrEmpty()) {
                connectError = "Не получен токен входа"
                AppStateHelper.addLogEntry("Ошибка: нет LOGIN токена в ответе AUTH")
                isConnecting = false; return false
            }
            AppStateHelper.addLogEntry("Токен входа получен")
            savedToken = loginToken
            saveToken(loginToken)

            // 6. LOGIN — вход с токеном
            AppStateHelper.addLogEntry("Логин...")
            val loginPayload = msgpackMap(
                "userAgent" to mapOf(
                    "deviceType" to "android",
                    "appVersion" to "2.1.1",
                    "osVersion" to android.os.Build.VERSION.RELEASE,
                    "timezone" to "Europe/Moscow",
                    "screen" to "1080x1920",
                    "locale" to "ru",
                    "deviceLocale" to "ru",
                    "deviceName" to android.os.Build.MODEL
                ),
                "token" to loginToken,
                "chatsSync" to -1,
                "contactsSync" to -1,
                "draftsSync" to -1,
                "interactive" to true,
                "presenceSync" to -1
            )
            val loginResp = client.request(OP_LOGIN, loginPayload) ?: run {
                // LOGIN может вернуть новый токен в ответе
                AppStateHelper.addLogEntry("LOGIN OK (без деталей)")
                isAuthenticated = true
                isConnecting = false
                connectionAlive = true
                startPing()
                onAuthenticated?.invoke(loginToken)
                return true
            }
            val loginData = unpackMap(loginResp.payload)
            AppStateHelper.addLogEntry("LOGIN успешен, чатов: ${(loginData["chats"] as? List<*>)?.size ?: 0}")

            // Обновляем токен из ответа LOGIN если есть
            val newToken = loginData["token"] as? String
            if (!newToken.isNullOrEmpty()) {
                savedToken = newToken
                saveToken(newToken)
            }

            isAuthenticated = true
            isConnecting = false
            connectionAlive = true
            startPing()
            onAuthenticated?.invoke(savedToken ?: loginToken)
            AppStateHelper.addLogEntry("Авторизация успешна!")
            return true

        } catch (e: CancellationException) {
            Log.w(TAG, "startAuth cancelled"); isConnecting = false; connectionAlive = false; return false
        } catch (e: Exception) {
            Log.e(TAG, "startAuth: ${e.message}")
            connectError = e.message; isConnecting = false; connectionAlive = false; return false
        } finally {
            isConnecting = false
        }
    }

    private suspend fun loginByToken(token: String): Boolean {
        // Пробуем LOGIN с сохранённым токеном
        AppStateHelper.addLogEntry("Пробуем вход по токену...")
        val payload = msgpackMap(
            "userAgent" to mapOf(
                "deviceType" to "android",
                "appVersion" to "2.1.1",
                "osVersion" to android.os.Build.VERSION.RELEASE,
                "timezone" to "Europe/Moscow",
                "screen" to "1080x1920",
                "locale" to "ru",
                "deviceLocale" to "ru",
                "deviceName" to android.os.Build.MODEL
            ),
            "token" to token,
            "chatsSync" to -1,
            "contactsSync" to -1,
            "draftsSync" to -1,
            "interactive" to true,
            "presenceSync" to -1
        )
        val resp = client.request(OP_LOGIN, payload) ?: return false
        val data = unpackMap(resp.payload)
        val newToken = data["token"] as? String
        if (!newToken.isNullOrEmpty()) {
            savedToken = newToken; saveToken(newToken)
        }
        val ok = data["chats"] != null || data["profile"] != null
        if (ok) {
            savedToken = newToken ?: token
            if (newToken != null) saveToken(newToken)
        }
        return ok
    }

    /**
     * Вызывается из HTTP handler когда пользователь ввёл SMS-код.
     */
    fun provideAuthCode(code: String) {
        authCode = code
        authEventArrived = true
    }

    private suspend fun waitForAuthCode(): String? {
        if (authCode != null) return authCode
        authEventArrived = false
        var waited = 0
        while (!authEventArrived && authCode == null && waited < 1200) {
            delay(100)
            waited++
        }
        return authCode
    }

    // ─── Heartbeat / Ping ────────────────────────────────────────────────

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isAuthenticated && isActive) {
                delay(30000)
                try {
                    val pong = client.request(OP_PING, byteArrayOf())
                    if (pong == null) {
                        connectionAlive = false
                        Log.w(TAG, "Ping timeout")
                        onConnectionLost?.invoke("Ping timeout")
                    }
                } catch (e: Exception) {
                    connectionAlive = false
                    onConnectionLost?.invoke(e.message)
                }
            }
        }
    }

    // ─── API методы ──────────────────────────────────────────────────────

    suspend fun fetchChats(): List<Map<String, Any?>> {
        val payload = msgpackMap("limit" to 100)
        val resp = client.request(OP_CHATS_LIST, payload) ?: return emptyList()
        val data = unpackMap(resp.payload)
        val chats = data["chats"] as? List<*> ?: data["CHATS_LIST"] as? List<*>
        return chats?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
    }

    suspend fun fetchHistory(chatId: Long, count: Int = 50): List<Map<String, Any?>> {
        val payload = msgpackMap("chat_id" to chatId, "limit" to count)
        val resp = client.request(OP_CHAT_HISTORY, payload) ?: return emptyList()
        val data = unpackMap(resp.payload)
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
        val data = unpackMap(resp.payload)
        return (data["id"] as? Number)?.toString()
    }

    suspend fun searchByPhone(phone: String): Map<String, Any?>? {
        val payload = msgpackMap("phone" to phone)
        val resp = client.request(OP_CONTACT_SEARCH, payload) ?: return null
        val data = unpackMap(resp.payload)
        return data["user"] as? Map<String, Any?>
    }

    // ─── Токен ───────────────────────────────────────────────────────────

    private fun saveToken(token: String) {
        if (token.isEmpty()) return
        try {
            val file = File(AppStateHelper.filesDir, "sessions")
            file.mkdirs()
            val tokenFile = File(file, "token_${currentPhone?.replace("+", "") ?: "unknown"}.txt")
            tokenFile.writeText(token)
            Log.i(TAG, "Token saved")
        } catch (e: Exception) {
            Log.w(TAG, "saveToken: ${e.message}")
        }
    }

    private fun loadToken(): String? {
        val phone = currentPhone ?: return null
        return try {
            val tokenFile = File(AppStateHelper.filesDir, "sessions/token_${phone.replace("+", "")}.txt")
            if (tokenFile.exists()) tokenFile.readText().trim().ifEmpty { null } else null
        } catch (e: Exception) { null }
    }

    // ─── Закрытие ────────────────────────────────────────────────────────

    suspend fun close() {
        pingJob?.cancel()
        pingJob = null
        isAuthenticated = false
        isConnecting = false
        connectionAlive = false
        savedToken = null
        try { withTimeout(5000) { client.close() } } catch (_: Exception) {}
    }
}
