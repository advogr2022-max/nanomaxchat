# NanoMaxChat — Список найденных ошибок и рекомендации

**Репозиторий:** https://github.com/advogr2022-max/nanomaxchat  
**Дата анализа:** 2026-06-01  
**Тип приложения:** Android (Kotlin), WebView + NanoHTTPD + TCP/TLS/msgpack клиент к api.oneme.ru:443

---

## Общая архитектура

Приложение использует гибридную архитектуру:
- **Kotlin-бэкенд** запускает локальный HTTP-сервер (NanoHTTPD) на `127.0.0.1:8085`
- **WebView** рендерит HTML-страницы из `assets/www/`, которые вызывают REST API (`/api/chats`, `/api/messages/*`)
- Связь с сервером MAX — через кастомный TCP+TLS+msgpack протокол

**Структура ключевых файлов:**
```
app/src/main/java/com/maxmini/
├── AppState.kt          — Глобальное состояние (авторизация, кэши, логирование)
├── MainActivity.kt      — Хост WebView + запуск ForegroundService
├── MaxHttpServer.kt     — NanoHTTPD API-сервер для WebView
├── MaxProtocol.kt       — MAX протокол (авторизация, чат/сообщения, цикл событий)
├── MaxService.kt        — Android foreground service
└── MaxTcpClient.kt      — Низкоуровневый TCP+TLS+msgpack фреймер

app/src/main/assets/www/
├── chat.html            — UI чата (1601 строка: HTML+CSS+JS)
└── login.html           — UI авторизации (902 строки)
```

---

## БАГ #1: Отображение ID вместо имён пользователей в листинге чатов

### Симптом
В боковой панели списка чатов DIALOG-диалоги (1-на-1) показывают `"Диалог #1111"` или `"Пользователь #1111"` вместо реального имени собеседника.

### Корневая причина

**Основная проблема:** `AppState.currentUserId == 0` — неправильное определение текущего пользователя.

Код в `MaxHttpServer.kt` (строки 265–290), метод `apiChats()`:

```kotlin
val uid = (p["id"] as? Number)?.toLong() ?: 0
if (uid != meId && uid > 0) {   // ← если meId == 0, это условие истинно для ВСЕХ участников
```

Когда `AppState.currentUserId == 0` (ошибка извлечения), условие `uid != meId` превращается в `uid != 0L`, что истинно для каждого участника. Код выбирает **первого** участника, который может быть самим текущим пользователем.

**Дополнительные проблемы:**

1. **Сервер может не включать `participants`** в ответе `CHATS_LIST` — тогда fallback через `usersCache` не срабатывает (кэш пуст)
2. **Поля `name` и `firstName` могут быть null** — для пользователей, не установивших имя, `otherName` остаётся null и показывается `"Диалог #$id"`
3. **Не комбинируются `firstName` + `lastName`** — даже если есть имя, фамилия теряется

### Ключевые файлы и строки

| Файл | Строки | Проблема |
|------|--------|----------|
| `MaxHttpServer.kt` | 265–290 | `apiChats()` — извлечение имени участника; зависит от `meId != 0` |
| `MaxHttpServer.kt` | 282–288 | usersCache fallback — работает только если кэш заполнен |
| `MaxProtocol.kt` | 414–428 | Заполнение `usersCache` при логине — только из participants |
| `AppState.kt` | 25 | `currentUserId` — если 0, всё сопоставление участников ломается |

### Рекомендуемые исправления

**Исправление 1A:** Защита от `meId == 0` в `apiChats()`:
```kotlin
if (meId <= 0) {
    // Невозможно определить «другого» участника — ищем любого с именем
    for (p in participants) {
        if (p is Map<*, *>) {
            val uid = (p["id"] as? Number)?.toLong() ?: 0
            if (uid > 0) {
                otherName = (p["name"] as? String)
                    ?: (p["firstName"] as? String)
                if (!otherName.isNullOrEmpty()) {
                    AppState.usersCache[uid] = p as Map<String, Any?>
                    break
                }
            }
        }
    }
} else {
    // Нормальная логика — пропускаем текущего пользователя
    for (p in participants) { /* существующий код */ }
}
```

**Исправление 1B:** Запрос недостающих данных через `OP_ASSETS_GET_BY_IDS`:
Opcode 28 уже определён в `MaxProtocol.kt` (строка 39), но **нигде не реализован** как метод. Нужно:
- Реализовать метод `fetchUserProfiles(ids: List<Long>)`
- Вызывать при промахе `usersCache`

**Исправление 1C:** Комбинировать `firstName` + `lastName`:
```kotlin
otherName = (p["name"] as? String)
    ?: run {
        val fn = p["firstName"] as? String
        val ln = p["lastName"] as? String
        when {
            fn != null && ln != null -> "$fn $ln"
            fn != null -> fn
            ln != null -> ln
            else -> null
        }
    }
```

---

## БАГ #2: Нет разделения собеседников — все сообщения одной лентой

### Симптом
В окне чата все сообщения отображаются **на левой стороне** (стиль входящих). Нет визуального различия между «моими сообщениями» (правая сторона/синий) и «сообщениями собеседника» (левая сторона/серый). Имя отправителя также не видно.

### Корневая причина

**CSS и HTML реализованы правильно** — стили `outgoing`/`incoming` существуют и работают корректно:
```css
/* chat.html:310-321 */
.msg.outgoing { align-self: flex-end; background: linear-gradient(135deg, #2563eb, #4f46e5); }
.msg.incoming { align-self: flex-start; background: rgba(100, 116, 170, 0.12); }
```

**Проблема в бэкенде** — метод `normalizeMessages()` в `MaxHttpServer.kt` (строки 357–408):

```kotlin
val isOut = if (meId > 0) {
    sender > 0 && sender == meId
} else {
    // Fallback: если meId неизвестен, проверяем другие флаги
    (m["outgoing"] as? Boolean) == true
        || (m["fromMe"] as? Boolean) == true
        || (m["isOutgoing"] as? Boolean) == true
}
```

**Когда `meId == 0` (ошибка извлечения `currentUserId`):**
- Основная проверка `sender > 0 && sender == meId` **пропускается**
- Fallback-проверка ищет флаги `outgoing`, `fromMe`, `isOutgoing`
- **Сервер MAX НИКОГДА не отправляет эти флаги** — только `senderId`
- Результат: `isOut` всегда `false`, ВСЕ сообщения отображаются как `incoming` (левая сторона)

**Почему `currentUserId` может быть 0:**

В `MaxProtocol.kt` строки 470–501 код пытается извлечь ID из LOGIN-ответа:
```kotlin
var userId: Long? = (profile["userId"] as? Number)?.toLong()
if (userId == null || userId <= 0) {
    userId = (profile["id"] as? Number)?.toLong()
}
// ... и т.д.
```

Проблемы:
1. `profile` может быть `null` (LOGIN без поля profile)
2. Имена полей могут отличаться (snake_case: `user_id` вместо `userId`)
3. Структура может быть вложена под другим ключом

### Ключевые файлы и строки

| Файл | Строки | Проблема |
|------|--------|----------|
| `MaxHttpServer.kt` | 357–408 | `normalizeMessages()` — определение исходящих зависит от `meId > 0` |
| `MaxHttpServer.kt` | 369–375 | Fallback проверяет флаги, которых сервер не отправляет |
| `MaxProtocol.kt` | 470–501 | Извлечение `currentUserId` — может тихо провалиться |
| `AppState.kt` | 25 | `currentUserId` по умолчанию 0 |
| `chat.html` | ~1250 | `renderMessage()` — корректно использует `outgoing`, но бэкенд даёт неверное значение |

### Рекомендуемые исправления

**Исправление 2A:** Улучшить извлечение `currentUserId` с логированием:
```kotlin
// MaxProtocol.kt — в loginWithToken(), после получения loginData
val profile = loginData["profile"] as? Map<*, *>
if (profile != null) {
    // Логируем ВСЕ ключи для отладки
    AppStateHelper.addLogEntry("Профиль ключи: ${profile.keys.joinToString(", ")}")
    
    // Пробуем ВСЕ возможные имена полей, включая snake_case
    var userId: Long? = null
    for (key in listOf("userId", "user_id", "id", "uid", "accountId", "account_id")) {
        val v = profile[key]
        if (v is Number && v.toLong() > 0) { userId = v.toLong(); break }
    }
    // ... остальная логика fallback
}
```

**Исправление 2B:** Вывести `currentUserId` из сообщений как последний резерв:
```kotlin
// После загрузки истории чатов
if (AppState.currentUserId <= 0 && AppState.chatsCache.isNotEmpty()) {
    for (chat in AppState.chatsCache) {
        val chatId = (chat["id"] as? Number)?.toLong() ?: continue
        val msgs = AppState.messagesCache[chatId] ?: continue
        for (msg in msgs) {
            val senderId = (msg["senderId"] as? Number)?.toLong() ?: continue
            // Сообщения с полем status — наши (сервер ставит статус доставки)
            val hasStatus = msg["status"] != null
            if (hasStatus) {
                AppState.currentUserId = senderId
                break
            }
        }
        if (AppState.currentUserId > 0) break
    }
}
```

**Исправление 2C:** Всегда добавлять `senderName` к сообщениям:
```kotlin
// MaxHttpServer.kt — normalizeMessages()
if (sender > 0) {
    val cachedUser = AppState.usersCache[sender]
    if (cachedUser != null) {
        m["senderName"] = (cachedUser["name"] as? String)
            ?: (cachedUser["firstName"] as? String)
            ?: "Пользователь"
    } else {
        m["senderName"] = "ID:$sender"
    }
}
```

---

## БАГ #3: Проверка базы сообщений при первом входе и миграция между версиями APK

### Текущее состояние

**Постоянной локальной базы данных НЕ СУЩЕСТВУЕТ.** Все данные хранятся в оперативной памяти:

```kotlin
// AppState.kt:28-31
val chatsCache = CopyOnWriteArrayList<Map<String, Any?>>()
val messagesCache = ConcurrentHashMap<Long, CopyOnWriteArrayList<Map<String, Any?>>>()
val newMessages = CopyOnWriteArrayList<Map<String, Any?>>()
val usersCache = ConcurrentHashMap<Long, Map<String, Any?>>()
```

При каждом перезапуске приложения все сообщения теряются и должны заново загружаться с сервера.

### Текущая логика проверки версии

`AppState.kt` строки 47–67:

```kotlin
fun init(filesDir: File, deviceId: String, appVersionCode: Int = 0) {
    // ...
    val savedVersion = try { versionFile.readText().trim().toIntOrNull() ?: 0 } catch (_: Exception) { 0 }
    if (appVersionCode > 0 && savedVersion > 0 && savedVersion != appVersionCode) {
        // Версия изменилась — удаляем старые сессии, токены, кэш
        connLog("APK version changed: $savedVersion → $appVersionCode, clearing sessions")
        sessionsDir.deleteRecursively()
        val logDir = File(filesDir, "log")
        logDir.deleteRecursively()
    }
    // ...
}
```

### Проблемы текущего подхода

| Проблема | Описание |
|----------|----------|
| **Нет постоянной БД** | Все сообщения теряются при перезапуске — плохой UX, нет офлайн-доступа |
| **Слишком агрессивное удаление токена** | Любое изменение версии APK удаляет auth-токен, принуждая к повторному входу даже при минорных обновлениях |
| **Нет версионирования схемы** | Поскольку нет БД, нет схемы для версионирования или миграции |
| **Нет инкрементальной загрузки** | При повторном входе загружаются только чаты из LOGIN-ответа; полная история подгружается лениво |
| **Нет принудительной ресинхронизации** | После смены версии нет явного шага «скачать все диалоги заново» |

### Ключевые файлы и строки

| Файл | Строки | Проблема |
|------|--------|----------|
| `AppState.kt` | 28–31 | Все данные только в памяти — нет персистентности |
| `AppState.kt` | 47–67 | `init()` — проверка версии удаляет сессии, но нет миграции БД |
| `AppState.kt` | 52–53 | Проверяет только файл `app_version` — нет версии схемы БД |
| `MaxProtocol.kt` | 407–467 | Чаты загружаются только из LOGIN-ответа — нет персистентности |
| `MaxProtocol.kt` | 733–757 | `fetchHistory()` — сообщения загружаются по требованию |

### Рекомендуемые исправления

**Исправление 3A:** Добавить SQLite базу данных для постоянного хранения:

Создать новый файл `ChatDatabase.kt` с Room или raw SQLite:
```kotlin
class ChatDatabase(context: Context) {
    private val db = context.openOrCreateDatabase("nanomaxchat.db", Context.MODE_PRIVATE, null)
    private val SCHEMA_VERSION = 1

    fun init() {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY,
                chatId INTEGER NOT NULL,
                senderId INTEGER NOT NULL,
                text TEXT,
                timestamp INTEGER,
                outgoing INTEGER DEFAULT 0,
                status TEXT DEFAULT 'sent'
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chatId ON messages(chatId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chats (
                id INTEGER PRIMARY KEY,
                type TEXT,
                title TEXT,
                lastMessageText TEXT,
                lastMessageTime INTEGER,
                participants TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY,
                name TEXT,
                firstName TEXT,
                lastName TEXT,
                phone TEXT,
                avatar TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS metadata (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """)

        // Проверка версии схемы
        val currentVersion = getMeta("schema_version")?.toIntOrNull() ?: 0
        if (currentVersion < SCHEMA_VERSION) {
            migrate(currentVersion, SCHEMA_VERSION)
            setMeta("schema_version", SCHEMA_VERSION.toString())
        }
    }

    private fun migrate(from: Int, to: Int) {
        // Будущие миграции схемы здесь
    }
    // ... CRUD-методы для messages, chats, users
}
```

**Исправление 3B:** Модифицировать проверку версии для обработки миграции БД:

```kotlin
// AppState.kt — заменить текущую проверку версии в init()
if (appVersionCode > 0 && savedVersion > 0 && savedVersion != appVersionCode) {
    connLog("APK version changed: $savedVersion → $appVersionCode")

    // Проверяем, нужна ли миграция схемы БД
    val chatDb = ChatDatabase(/* context */)
    val dbSchemaVersion = chatDb.getSchemaVersion()

    if (dbSchemaVersion < ChatDatabase.REQUIRED_SCHEMA_VERSION) {
        connLog("DB schema outdated ($dbSchemaVersion), migrating...")
        chatDb.migrate(dbSchemaVersion, ChatDatabase.REQUIRED_SCHEMA_VERSION)
    }

    // Удаляем сессии только при мажорном изменении версии
    if (Math.abs(appVersionCode - savedVersion) >= 5) {
        connLog("Major version change, clearing sessions")
        sessionsDir.deleteRecursively()
    }
}
```

**Исправление 3C:** Добавить шаг «скачать все диалоги» после первого входа или смены версии:

```kotlin
// MaxProtocol.kt — добавить после успешного логина
suspend fun syncAllDialogs() {
    val chats = fetchChats()
    AppState.chatsCache.clear()
    AppState.chatsCache.addAll(chats)

    // Предзагрузка последних сообщений для каждого чата
    for (chat in chats) {
        val chatId = (chat["id"] as? Number)?.toLong() ?: continue
        val msgs = fetchHistory(chatId, count = 20)
        AppState.messagesCache[chatId] = CopyOnWriteArrayList(msgs)
        // Также сохраняем в БД
        chatDatabase.saveMessages(chatId, msgs)
    }
    chatDatabase.saveChats(chats)
    AppStateHelper.addLogEntry("Синхронизировано ${chats.size} диалогов с ${AppState.messagesCache.values.sumOf { it.size }} сообщениями")
}
```

**Исправление 3D:** При первом входе — проверка и очистка несовместимой базы:

```kotlin
// AppState.kt — в init()
if (appVersionCode > 0 && savedVersion > 0 && savedVersion != appVersionCode) {
    val dbFile = File(filesDir, "nanomaxchat.db")
    if (dbFile.exists()) {
        val chatDb = ChatDatabase(/* context */)
        val dbSchemaVersion = chatDb.getSchemaVersion()

        if (dbSchemaVersion > 0 && dbSchemaVersion != ChatDatabase.REQUIRED_SCHEMA_VERSION) {
            // Схема несовместима — удаляем старую базу, создаём новую
            connLog("Несовместимая схема БД ($dbSchemaVersion), пересоздаём")
            chatDb.close()
            dbFile.delete()
            // База будет пересоздана при следующем обращении
        }
    }
}
```

---

## ДОПОЛНИТЕЛЬНЫЕ НАЙДЕННЫЕ ПРОБЛЕМЫ

### 5.1 `runBlocking` на главном потоке в `apiMessages()`
```kotlin
// MaxHttpServer.kt:334
val msgs = runBlocking { protocol.fetchHistory(chatId) }
```
NanoHTTPD обслуживает запросы в собственном пуле потоков, но `runBlocking` может вызвать проблемы при длительных сетевых вызовах. Следует использовать корутину с таймаутом.

### 5.2 Нет очереди офлайн-сообщений
Сообщения, отправленные в офлайне, тихо теряются. Нет механизма повтора или локальной очереди.

### 5.3 Несогласованность имени поля `senderId`
`MaxProtocol.kt` определяет протокол с `senderId` (camelCase), но msgpack-unpacker может возвращать целочисленные ключи для некоторых структур. Метод `unpackKey()` (строка 243) конвертирует целочисленные ключи в строки, но `senderId` может прийти как целочисленный ключ (его msgpack-индекс) вместо строки `"senderId"`.

### 5.4 Нет дедупликации сообщений
`AppState.newMessages` (используется `/api/poll`) может содержать дубликаты, если одно событие обработано дважды (например, после реконнекта). Нет дедупликации по ID сообщения.

### 5.5 Риск XSS в рендеринге чата
`chat.html` использует `escHtml()` для текстового содержимого, но не санитизирует поля `attachment.id` или `attachment.name`, используемые в URL и HTML-атрибутах:
```javascript
const imgSrc = `/api/download/${msg.chat_id}/${msg.id}/${attId}`;
content += `<img class="msg-image" src="${escAttr(imgSrc)}" ...>`;
```
Функция `escAttr()` обрабатывает только кавычки, но не инъекцию протоколов.

### 5.6 Захардкоженная версия user-agent
```kotlin
// MaxProtocol.kt:293
"appVersion" to "26.14.1",
"buildNumber" to 6686,
```
Версия захардкожена вместо чтения из `BuildConfig`. При изменении версии APK серверный user-agent всё ещё сообщает «26.14.1», что может вызвать несовместимость API.

---

## СВОДНАЯ ТАБЛИЦА

| Баг | Корневая причина | Ключевой файл | Строки | Статус |
|-----|-----------------|---------------|--------|--------|
| **#1: «Диалог #N»** | `currentUserId=0` → неправильный участник выбран; сервер может не включать `participants`; поля имён null | `MaxHttpServer.kt` | 265–290 | Частично исправлен — List-cast корректен, но `meId=0` и отсутствующие participants вызывают проблемы |
| **#2: Все сообщения слева** | `currentUserId=0` → `isOut` всегда false; fallback проверяет флаги, которых сервер не шлёт | `MaxHttpServer.kt` | 369–375 | Частично исправлен — есть fallback-путь, но он бесполезен без серверного флага `outgoing` |
| **#3: Нет миграции БД** | Нет постоянной базы данных; проверка версии только удаляет сессии; нет принудительной ресинхронизации | `AppState.kt` | 47–67 | Не реализовано — только в памяти, нет SQLite, нет версионирования схемы |

---

## ОБЩИЙ КОРНЕВОЙ ФАКТОР

**Общая корневая причина багов #1 и #2 — `AppState.currentUserId == 0`.** Исправление извлечения `currentUserId` (с надлежащим логированием фактической структуры LOGIN-ответа) решит оба бага одновременно. Баг #3 требует архитектурных изменений для добавления постоянного хранилища.

### Приоритет исправлений

1. **КРИТИЧЕСКИЙ:** Исправить извлечение `currentUserId` из LOGIN-ответа с логированием всех ключей → решает баги #1 и #2
2. **ВЫСОКИЙ:** Добавить `firstName + lastName` комбинацию и fallback при `meId == 0`
3. **ВЫСОКИЙ:** Реализовать SQLite-базу для персистентного хранения сообщений
4. **СРЕДНИЙ:** Добавить версионирование схемы БД и миграцию
5. **СРЕДНИЙ:** Добавить принудительную синхронизацию всех диалогов после первого входа / смены версии
6. **НИЗКИЙ:** Устранить дополнительные проблемы (runBlocking, дедупликация, XSS, user-agent)
