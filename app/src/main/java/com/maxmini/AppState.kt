package com.maxmini

import android.util.Log
import org.msgpack.core.MessagePack
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Глобальное состояние приложения.
 * Все поля, читаемые/записываемые из разных потоков — @Volatile.
 * Коллекции — thread-safe (CopyOnWriteArrayList, ConcurrentHashMap).
 */
object AppState {
    @Volatile var isAuthenticated: Boolean = false
    @Volatile var isConnecting: Boolean = false
    @Volatile var connectionAlive: Boolean = false
    @Volatile var connectError: String? = null
    @Volatile var currentPhone: String? = null
    @Volatile var smsSentAt: Long = 0
    @Volatile var protocol: MaxProtocol? = null

    // Thread-safe коллекции
    val chatsCache = CopyOnWriteArrayList<Map<String, Any?>>()
    val messagesCache = ConcurrentHashMap<Long, CopyOnWriteArrayList<Map<String, Any?>>>()
    val newMessages = CopyOnWriteArrayList<Map<String, Any?>>()

    // Пути
    lateinit var filesDir: File
    var deviceId: String = "android"

    // Лог
    private val _connLog = CopyOnWriteArrayList<String>()
    val connLog: List<String> get() = _connLog.toList()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val timeMsFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun init(filesDir: File, deviceId: String) {
        this.filesDir = filesDir
        this.deviceId = deviceId
        File(filesDir, "sessions").mkdirs()
        File(filesDir, "log").mkdirs()
        connLog("AppState инициализирован")
    }

    fun connLog(msg: String) {
        val ts = LocalTime.now().format(timeFormatter)
        _connLog.add("[$ts] [INFO] $msg")
        if (_connLog.size > 500) _connLog.removeAt(0)
        Log.i("AppState", msg)
        fileLog(msg, "INFO")
    }

    fun connLogError(msg: String) {
        val ts = LocalTime.now().format(timeFormatter)
        _connLog.add("[$ts] [ERROR] $msg")
        if (_connLog.size > 500) _connLog.removeAt(0)
        Log.e("AppState", msg)
        fileLog(msg, "ERROR")
    }

    // Make internal for AppStateHelper
    internal fun fileLog(msg: String, level: String = "INFO") {
        try {
            val today = LocalDate.now().format(dateFormatter)
            val file = File(filesDir, "log/chat_$today.log")
            val fw = java.io.FileWriter(file, true)
            val ts = LocalTime.now().format(timeMsFormatter)
            fw.write("[$ts] [$level] $msg\n")
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
    fun fileLog(msg: String) = AppState.fileLog(msg, "FILE")
}
