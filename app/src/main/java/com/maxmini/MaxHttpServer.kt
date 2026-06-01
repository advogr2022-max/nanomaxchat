package com.maxmini

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * HTTP-сервер (NanoHTTPD) — API для WebView.
 * Порт 8085, слушает только localhost.
 * HTML страницы из assets/www/ (как max-chat).
 */
class MaxHttpServer(private val ctx: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {
    companion object {
        private const val TAG = "MaxHttpServer"
        private const val PORT = 8085
    }

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null

    override fun serve(session: IHTTPSession): Response {
        return try { handleRequest(session) } catch (e: Exception) {
            Log.e(TAG, "serve: ${e.message}")
            jsonResponse(500, mapOf("ok" to false, "error" to "Internal error"))
        }
    }

    private fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Статика из assets/www/
        if (uri == "/" || uri == "/login") return assetsHtml("www/login.html")
        if (uri == "/chat") return assetsHtml("www/chat.html")

        // API
        return when {
            uri == "/api/status" && method == Method.GET -> apiStatus()
            uri == "/api/send-code" && method == Method.POST -> apiSendCode(session)
            uri == "/api/verify-code" && method == Method.POST -> apiVerifyCode(session)
            uri == "/api/verify-password" && method == Method.POST -> apiVerifyPassword(session)
            uri == "/api/resend-code" && method == Method.POST -> apiResendCode()
            uri == "/api/logout" && method == Method.POST -> apiLogout()
            uri == "/api/reconnect" && method == Method.POST -> apiReconnect()
            uri == "/api/chats" && method == Method.GET -> apiChats()
            uri == "/api/me" && method == Method.GET -> apiMe()
            uri.startsWith("/api/messages/") && method == Method.GET -> apiMessages(session)
            uri == "/api/send-message" && method == Method.POST -> apiSendMessage(session)
            uri == "/api/poll" && method == Method.GET -> apiPoll()
            uri == "/api/log" && method == Method.GET -> apiLog(session)
            uri.startsWith("/api/search-phone") && method == Method.GET -> apiSearchPhone(session)
            else -> jsonResponse(404, mapOf("ok" to false, "error" to "Not found"))
        }
    }

    // ─── Статика из assets ─────────────────────────────────────────────

    private fun assetsHtml(path: String): Response {
        return try {
            val input = ctx.assets.open(path)
            val text = input.bufferedReader().use { it.readText() }
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", text)
        } catch (e: Exception) {
            Log.w(TAG, "assets $path not found: ${e.message}")
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    // ─── API: Status ───────────────────────────────────────────────────

    private fun apiStatus(): Response {
        val waitingCode = AppState.isConnecting && !AppState.isAuthenticated
        val now = System.currentTimeMillis() / 1000
        val elapsed = if (AppState.smsSentAt > 0) (now - AppState.smsSentAt).toInt() else 0
        return jsonOk(mapOf(
            "authenticated" to AppState.isAuthenticated,
            "connecting" to AppState.isConnecting,
            "waiting_for_code" to waitingCode,
            "waiting_for_2fa" to false,
            "connection_alive" to AppState.connectionAlive,
            "phone" to (AppState.currentPhone ?: ""),
            "error" to (AppState.connectError ?: ""),
            "sms_elapsed" to elapsed,
            "sms_can_resend" to (elapsed >= 60 && waitingCode),
            "sms_cooldown_remaining" to maxOf(0, 60 - elapsed)
        ))
    }

    // ─── API: Send code ────────────────────────────────────────────────

    private fun apiSendCode(session: IHTTPSession): Response {
        try {
            val data = JSONObject(parseBody(session))
            var phone = data.optString("phone", "").trim()
            if (phone.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Укажите номер"))
            if (!phone.startsWith("+")) phone = "+$phone"
            if (AppState.isConnecting) return jsonResponse(409, mapOf("ok" to false, "error" to "Уже выполняется подключение"))

            AppState.resetAuth()
            AppState.currentPhone = phone
            AppState.isConnecting = true
            AppState.connectError = null
            AppState.resetAuthEvent()
            AppState.smsSentAt = System.currentTimeMillis() / 1000
            AppState.connLog("Начинаем подключение к MAX для номера $phone")

            connectJob?.cancel()
            connectJob = serverScope.launch { doConnect(phone) }
            return jsonOk(mapOf("message" to "Подключение запущено"))
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный JSON"))
        }
    }

    private fun apiResendCode(): Response {
        if (!AppState.isConnecting) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Нет активного подключения"))
        }
        val phone = AppState.currentPhone ?: return jsonResponse(400, mapOf("ok" to false, "error" to "Нет номера"))
        AppState.connLog("Повторный запрос кода...")
        connectJob?.cancel()
        AppState.resetAuth()
        AppState.isConnecting = true
        AppState.currentPhone = phone
        AppState.smsSentAt = System.currentTimeMillis() / 1000
        connectJob = serverScope.launch { doConnect(phone) }
        return jsonOk(mapOf("message" to "Повторная отправка запущена"))
    }

    // ─── Подключение к MAX ─────────────────────────────────────────────

    private suspend fun doConnect(phone: String) {
        try {
            val client = MaxTcpClient()
            val protocol = MaxProtocol(client)
            AppState.protocol = protocol

            protocol.onAuthenticated = { token ->
                AppState.isAuthenticated = true
                AppState.isConnecting = false
                AppState.connectionAlive = true
                AppState.connLog("Авторизация успешна!")
                protocol.startEventListener()
            }
            protocol.onConnectionLost = { cause ->
                AppState.connectionAlive = false
                AppState.connLogError("Соединение потеряно: $cause")
            }
            protocol.onMessage = { msg ->
                AppState.newMessages.add(msg)
            }

            val ok = protocol.tryLoginByToken() || protocol.startAuth(phone)
            if (!ok) {
                AppState.isConnecting = false
                AppState.connectionAlive = false
                AppState.connectError = protocol.connectError
                AppState.connLogError("Ошибка авторизации: ${protocol.connectError}")
            }
        } catch (e: CancellationException) {
            AppState.connLog("Подключение отменено")
        } catch (e: Exception) {
            AppState.isConnecting = false
            AppState.isAuthenticated = false
            AppState.connectionAlive = false
            AppState.connectError = e.message
            AppState.connLogError("Ошибка: ${e.message}")
        }
    }

    // ─── API: Verify code ──────────────────────────────────────────────

    private fun apiVerifyCode(session: IHTTPSession): Response {
        try {
            val data = JSONObject(parseBody(session))
            val code = data.optString("code", "").trim()
            if (code.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Укажите код"))

            AppState.connLog("SMS-код получен, передача протоколу...")
            AppState.provideAuthCode(code)

            return jsonOk(mapOf("message" to "Код передан, ожидайте авторизацию"))
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный JSON"))
        }
    }

    // ─── API: Verify password (2FA) ────────────────────────────────────

    private fun apiVerifyPassword(session: IHTTPSession): Response {
        try {
            val data = JSONObject(parseBody(session))
            val password = data.optString("password", "").trim()
            if (password.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Укажите пароль"))
            AppState.connLog("2FA пароль получен (пока не реализовано)")
            return jsonResponse(501, mapOf("ok" to false, "error" to "2FA не поддерживается"))
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный JSON"))
        }
    }

    // ─── API: Logout ───────────────────────────────────────────────────

    private fun apiLogout(): Response {
        connectJob?.cancel()
        connectJob = null
        serverScope.launch { AppState.protocol?.close() }
        AppState.resetAuth()
        AppState.protocol = null
        AppState.connLog("Выход выполнен")
        return jsonOk(emptyMap())
    }

    // ─── API: Reconnect ────────────────────────────────────────────────

    private fun apiReconnect(): Response {
        val phone = AppState.currentPhone ?: return jsonResponse(400, mapOf("ok" to false, "error" to "Нет номера"))
        connectJob?.cancel()
        AppState.resetAuth()
        AppState.currentPhone = phone
        AppState.isConnecting = true
        connectJob = serverScope.launch { doConnect(phone) }
        return jsonOk(mapOf("message" to "Переподключение..."))
    }

    // ─── API: Me ───────────────────────────────────────────────────────

    private fun apiMe(): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        // Простейший профиль — берём из первого чата
        val phone = AppState.currentPhone ?: ""
        return jsonOk(mapOf("user" to mapOf(
            "id" to 0,
            "name" to "Пользователь",
            "phone" to phone,
            "avatar_url" to ""
        )))
    }

    // ─── API: Chats ────────────────────────────────────────────────────

    private fun apiChats(): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        val rawChats = AppState.chatsCache.toList()
        // Нормализуем чаты: добавляем name fallback для DIALOG без названия
        val chats = rawChats.map { chat ->
            val map = chat.toMutableMap()
            if (map["title"] !is String || (map["title"] as? String).orEmpty().isEmpty()) {
                val type = map["type"] as? String ?: ""
                val id = map["id"] ?: map["chat_id"] ?: 0
                if (type == "DIALOG") {
                    val participants = map["participants"] as? Map<*, *>
                    if (participants != null && participants.isNotEmpty()) {
                        val otherId = participants.keys.firstOrNull { it.toString() != "0" }
                        map["title"] = "Диалог #${otherId ?: id}"
                        map["name"] = "Диалог #${otherId ?: id}"
                    } else {
                        map["title"] = "Диалог #$id"
                        map["name"] = "Диалог #$id"
                    }
                } else {
                    map["title"] = "Чат #$id"
                    map["name"] = "Чат #$id"
                }
            }
            map
        }
        return jsonOk(mapOf("chats" to chats))
    }

    // ─── API: Messages ─────────────────────────────────────────────────

    private fun apiMessages(session: IHTTPSession): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        val chatId = session.uri.removePrefix("/api/messages/").split("/").first().toLongOrNull()
            ?: return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный chat_id"))

        val cached = AppState.messagesCache[chatId]
        if (cached != null && cached.isNotEmpty()) {
            return jsonOk(mapOf("messages" to cached.toList()))
        }

        val protocol = AppState.protocol
        if (protocol != null && AppState.isAuthenticated) {
            serverScope.launch {
                try {
                    val msgs = protocol.fetchHistory(chatId)
                    val list = AppState.messagesCache.computeIfAbsent(chatId) {
                        java.util.concurrent.CopyOnWriteArrayList()
                    }
                    list.clear()
                    list.addAll(msgs)
                } catch (e: Exception) {
                    AppState.connLogError("Ошибка загрузки истории: ${e.message}")
                }
            }
        }

        return jsonOk(mapOf("messages" to (cached?.toList() ?: emptyList<Any>())))
    }

    // ─── API: Send message ─────────────────────────────────────────────

    private fun apiSendMessage(session: IHTTPSession): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        try {
            val data = JSONObject(parseBody(session))
            val chatId = data.optLong("chat_id", -1)
            val text = data.optString("text", "").trim()
            if (chatId < 0 || text.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Неверные параметры"))

            val protocol = AppState.protocol
            if (protocol == null) return jsonResponse(503, mapOf("ok" to false, "error" to "Нет соединения"))

            serverScope.launch {
                try {
                    protocol.sendMessage(chatId, text)
                    AppState.connLog("Сообщение отправлено в чат $chatId")
                } catch (e: Exception) {
                    AppState.connLogError("Ошибка отправки: ${e.message}")
                }
            }
            return jsonOk(mapOf("message" to "Отправляется"))
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный JSON"))
        }
    }

    // ─── API: Poll ─────────────────────────────────────────────────────

    private fun apiPoll(): Response {
        val msgs = AppState.newMessages.toList()
        AppState.newMessages.clear()
        return jsonOk(mapOf("messages" to msgs))
    }

    // ─── API: Log ──────────────────────────────────────────────────────

    private fun apiLog(session: IHTTPSession): Response {
        val after = session.parameters.getOrDefault("after", listOf("0")).first().toIntOrNull() ?: 0
        val log = AppState.connLog
        return jsonOk(mapOf(
            "log" to (if (after < log.size) log.subList(after, log.size) else emptyList<String>()),
            "total" to log.size
        ))
    }

    // ─── API: Search phone ─────────────────────────────────────────────

    private fun apiSearchPhone(session: IHTTPSession): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        val phone = session.parameters.getOrDefault("phone", listOf("")).first().trim()
        if (phone.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Укажите номер"))

        // Поиск через протокол
        val protocol = AppState.protocol
        if (protocol == null) return jsonResponse(503, mapOf("ok" to false, "error" to "Нет соединения"))

        return try {
            val user = runBlocking { protocol.searchByPhone(phone) }
            if (user != null) {
                val userId = (user["id"] as? Number)?.toLong() ?: 0
                val name = (user["name"] as? String) ?: (user["firstName"] as? String) ?: phone
                val chatId = userId  // Для личного диалога chat_id = user_id
                jsonOk(mapOf("found" to true, "user" to mapOf(
                    "id" to userId, "name" to name, "phone" to phone, "chat_id" to chatId
                )))
            } else {
                jsonOk(mapOf("found" to false, "message" to "$phone не найден"))
            }
        } catch (e: Exception) {
            jsonOk(mapOf("found" to false, "message" to "Ошибка: ${e.message}"))
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun htmlResponse(html: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun jsonOk(data: Map<String, Any?>): Response {
        return jsonResponse(200, mapOf("ok" to true) + data)
    }

    private fun jsonResponse(status: Int, data: Map<String, Any?>): Response {
        val json = JSONObject()
        for ((k, v) in data) {
            when (v) {
                null -> json.put(k, JSONObject.NULL)
                is String -> json.put(k, v)
                is Number -> json.put(k, v)
                is Boolean -> json.put(k, v)
                is ByteArray -> json.put(k, v.joinToString("") { "%02x".format(it) })
                is List<*> -> json.put(k, jsonArray(v))
                is Map<*, *> -> {
                    try {
                        json.put(k, JSONObject(v as Map<String?, Any?>))
                    } catch (e: Exception) {
                        val safe = LinkedHashMap<String, Any?>()
                        for ((mk, mv) in v) safe[mk.toString()] = mv
                        json.put(k, JSONObject(safe))
                    }
                }
                else -> json.put(k, v.toString())
            }
        }
        return newFixedLengthResponse(Response.Status.lookup(status), "application/json; charset=utf-8", json.toString())
    }

    private fun jsonArray(list: List<*>): JSONArray {
        val arr = JSONArray()
        for (item in list) {
            when (item) {
                null -> arr.put(JSONObject.NULL)
                is String -> arr.put(item)
                is Number -> arr.put(item)
                is Boolean -> arr.put(item)
                is ByteArray -> arr.put(item.joinToString("") { "%02x".format(it) })
                is List<*> -> arr.put(jsonArray(item))
                is Map<*, *> -> {
                    try {
                        arr.put(JSONObject(item as Map<String?, Any?>))
                    } catch (e: Exception) {
                        val safe = LinkedHashMap<String, Any?>()
                        for ((mk, mv) in item) safe[mk.toString()] = mv
                        arr.put(JSONObject(safe))
                    }
                }
                else -> arr.put(item.toString())
            }
        }
        return arr
    }

    override fun stop() {
        connectJob?.cancel()
        serverScope.launch { AppState.protocol?.close() }
        super.stop()
    }
}
