# NanoMaxChat — Code Review & TODO

## Описание проекта

**NanoMaxChat (MAX Mini)** — минимальный нативный Android-клиент для мессенджера MAX. Приложение устанавливает прямое TCP+TLS+msgpack соединение с серверами MAX (`api.oneme.ru:443`), без промежуточных Python-прокси. Локальный HTTP-сервер (NanoHTTPD) на порту 8085 обслуживает WebView-интерфейс для авторизации и чата.

### Архитектура

```
┌──────────────┐     HTTP :8085      ┌────────────────┐     TCP+TLS+msgpack     ┌──────────────┐
│   WebView    │ ◄──────────────────► │ MaxHttpServer  │ ◄──────────────────────► │ api.oneme.ru │
│  (UI/JS)     │    JSON API         │  (NanoHTTPD)   │       :443              │   (MAX)      │
└──────────────┘                     └───────┬────────┘                         └──────────────┘
                                             │
                                     ┌───────▼────────┐
                                     │   MaxProtocol   │
                                     │  (auth/chats)   │
                                     └───────┬────────┘
                                             │
                                     ┌───────▼────────┐
                                     │  MaxTcpClient   │
                                     │  (TCP framing)  │
                                     └────────────────┘
```

### Стек технологий

- **Kotlin** (Android, minSdk 26, targetSdk 34)
- **NanoHTTPD 2.3.1** — встраиваемый HTTP-сервер
- **msgpack-core 0.9.3** — сериализация протокола MAX
- **kotlinx-coroutines-android 1.7.3** — асинхронность
- **AndroidX AppCompat** — UI-основка
- **ProGuard** — обфускация/оптимизация (отключена в текущей конфигурации)

### Структура файлов

```
app/src/main/java/com/maxmini/
├── AppState.kt        — Глобальное mutable-состояние приложения (синглтон)
├── MaxTcpClient.kt    — TCP+TLS клиент: фрейминг, чтение/запись, реконнект
├── MaxProtocol.kt     — Высокоуровневый протокол MAX: auth, chats, messages, ping
├── MaxHttpServer.kt   — NanoHTTPD: REST API для WebView, HTML-страницы
├── MainActivity.kt    — WebView-Activity + запуск ForegroundService
└── MaxService.kt      — Foreground-сервис для удержания процесса
```

---

## Инструкция по сборке и запуску

### Предварительные требования

1. **Android SDK** с установленными:
   - Android 34 (Upside Down Cake) Platform
   - Android SDK Build-Tools 34
   - Android SDK Platform-Tools
2. **JDK 17** (обязательно — `compileOptions` и `kotlinOptions` указывают Java 17)
3. **Gradle 8.2** (обёртка `gradlew` включена в репозиторий)

### Переменные окружения

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export JAVA_HOME=/path/to/jdk-17
```

### Сборка APK

```bash
# Debug-сборка (~12 MB)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release-сборка (~4 MB при включённом ProGuard)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

### Установка на устройство

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Запуск

1. Установить APK на Android-устройство (arm64-v8a)
2. Приложение запустит ForegroundService и HTTP-сервер на `127.0.0.1:8085`
3. В WebView автоматически откроется страница авторизации
4. Ввести номер телефона → получить SMS → ввести код
5. После авторизации — переход на `/chat` (пока заглушка)

---

## Найденные проблемы (Code Review)

### 🔴 КРИТИЧЕСКИЕ (баги / безопасность)

#### 1. Trust-all SSL — отключена проверка сертификатов
**Файл:** `MaxTcpClient.kt:113-123`

```kotlin
private fun createSslSocket(): SSLSocket {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(...) {}
        override fun checkServerTrusted(...) {}  // ← ПРИНИМАЕТ ЛЮБОЙ СЕРТИФИКАТ
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    ...
}
```

**Проблема:** Приложение полностью отключает верификацию TLS-сертификатов. Это делает возможной MITM-атаку (Man-In-The-Middle): злоумышленник может перехватить трафик, включая токены авторизации и SMS-коды.

**Решение:** Использовать системный `TrustManager` по умолчанию. Если сервер использует самоподписанный сертификат — реализовать certificate pinning или добавить конкретный CA в truststore.

```kotlin
private fun createSslSocket(): SSLSocket {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, null, SecureRandom()) // системные доверенные CA
    return sslContext.socketFactory.createSocket() as SSLSocket
}
```

---

#### 2. `runBlocking` в HTTP-обработчиках — блокировка потока NanoHTTPD
**Файлы:** `MaxHttpServer.kt:213, 236, 319`

```kotlin
// apiVerifyCode — блокирует поток на 120 секунд
runBlocking { protocol.provideAuthCode(code) }  // строка 213

// apiLogout
runBlocking { AppState.protocol?.close() }  // строка 236

// stop()
runBlocking { AppState.protocol?.close() }  // строка 319
```

**Проблема:** `runBlocking` останавливает текущий поток. NanoHTTPD использует пул потоков для обработки запросов. Длительная блокировка (до 120 секунд в `apiVerifyCode`) может исчерпать пул и «заморозить» весь сервер.

**Решение:** Использовать асинхронную модель — запускать корутины через `serverScope.launch` и возвращать ответ через `CompletableDeferred` или polling-механизм (который уже частично реализован через `/api/status`).

---

#### 3. Busy-wait с `Thread.sleep` в HTTP-обработчике
**Файл:** `MaxHttpServer.kt:217-226`

```kotlin
// Ждём результат (busy wait с Thread.sleep)
for (i in 0 until 1200) {
    if (AppState.isAuthenticated) { ... return ... }
    if (AppState.connectError != null && !AppState.isConnecting) { ... return ... }
    Thread.sleep(100)  // ← до 120 секунд блокировки!
}
```

**Проблема:** Цикл с `Thread.sleep(100)` на 1200 итераций — это 120 секунд блокировки потока NanoHTTPD. Это антипаттерн, который блокирует серверный поток вместо использования асинхронного подхода. Клиент уже имеет polling через `/api/status` — логика проверки должна быть там.

**Решение:** Убрать busy-wait. `apiVerifyCode` должен просто передать код в протокол и вернуть немедленный ответ `{"ok": true, "message": "Код передан"}`. Клиент через polling `/api/status` узнает результат авторизации.

---

#### 4. Токены авторизации хранятся в открытом виде
**Файл:** `MaxProtocol.kt:459-469`

```kotlin
private fun saveToken(token: String) {
    val tokenFile = File(file, "sessions/token_${...}.txt")
    tokenFile.writeText(token)  // ← ОТКРЫТЫЙ ТЕКСТ
}
```

**Проблема:** Токены авторизации сохраняются в файловую систему в виде открытого текста. На рутованном устройстве или через backup любой может прочитать токен и получить доступ к аккаунту.

**Решение:** Использовать `EncryptedSharedPreferences` из AndroidX Security или Android Keystore для шифрования токенов.

---

#### 5. `FOREGROUND_SERVICE` запускается без уведомления на Android 14+
**Файл:** `MaxService.kt:11-29`

```kotlin
class MaxService : Service() {
    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Создание канала и уведомления только для API >= 26
        }
    }
}
```

**Проблема:** На Android 14 (API 34, что является `targetSdk`) для `foregroundServiceType="dataSync"` требуется разрешение `FOREGROUND_SERVICE_DATA_SYNC` в манифесте. Без него сервис упадёт с `SecurityException`. Также отсутствует проверка на `POST_NOTIFICATIONS` permission (требуется с Android 13).

**Решение:**
- Добавить в манифест: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`
- Запросить `POST_NOTIFICATIONS` runtime permission перед запуском сервиса

---

### 🟠 СЕРЬЁЗНЫЕ (архитектура / стабильность)

#### 6. Thread-safety: `mutableListOf` без синхронизации
**Файл:** `AppState.kt:23-25`

```kotlin
val chatsCache = mutableListOf<Map<String, Any?>>()
val messagesCache = mutableMapOf<Long, MutableList<Map<String, Any?>>>()
val newMessages = mutableListOf<Map<String, Any?>>()
```

**Проблема:** Эти коллекции доступны из нескольких потоков (HTTP-сервер, корутины протокола, reader-loop), но не синхронизированы. Одновременная запись/чтение может привести к `ConcurrentModificationException` или потере данных.

**Решение:** Использовать `CopyOnWriteArrayList` / `ConcurrentHashMap` или обернуть доступ в `Mutex`/`synchronized`.

---

#### 7. `AppStateHelper.fileLog()` вызывает `connLog()` вместо `fileLog()`
**Файл:** `AppState.kt:110`

```kotlin
object AppStateHelper {
    fun fileLog(msg: String) = AppState.connLog(msg)  // ← Вызывает connLog, не fileLog!
}
```

**Проблема:** `AppStateHelper.fileLog()` делегирует в `AppState.connLog()` вместо прямого вызова `AppState.fileLog()`. Это не баг в прямом смысле (fileLog вызывается внутри connLog), но семантически неверно и ведёт к двойной записи в лог (connLog пишет в консоль и файл, а fileLog должен писать только в файл).

**Решение:** Либо сделать `AppState.fileLog()` публичным и вызывать его напрямую, либо переименовать метод, чтобы избежать путаницы.

---

#### 8. Дублирование состояния между `AppState` и `MaxProtocol`
**Файлы:** `AppState.kt`, `MaxProtocol.kt`

Оба класса содержат одинаковый набор полей:
- `isAuthenticated`, `isConnecting`, `connectionAlive`, `connectError`
- `authCode`, `authEventArrived`

**Проблема:** Два источника истины. `MaxProtocol` хранит своё состояние, но HTTP-сервер читает из `AppState`. Синхронизация происходит вручную через коллбэки (строки 170-178 в MaxHttpServer), что хрупко и может рассинхронизироваться.

**Решение:** Убрать дублирующие поля из `MaxProtocol` и использовать только `AppState` как единый источник истины. Либо наоборот — убрать `AppState` и работать через `MaxProtocol`.

---

#### 9. `onBackPressed()` устарел
**Файл:** `MainActivity.kt:66-69`

```kotlin
override fun onBackPressed() {
    if (webView.canGoBack()) webView.goBack()
    else super.onBackPressed()
}
```

**Проблема:** Метод `onBackPressed()` объявлен `@Deprecated` с API 33. На Android 13+ вместо него используется `OnBackPressedDispatcher` + `BackPressedCallback`.

**Решение:**
```kotlin
onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else isEnabled = false  // позволит системной навигации обработать
    }
})
```

---

#### 10. HTTP-сервер слушает на всех интерфейсах, а не только на localhost
**Файл:** `MaxHttpServer.kt:14`

```kotlin
class MaxHttpServer(context: android.content.Context, port: Int) : NanoHTTPD(port) {
```

**Проблема:** NanoHTTPD по умолчанию слушает `0.0.0.0:8085` — доступен с любого сетевого интерфейса. Любое устройство в той же сети может обратиться к API и получить токены, сообщения и т.д.

**Решение:** Явно указать `127.0.0.1` как hostname:
```kotlin
class MaxHttpServer(context: android.content.Context, port: Int) : NanoHTTPD("127.0.0.1", port) {
```

---

#### 11. `seqCounter` не потокобезопасен
**Файл:** `MaxTcpClient.kt:236-240`

```kotlin
private var seqCounter = 0

private fun nextSeq(): Int {
    seqCounter++
    if (seqCounter > 0xFFFF) seqCounter = 1
    return seqCounter
}
```

**Проблема:** `seqCounter` увеличивается без синхронизации, хотя может вызываться из разных корутин (через `request()` и `respond()`). Race condition может привести к дублированию seq-номеров.

**Решение:** Использовать `AtomicInteger`:
```kotlin
private val seqCounter = AtomicInteger(0)

private fun nextSeq(): Int {
    val seq = seqCounter.incrementAndGet()
    if (seq > 0xFFFF) seqCounter.set(1)
    return seqCounter.incrementAndGet()
}
```

---

#### 12. Протечка `CoroutineScope` в `MaxProtocol`
**Файл:** `MaxProtocol.kt:59`

```kotlin
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
```

**Проблема:** Этот `scope` никогда не отменяется. Даже при вызове `close()` (строка 482-490) корутины, запущенные в `scope`, продолжат работать. Плюс `SupervisorJob` не привязан к lifecycle — при уничтожении Activity корутины не отменяются.

**Решение:** Отменять `scope` в методе `close()`:
```kotlin
suspend fun close() {
    scope.cancel()
    // ...
}
```

---

#### 13. Страница `/chat` — заглушка без функционала
**Файл:** `MaxHttpServer.kt:108`

```kotlin
if (uri == "/chat") return htmlResponse("<!DOCTYPE html><html>...<h1>Чат</h1><p>Заглушка</p>...")
```

**Проблема:** После успешной авторизации пользователь перенаправляется на `/chat`, но страница содержит только текст «Заглушка». Нет списка чатов, нет сообщений, нет возможности писать.

**Решение:** Реализовать полноценный UI чата с использованием `/api/chats`, `/api/messages/{id}`, `/api/send-message` и `/api/poll`.

---

### 🟡 УМЕРЕННЫЕ (качество кода / обслуживание)

#### 14. ProGuard включён, но `isMinifyEnabled = false`
**Файл:** `app/build.gradle.kts:20-23`

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false
        isShrinkResources = false
        proguardFiles(...)
    }
}
```

**Проблема:** В README заявлено «~4 MB с ProGuard + shrinkResources», но оба флага отключены. Release APK будет значительно больше ожидаемого, а код не будет обфусцирован.

**Решение:**
```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(...)
}
```

---

#### 15. Отсутствует файл `gradlew.bat` для Windows
**Файл:** `build.bat` ссылается на `gradlew.bat`, но в репозитории есть только `gradlew` (Unix shell script).

**Проблема:** `build.bat` проверяет наличие `gradlew.bat`, но файл отсутствует в репозитории. Сборка на Windows через `build.bat` не сработает.

**Решение:** Добавить `gradlew.bat` в репозиторий (генерируется через `gradle wrapper`).

---

#### 16. Захардкожен порт 8085
**Файлы:** `MainActivity.kt:54`, `MaxHttpServer.kt:14`, `MaxService.kt:22`

**Проблема:** Порт 8085 указан в трёх разных местах. Если порт занят другим приложением, нет механизма fallback или настройки.

**Решение:** Вынести в константу или настройку, добавить обработку `BindException` с попыткой следующего порта.

---

#### 17. HTML/CSS/JS встроены в Kotlin-код
**Файл:** `MaxHttpServer.kt:17-85`

**Проблема:** Весь HTML (страница авторизации) собран через `buildString` с `append()`. Это нечитаемо, сложно поддерживать и невозможно тестировать отдельно.

**Решение:** Вынести HTML в ресурсы (`assets/` папка) или отдельные файлы. Загружать через `context.assets.open()`.

---

#### 18. `provideAuthCode()` вызывается дважды
**Файл:** `MaxHttpServer.kt:208-214`

```kotlin
AppState.provideAuthCode(code)
// Также напрямую в протокол
val protocol = AppState.protocol
if (protocol != null) {
    runBlocking { protocol.provideAuthCode(code) }
}
```

**Проблема:** Код передаётся через два канала: `AppState.provideAuthCode()` и `protocol.provideAuthCode()`. В `MaxProtocol.waitForAuthCode()` читается `authCode` из собственных полей, а `AppState.authCode` не используется. Это может привести к ситуации, когда код не будет прочитан протоколом.

**Решение:** Использовать один механизм передачи кода — через `CompletableDeferred` или `Channel`, а не volatile-флаги.

---

#### 19. Отсутствует reconnect-механизм
**Файл:** `MaxTcpClient.kt`

**Проблема:** При потере соединения (EOF, timeout, ошибка) клиент просто вызывает `closeInternal()` и уведомляет через `onDisconnect`. Нет автоматической попытки переподключения.

**Решение:** Реализовать exponential backoff reconnect в `MaxProtocol`:

```kotlin
private suspend fun reconnectLoop() {
    var attempt = 0
    while (isAuthenticated) {
        delay(min(1000L * (1 shl attempt), 60000L))
        attempt++
        val token = savedToken
        if (token != null && client.connect()) {
            if (loginByToken(token)) { attempt = 0; continue }
        }
    }
}
```

---

#### 20. `fileLog` пишет уровень `[INFO]` даже для ошибок
**Файл:** `AppState.kt:59-68`

```kotlin
private fun fileLog(msg: String) {
    ...
    fw.write("[$ts] [INFO] $msg\n")  // ← Всегда INFO, даже если вызвано из connLogError
}
```

**Проблема:** `connLogError()` вызывает `fileLog("ERROR: $msg")`, но в файле всё равно пишется `[INFO] ERROR: ...`. Уровень лога в файле не отражает реальную серьёзность.

**Решение:** Передавать уровень в `fileLog()`:
```kotlin
private fun fileLog(msg: String, level: String = "INFO") {
    fw.write("[$ts] [$level] $msg\n")
}
```

---

#### 21. Захардкожен timezone `"Europe/Moscow"`
**Файл:** `MaxProtocol.kt:204, 254`

**Проблема:** В handshake и login payload часовой пояс `"Europe/Moscow"` захардкожен. Пользователь в другом часовом поясе будет отправлять неверные данные.

**Решение:** Определять timezone динамически:
```kotlin
"timezone" to java.util.TimeZone.getDefault().id
```

---

#### 22. CORS `Access-Control-Allow-Origin: *` на localhost-сервере
**Файл:** `MaxHttpServer.kt:311`

**Проблема:** Разрешены запросы с любого origin. Поскольку сервер слушает на всех интерфейсах (см. проблему #10), любой сайт может обратиться к API.

**Решение:** Ограничить CORS до `http://127.0.0.1:8085` или убрать заголовки CORS совсем (WebView не нуждается в них).

---

#### 23. Нет обработки `WRITE_EXTERNAL_STORAGE` permission
**Файл:** `MainActivity.kt:48`, `AndroidManifest.xml:8-9`

**Проблема:** В манифесте объявлено `WRITE_EXTERNAL_STORAGE` (maxSdk=28), но runtime-запрос permission отсутствует. На Android 6-8 (API 23-28) запись на внешний накопитель не будет работать.

**Решение:** Добавить runtime-запрос permission или использовать `getExternalFilesDir()` (который уже используется) — он не требует permission на API 19+.

---

#### 24. `SimpleDateFormat` не потокобезопасен
**Файл:** `AppState.kt:44, 52, 61, 64`

```kotlin
val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
```

**Проблема:** `SimpleDateFormat` не потокобезопасен, а `AppState.connLog()` может вызываться из разных потоков. Создание нового экземпляра каждый раз решает проблему, но неэффективно.

**Решение:** Использовать `java.time.format.DateTimeFormatter` (доступен с minSdk 26) или кэшировать форматтер в `ThreadLocal`.

---

#### 25. `usesCleartextTraffic="true"` в манифесте
**Файл:** `AndroidManifest.xml:14`

```xml
android:usesCleartextTraffic="true"
```

**Проблема:** Разрешает HTTP-трафик без шифрования. Локальный сервер на 127.0.0.1 работает через HTTP, но глобально включать cleartext traffic не нужно — это ослабляет безопасность.

**Решение:** Использовать `android:networkSecurityConfig` с `domain-config` для `127.0.0.1`, а для остальных доменов запретить cleartext.

---

## Сводная таблица проблем

| # | Серьёзность | Категория | Описание |
|---|-------------|-----------|----------|
| 1 | 🔴 Критическая | Безопасность | Trust-all SSL — MITM-уязвимость |
| 2 | 🔴 Критическая | Архитектура | `runBlocking` в HTTP-обработчиках |
| 3 | 🔴 Критическая | Архитектура | Busy-wait 120 секунд с `Thread.sleep` |
| 4 | 🔴 Критическая | Безопасность | Токены в открытом виде |
| 5 | 🔴 Критическая | Совместимость | ForegroundService без нужных permission |
| 6 | 🟠 Серьёзная | Thread-safety | `mutableListOf` без синхронизации |
| 7 | 🟠 Серьёзная | Баг логики | `fileLog` делегирует в `connLog` |
| 8 | 🟠 Серьёзная | Архитектура | Дублирование состояния AppState/MaxProtocol |
| 9 | 🟠 Серьёзная | Совместимость | Устаревший `onBackPressed()` |
| 10 | 🟠 Серьёзная | Безопасность | HTTP на 0.0.0.0 вместо localhost |
| 11 | 🟠 Серьёзная | Thread-safety | `seqCounter` без синхронизации |
| 12 | 🟠 Серьёзная | Утечка памяти | CoroutineScope не отменяется |
| 13 | 🟠 Серьёзная | Функционал | `/chat` — заглушка без UI |
| 14 | 🟡 Умеренная | Конфигурация | ProGuard отключён в release |
| 15 | 🟡 Умеренная | Сборка | Нет `gradlew.bat` для Windows |
| 16 | 🟡 Умеренная | Качество | Захардкожен порт 8085 |
| 17 | 🟡 Умеренная | Качество | HTML в Kotlin-коде |
| 18 | 🟡 Умеренная | Баг логики | Двойная передача authCode |
| 19 | 🟡 Умеренная | Функционал | Нет reconnect-механизма |
| 20 | 🟡 Умеренная | Качество | `[INFO]` вместо уровня ошибки в логе |
| 21 | 🟡 Умеренная | Качество | Захардкожен timezone |
| 22 | 🟡 Умеренная | Безопасность | CORS `*` на открытом сервере |
| 23 | 🟡 Умеренная | Совместимость | Нет runtime-запроса permission |
| 24 | 🟡 Умеренная | Качество | SimpleDateFormat не потокобезопасен |
| 25 | 🟡 Умеренная | Безопасность | `usesCleartextTraffic=true` |

---

## Приоритетный план исправлений

### Фаза 1 — Безопасность (критично)
- [x] **#1** Заменить trust-all SSL на системный TrustManager
- [x] **#4** Зашифровать хранение токенов (токен в private файле)
- [x] **#10** Привязать HTTP-сервер к `127.0.0.1`
- [x] **#25** Настроить networkSecurityConfig вместо cleartextTraffic
- [x] **#5** Добавить `FOREGROUND_SERVICE_DATA_SYNC` permission

### Фаза 2 — Стабильность (серьёзно)
- [x] **#2,#3** Убрать `runBlocking` и busy-wait из HTTP-обработчиков
- [x] **#6** Заменить `mutableListOf` на потокобезопасные коллекции
- [x] **#8** Устранить дублирование состояния AppState/MaxProtocol
- [x] **#11** Сделать `seqCounter` потокобезопасным (`AtomicInteger`)
- [x] **#12** Отменять CoroutineScope в `MaxProtocol.close()`

### Фаза 3 — Функционал
- [x] **#13** Реализовать UI чата на `/chat`
- [ ] **#19** Добавить auto-reconnect при потере соединения
- [x] **#14** Включить ProGuard и shrinkResources в release

### Фаза 4 — Качество кода
- [ ] **#17** Вынести HTML в assets (оставлено inline для простоты)
- [x] **#9** Заменить `onBackPressed()` на `OnBackPressedDispatcher`
- [x] **#16** Вынести порт в константу
- [x] **#18** Унифицировать передачу authCode
- [x] **#20-24** Мелкие исправления логирования и качества
