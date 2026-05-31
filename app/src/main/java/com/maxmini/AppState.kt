package com.maxmini

import android.util.Log
import org.msgpack.core.MessagePack
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Глобальное состояние приложения.
 * Все поля, читаемые/записываемые из разных потоков — @Volatile.
 */
object AppState {
    @Volatile var isAuthenticated: Boolean = false
    @Volatile var isConnecting: Boolean = false
    @Volatile var connectionAlive: Boolean = false
    @Volatile var connectError: String? = null
    @Volatile var currentPhone: String? = null
    @Volatile var smsSentAt: Long = 0
    @Volatile var protocol: MaxProtocol? = null

    // Кэш
    val chatsCache = mutableListOf<Map<String, Any?>>()
    val messagesCache = mutableMapOf<Long, MutableList<Map<String, Any?>>>()
    val newMessages = mutableListOf<Map<String, Any?>>()

    // Пути
    lateinit var filesDir: File
    var deviceId: String = "android"

    // Лог
    private val _connLog = mutableListOf<String>()
    val connLog: List<String> get() = _connLog.toList()

    fun init(filesDir: File, deviceId: String) {
        this.filesDir = filesDir
        this.deviceId = deviceId
        File(filesDir, "sessions").mkdirs()
        File(filesDir, "log").mkdirs()
        connLog("AppState инициализирован")
    }

    fun connLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _connLog.add("[$ts] [INFO] $msg")
        if (_connLog.size > 500) _connLog.removeAt(0)
        Log.i("AppState", msg)
        fileLog(msg)
    }

    fun connLogError(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _connLog.add("[$ts] [ERROR] $msg")
        if (_connLog.size > 500) _connLog.removeAt(0)
        Log.e("AppState", msg)
        fileLog("ERROR: $msg")
    }

    private fun fileLog(msg: String) {
        try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(filesDir, "log/chat_$today.log")
            val fw = java.io.FileWriter(file, true)
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            fw.write("[$ts] [INFO] $msg\n")
            fw.close()
        } catch (_: Exception) {}
    }

    fun resetAuth() {
        isAuthenticated = false
        isConnecting = false
        connectionAlive = false
        connectError = null
        currentPhone = null
        authCode = null
        authEventArrived = false
        smsSentAt = 0
        chatsCache.clear()
        messagesCache.clear()
        newMessages.clear()
    }

    // Для передачи authCode между потоками
    @Volatile var authCode: String? = null
    @Volatile var authEventArrived: Boolean = false

    fun provideAuthCode(code: String) {
        authCode = code
        authEventArrived = true
    }

    fun resetAuthEvent() {
        authCode = null
        authEventArrived = false
    }
}

/**
 * Хелпер для обратной совместимости.
 */
object AppStateHelper {
    var connectionAlive: Boolean
        get() = AppState.connectionAlive
        set(v) { AppState.connectionAlive = v }

    val filesDir: File get() = AppState.filesDir
    val deviceId: String get() = AppState.deviceId
    fun addLogEntry(msg: String) = AppState.connLog(msg)
    fun fileLog(msg: String) = AppState.connLog(msg)
}
