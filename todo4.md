# NanoMaxChat — Нет имён пользователей и не разделены сообщения (лево/право)

## Симптомы

1. **Вместо имён пользователей в списке чатов — «Диалог #N»** или «Пользователь #N».
   Собственные и собеседников реальные имена не отображаются.

2. **Все сообщения показываются с одной стороны (слева)** — нет визуального
   разделения «мои / чужие» (outgoing = справа, incoming = слева).

---

## Корневая причина 1: Нет имён пользователей — «Диалог #N»

### Анализ кода

В `MaxHttpServer.kt` (строки 246–272) метод `apiChats()` пытается извлечь
имя собеседника для DIALOG-чатов без `title`:

```kotlin
private fun apiChats(): Response {
    val rawChats = AppState.chatsCache.toList()
    val meId = AppState.currentUserId
    val chats = rawChats.map { chat ->
        val map = chat.toMutableMap()
        if (map["title"] !is String || (map["title"] as? String).orEmpty().isEmpty()) {
            val type = map["type"] as? String ?: ""
            val id = map["id"] ?: map["chat_id"] ?: 0
            if (type == "DIALOG") {
                val participants = map["participants"] as? Map<*, *>  // ← ОШИБКА ТИПА
                val otherId = participants?.keys?.firstOrNull { key ->
                    val k = (key as? Number)?.toLong() ?: 0
                    k != 0L && k != meId
                }
                val otherName = if (otherId != null) "Пользователь $otherId" else "Диалог #$id"
                map["title"] = otherName
                map["name"] = otherName
            } else {
                map["title"] = "Чат #$id"
                map["name"] = "Чат #$id"
            }
        }
        map
    }
    return jsonOk(mapOf("chats" to chats))
}
```

### Подпроблема 1A: `participants` — неверный тип (Map вместо List)

Код приводит `participants` к `Map<*, *>` (словарь userId → данные), но
протокол MAX (PyMax) возвращает `participants` как **List** объектов-участников:

```python
# PyMax — pymax/api/chats/models.py
class Chat(CamelModel):
    id: int
    type: ChatType            # DIALOG, GROUP, CHANNEL
    title: str | None         # null для DIALOG
    participants: list[Participant] | None  # ← LIST, не MAP
    last_message: Message | None
    ...

class Participant(CamelModel):
    id: int
    name: str | None
    first_name: str | None
    last_name: str | None
    avatar: Avatar | None
    ...
```

После msgpack-сериализации через `CamelModel` (camelCase), каждый участник
в `participants` — это map с ключами `id`, `name`, `firstName`, `lastName`,
`avatar`. Это **массив** (msgpack array), а не map.

**Результат:** `participants as? Map<*, *>` возвращает `null`, потому что
это `List<*>`. Код считает, что участников нет, и показывает «Диалог #N».

### Подпроблема 1B: Нет разрешения имён — «Пользователь #N» вместо имени

Даже если бы `participants` парсился правильно, код при нахождении `otherId`
показывает `"Пользователь $otherId"` — просто числовой ID. Никакого извлечения
имени из объекта участника не происходит.

### Подпроблема 1C: Нет кэша пользователей

Приложение не поддерживает кэш `userId → userName`. Каждый раз, когда нужно
показать имя, оно не может быть разрешено, потому что данные о пользователях
нигде не сохраняются. После логина `AppState` получает список чатов с объектами
`participants`, но имена участников теряются при обработке.

### Подпроблема 1D: `apiMe()` возвращает захардкоженные данные

```kotlin
// MaxHttpServer.kt:232-241
private fun apiMe(): Response {
    val phone = AppState.currentPhone ?: ""
    return jsonOk(mapOf("user" to mapOf(
        "id" to 0,                      // ← Всегда 0!
        "name" to "Пользователь",       // ← Захардкожено!
        "phone" to phone,
        "avatar_url" to ""
    )))
}
```

Профиль текущего пользователя не извлекается из ответа LOGIN. В JavaScript
это отображается как «Пользователь» в сайдбаре, и `state.me.id` всегда 0.

### Исправление 1: Парсинг participants и извлечение имён

**Шаг 1:** Добавить кэш пользователей в `AppState.kt`:

```kotlin
// AppState.kt — добавить поле
val usersCache = ConcurrentHashMap<Long, Map<String, Any?>>()
```

**Шаг 2:** Сохранять данные пользователей при обработке чатов в `loginWithToken()`
и `fetchChats()`:

```kotlin
// MaxProtocol.kt — после загрузки чатов
for (chat in chatMaps) {
    val participants = chat["participants"] as? List<*>
    if (participants != null) {
        for (p in participants) {
            if (p is Map<*, *>) {
                val uid = (p["id"] as? Number)?.toLong()
                if (uid != null && uid > 0) {
                    @Suppress("UNCHECKED_CAST")
                    AppState.usersCache[uid] = p as Map<String, Any?>
                }
            }
        }
    }
}
```

**Шаг 3:** Переписать `apiChats()` — правильно парсить `participants` и
разрешать имена:

```kotlin
private fun apiChats(): Response {
    if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
    val rawChats = AppState.chatsCache.toList()
    val meId = AppState.currentUserId
    val chats = rawChats.map { chat ->
        val map = chat.toMutableMap()
        val title = map["title"] as? String
        if (title.isNullOrEmpty()) {
            val type = map["type"] as? String ?: ""
            val id = map["id"] ?: map["chat_id"] ?: 0
            if (type == "DIALOG") {
                // participants — это List<Map>, не Map
                val participants = map["participants"] as? List<*>
                var otherName: String? = null
                if (participants != null) {
                    for (p in participants) {
                        if (p is Map<*, *>) {
                            val uid = (p["id"] as? Number)?.toLong() ?: 0
                            if (uid != meId && uid > 0) {
                                // Извлекаем имя из объекта участника
                                otherName = (p["name"] as? String)
                                    ?: (p["firstName"] as? String)
                                    ?: (p["lastName"] as? String)?.let { ln ->
                                        (p["firstName"] as? String)?.let { fn -> "$fn $ln" } ?: ln
                                    }
                                // Также сохраняем в кэш на будущее
                                @Suppress("UNCHECKED_CAST")
                                AppState.usersCache[uid] = p as Map<String, Any?>
                                break
                            }
                        }
                    }
                }
                // Если не нашли в participants — попробовать кэш
                if (otherName.isNullOrEmpty()) {
                    // Попробовать найти другого участника по ID чата
                    // Для DIALOG: chatId может быть = userId собеседника
                    val chatIdLong = (id as? Number)?.toLong() ?: id.toString().toLongOrNull() ?: 0
                    val cachedUser = AppState.usersCache[chatIdLong]
                    if (cachedUser != null) {
                        otherName = (cachedUser["name"] as? String)
                            ?: (cachedUser["firstName"] as? String)
                    }
                }
                val displayName = if (!otherName.isNullOrEmpty()) otherName else "Диалог #$id"
                map["title"] = displayName
                map["name"] = displayName
            } else {
                map["title"] = "Чат #$id"
                map["name"] = "Чат #$id"
            }
        }
        // Добавляем phone для поиска, если есть
        if (!map.containsKey("phone")) {
            // Для DIALOG чатов попробуем найти телефон в participants
            val participants = map["participants"] as? List<*>
            if (participants != null) {
                for (p in participants) {
                    if (p is Map<*, *>) {
                        val uid = (p["id"] as? Number)?.toLong() ?: 0
                        if (uid != meId && uid > 0) {
                            map["phone"] = p["phone"] as? String ?: ""
                            break
                        }
                    }
                }
            }
        }
        map
    }
    return jsonOk(mapOf("chats" to chats))
}
```

**Шаг 4:** Переписать `apiMe()` — использовать реальные данные из LOGIN:

```kotlin
// AppState.kt — добавить поле для профиля
@Volatile var userProfile: Map<String, Any?>? = null

// MaxProtocol.kt — в loginWithToken(), после извлечения profile:
if (profile != null) {
    @Suppress("UNCHECKED_CAST")
    AppState.userProfile = profile as Map<String, Any?>
    // ... существующий код извлечения userId ...
}

// MaxHttpServer.kt — apiMe()
private fun apiMe(): Response {
    if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
    val profile = AppState.userProfile
    val name = (profile?.get("name") as? String)
        ?: (profile?.get("firstName") as? String)
        ?: "Пользователь"
    val phone = AppState.currentPhone ?: ""
    val id = AppState.currentUserId
    val avatar = (profile?.get("avatar") as? Map<*, *>)?.let { av ->
        av["url"] as? String ?: av["large"] as? String ?: ""
    } ?: ""
    return jsonOk(mapOf("user" to mapOf(
        "id" to id,
        "name" to name,
        "phone" to phone,
        "avatar_url" to avatar
    )))
}
```

**Шаг 5:** Логировать структуру чата и профиля для отладки:

```kotlin
// MaxProtocol.kt — в loginWithToken(), после обработки чатов
if (chatMaps.isNotEmpty()) {
    val first = chatMaps.first()
    val keys = first.keys.joinToString(", ")
    val type = first["type"]
    val title = first["title"]
    val participants = first["participants"]
    val pType = when (participants) {
        is List<*> -> "List[${participants.size}]"
        is Map<*, *> -> "Map[${participants.size}]"
        null -> "null"
        else -> participants::class.simpleName
    }
    AppStateHelper.addLogEntry(
        "Первый чат: id=${first["id"]} type=$type title=$title participants=$pType keys=[$keys]"
    )
    // Логируем первого участника если есть
    if (participants is List<*> && participants.isNotEmpty()) {
        val p0 = participants.first()
        if (p0 is Map<*, *>) {
            AppStateHelper.addLogEntry(
                "Первый participant: id=${p0["id"]} name=${p0["name"]} firstName=${p0["firstName"]} keys=[${p0.keys.joinToString(",")}]"
            )
        }
    }
}

// Логируем структуру профиля
if (profile != null) {
    AppStateHelper.addLogEntry(
        "Профиль: keys=[${profile.keys.joinToString(",")}] id=${profile["id"]} userId=${profile["userId"]} name=${profile["name"]} firstName=${profile["firstName"]}"
    )
}
```

---

## Корневая причина 2: Сообщения не разделены лево/право

### Анализ кода

В `MaxHttpServer.kt` (строки 306–329) метод `normalizeMessages()` определяет
`outgoing`:

```kotlin
private fun normalizeMessages(msgs: List<Map<String, Any?>>, chatId: Long): List<Map<String, Any?>> {
    val meId = AppState.currentUserId
    return msgs.map { msg ->
        val m = msg.toMutableMap()
        val sender = (m["sender"] as? Number)?.toLong()
            ?: (m["senderId"] as? Number)?.toLong()
            ?: (m["from"] as? Number)?.toLong()
            ?: 0
        val isOut = sender > 0 && sender == meId
        m["outgoing"] = isOut
        // ...
    }
}
```

В JavaScript (`chat.html`, строка 1240):

```javascript
function renderMessage(msg) {
    const isOut = msg.outgoing === true || msg.type === 'outgoing' || msg.fromMe;
    const cls = isOut ? 'outgoing' : 'incoming';
    // ...
}
```

CSS:

```css
.msg.outgoing { align-self: flex-end; background: ...; }   /* справа */
.msg.incoming { align-self: flex-start; background: ...; } /* слева */
```

### Подпроблема 2A: `currentUserId` = 0 — главный виновник

Если `AppState.currentUserId` равен 0, условие `sender > 0 && sender == meId`
**всегда ложно**. Все сообщения помечаются как `incoming` (слева).

Причина: извлечение `currentUserId` из ответа LOGIN может не срабатывать.
Код проверяет несколько вариантов расположения ID:

```kotlin
var userId: Long? = (profile["id"] as? Number)?.toLong()
if (userId == null || userId <= 0) {
    userId = (profile["userId"] as? Number)?.toLong()
}
if (userId == null || userId <= 0) {
    val userMap = profile["user"] as? Map<*, *>
    userId = (userMap?.get("id") as? Number)?.toLong()
}
```

В протоколе MAX (PyMax) профиль пользователя при логине возвращается
в поле `profile`. Структура профиля в PyMax:

```python
class Profile(CamelModel):
    user_id: int              # → "userId"
    first_name: str | None    # → "firstName"
    last_name: str | None     # → "lastName"
    name: str | None          # → "name"
    phone: str | None         # → "phone"
    avatar: Avatar | None     # → "avatar"
    about: str | None         # → "about"
    ...
```

**Ключевое:** PyMax сериализует `user_id` как `"userId"` (camelCase),
а не `"id"`. Код проверяет `profile["id"]` первым, но в ответе сервера
скорее всего поле называется `"userId"`.

Также возможно, что в ответе LOGIN структура выглядит иначе — например:
- `profile.userId` — напрямую
- `profile.user.id` — вложенный объект `user`
- `profile.id` — в некоторых версиях API

Без логирования реального ответа сложно определить точное поле.

### Подпроблема 2B: Имя поля sender в сообщении

Код проверяет три варианта имени поля: `sender`, `senderId`, `from`.
В PyMax сообщение выглядит так:

```python
class Message(CamelModel):
    id: int
    chat_id: int              # → "chatId"
    sender_id: int            # → "senderId"  ← ВОТ ОНО
    text: str | None
    timestamp: int
    status: MessageStatus
    ...
```

PyMax сериализует `sender_id` как `"senderId"` (camelCase).
Код проверяет `senderId`, что **должно работать**. Но проблема в том,
что если `currentUserId = 0`, то даже правильный `senderId` не поможет.

### Подпроблема 2C: Тип senderId — Long vs Int

Msgpack может вернуть числовой ID как `Int` или `Long` в зависимости от
размера значения. Код проверяет `as? Number`, что покрывает оба случая.
Однако если ID приходит как msgpack-INTEGER, `unpackAny()` возвращает
`unpacker.unpackLong()`, что корректно.

### Исправление 2: Правильное извлечение currentUserId + fallback

**Шаг 1:** Расширить поиск userId в профиле — добавить все возможные
варианты и логировать структуру:

```kotlin
// MaxProtocol.kt — в loginWithToken(), блок извлечения профиля
val profile = loginData["profile"] as? Map<*, *>
if (profile != null) {
    // Логируем все ключи профиля для отладки
    AppStateHelper.addLogEntry("Профиль ключи: [${profile.keys.joinToString(",")}]")

    var userId: Long? = null

    // Вариант 1: profile.userId (PyMax: user_id → userId)
    userId = (profile["userId"] as? Number)?.toLong()

    // Вариант 2: profile.id
    if (userId == null || userId <= 0) {
        userId = (profile["id"] as? Number)?.toLong()
    }

    // Вариант 3: profile.user.id
    if (userId == null || userId <= 0) {
        val userMap = profile["user"] as? Map<*, *>
        userId = (userMap?.get("id") as? Number)?.toLong()
            ?: (userMap?.get("userId") as? Number)?.toLong()
    }

    // Вариант 4: chatId текущего пользователя из participants
    // Для этого проверим: если это DIALOG, chatId может быть userId собеседника
    // В LOGIN может быть поле с прямым ID
    if (userId == null || userId <= 0) {
        // Попробуем найти из токена или других полей
        val uid = loginData["userId"] as? Number
        userId = uid?.toLong()
    }

    if (userId != null && userId > 0) {
        AppState.currentUserId = userId
        AppStateHelper.addLogEntry("ID пользователя: $userId")
    } else {
        AppStateHelper.addLogEntry("ПРЕДУПРЕЖДЕНИЕ: не удалось извлечь userId из профиля!")
        AppStateHelper.addLogEntry("Профиль данные: ${profile.entries.take(10).associate { it.key.toString() to it.value }}")
    }
} else {
    AppStateHelper.addLogEntry("ПРЕДУПРЕЖДЕНИЕ: LOGIN ответ без profile!")
    // Fallback: попробуем chatId из токена
    val uid = loginData["userId"] as? Number
    if (uid != null) {
        AppState.currentUserId = uid.toLong()
        AppStateHelper.addLogEntry("userId из loginData: ${uid.toLong()}")
    }
}
```

**Шаг 2:** Добавить fallback-определение outgoing в `normalizeMessages()`:

Если `currentUserId` всё ещё 0, можно использовать альтернативный способ
определения исходящих сообщений — через сравнение sender с chatId:

```kotlin
private fun normalizeMessages(msgs: List<Map<String, Any?>>, chatId: Long): List<Map<String, Any?>> {
    val meId = AppState.currentUserId
    return msgs.map { msg ->
        val m = msg.toMutableMap()

        // Определяем sender
        val sender = (m["senderId"] as? Number)?.toLong()
            ?: (m["sender"] as? Number)?.toLong()
            ?: (m["from"] as? Number)?.toLong()
            ?: (m["senderId"] as? ByteArray)?.let {
                // Если senderId пришёл как ByteArray (msgpack binary)
                try { String(it).toLongOrNull() } catch (_: Exception) { null }
            }
            ?: 0

        // Определяем outgoing
        val isOut = if (meId > 0) {
            sender > 0 && sender == meId
        } else {
            // Fallback: если meId неизвестен, используем другие признаки
            (m["outgoing"] as? Boolean) == true
                || (m["fromMe"] as? Boolean) == true
                || (m["isOutgoing"] as? Boolean) == true
        }
        m["outgoing"] = isOut

        // Сохраняем senderId в нормализованном виде для отладки
        if (sender > 0) m["senderId"] = sender

        if (m["chatId"] == null && m["chat_id"] == null) m["chatId"] = chatId
        if (m["chat_id"] != null) m["chatId"] = m["chat_id"]

        // Нормализуем timestamp
        val ts = m["timestamp"] ?: m["time"] ?: m["createdAt"]
        if (ts is Number && ts.toLong() < 10000000000L) {
            m["timestamp"] = ts.toLong() * 1000
        } else if (ts != null) {
            m["timestamp"] = ts
        }
        if (m["timestamp"] == null) m["timestamp"] = m["time"]

        // Нормализуем текст сообщения
        if (m["text"] == null) {
            m["text"] = m["body"] ?: m["message"] ?: m["content"] ?: ""
        }

        m
    }
}
```

**Шаг 3:** Логировать структуру первого сообщения для отладки:

```kotlin
// В apiMessages(), после fetchHistory:
if (msgs.isNotEmpty()) {
    val first = msgs.first()
    val keys = first.keys.joinToString(", ")
    val senderId = first["senderId"] ?: first["sender"] ?: first["from"]
    AppStateHelper.addLogEntry(
        "Первое сообщение: senderId=$senderId keys=[$keys] meId=${AppState.currentUserId}"
    )
}
```

---

## Дополнительная проблема: Имя отправителя не отображается в чате

В текущем UI (`chat.html`, `renderMessage()`) имя отправителя **вообще
не отображается** — показывается только текст сообщения и время.
В групповых чатах это критично: непонятно, кто написал сообщение.

### Исправление: Добавить имя отправителя в renderMessage()

**Шаг 1:** В `normalizeMessages()` добавлять имя отправителя:

```kotlin
// В normalizeMessages()
val senderId = (m["senderId"] as? Number)?.toLong()
    ?: (m["sender"] as? Number)?.toLong() ?: 0
if (senderId > 0) {
    val cachedUser = AppState.usersCache[senderId]
    if (cachedUser != null) {
        m["senderName"] = (cachedUser["name"] as? String)
            ?: (cachedUser["firstName"] as? String)
            ?: "Пользователь"
    }
}
```

**Шаг 2:** В `chat.html` добавить отображение имени для входящих сообщений
в групповых чатах:

```css
/* Добавить в CSS */
.msg-sender {
    font-size: 12px;
    font-weight: 600;
    color: #60a5fa;
    margin-bottom: 4px;
}
.msg.outgoing .msg-sender { display: none; }
```

```javascript
// В renderMessage(), добавить:
const senderName = msg.senderName || '';
const isGroupChat = ...; // определить тип чата
let senderHtml = '';
if (!isOut && senderName && isGroupChat) {
    senderHtml = `<div class="msg-sender">${escHtml(senderName)}</div>`;
}

// В return-шаблоне:
return `
    <div class="msg ${cls}">
        ${senderHtml}
        ${content}
        <div class="msg-time">${timeStr} ${statusIcon}</div>
    </div>
`;
```

---

## Полный список исправлений (чеклист)

### 🔴 Критические (блокируют отображение имён и разделение сообщений)

- [ ] **#1** Заменить `participants as? Map<*, *>` → `participants as? List<*>` в `apiChats()`
- [ ] **#2** Извлекать имя собеседника из объекта participant: `p["name"]` / `p["firstName"]`
- [ ] **#3** Добавить `AppState.usersCache` (ConcurrentHashMap<Long, Map<String, Any?>>) для кэша пользователей
- [ ] **#4** Заполнять `usersCache` при загрузке чатов (из participants каждого чата)
- [ ] **#5** Исправить извлечение `currentUserId` из профиля: проверять `"userId"` первым (как в PyMax)
- [ ] **#6** Добавить логирование структуры профиля LOGIN для отладки (ключи, значения)
- [ ] **#7** Переписать `apiMe()` — возвращать реальные данные из `AppState.userProfile`

### 🟠 Серьёзные (часть проблемы с разделением сообщений)

- [ ] **#8** Добавить fallback для определения outgoing: если `currentUserId = 0`, проверять `msg["outgoing"]`, `msg["fromMe"]`, `msg["isOutgoing"]`
- [ ] **#9** Логировать структуру первого сообщения (senderId, keys) при загрузке истории
- [ ] **#10** Проверять `senderId` (camelCase) первым в `normalizeMessages()`, т.к. PyMax сериализует `sender_id` как `"senderId"`
- [ ] **#11** Нормализовать `text` в `normalizeMessages()`: проверять `body`, `message`, `content`

### 🟡 Умеренные (дополнительные улучшения)

- [ ] **#12** Добавить отображение имени отправителя в групповых чатах (CSS `.msg-sender`)
- [ ] **#13** Добавить phone в данные чата для поиска (из participants)
- [ ] **#14** Сохранять `AppState.userProfile` при логине для использования в `apiMe()`
- [ ] **#15** Обновлять `usersCache` при получении новых сообщений (из `senderId` + `senderName`)

---

## Минимальный патч для исправления обеих проблем

### Патч 1: Исправить apiChats() — имена вместо «Диалог #N»

Заменить блок извлечения имени в `MaxHttpServer.kt`:

```kotlin
// Было:
val participants = map["participants"] as? Map<*, *>
val otherId = participants?.keys?.firstOrNull { ... }
val otherName = if (otherId != null) "Пользователь $otherId" else "Диалог #$id"

// Стало:
val participants = map["participants"] as? List<*>
var otherName: String? = null
if (participants != null) {
    for (p in participants) {
        if (p is Map<*, *>) {
            val uid = (p["id"] as? Number)?.toLong() ?: 0
            if (uid != meId && uid > 0) {
                otherName = (p["name"] as? String)
                    ?: (p["firstName"] as? String)
                @Suppress("UNCHECKED_CAST")
                AppState.usersCache[uid] = p as Map<String, Any?>
                break
            }
        }
    }
}
val displayName = if (!otherName.isNullOrEmpty()) otherName else "Диалог #$id"
```

### Патч 2: Исправить извлечение currentUserId — разделение сообщений

В `MaxProtocol.kt`, в `loginWithToken()`, заменить блок извлечения профиля:

```kotlin
// Было:
var userId: Long? = (profile["id"] as? Number)?.toLong()
if (userId == null || userId <= 0) {
    userId = (profile["userId"] as? Number)?.toLong()
}

// Стало:
var userId: Long? = (profile["userId"] as? Number)?.toLong()  // PyMax: user_id → userId
if (userId == null || userId <= 0) {
    userId = (profile["id"] as? Number)?.toLong()
}
if (userId == null || userId <= 0) {
    val userMap = profile["user"] as? Map<*, *>
    userId = (userMap?.get("userId") as? Number)?.toLong()
        ?: (userMap?.get("id") as? Number)?.toLong()
}
// Лог для отладки
AppStateHelper.addLogEntry("Профиль: keys=[${profile.keys.joinToString(",")}] userId=$userId")
```

### Патч 3: Добавить usersCache в AppState

В `AppState.kt` добавить:

```kotlin
val usersCache = ConcurrentHashMap<Long, Map<String, Any?>>()
```

И в `resetAuth()`:

```kotlin
usersCache.clear()
```

---

## Сводная таблица причин

| Симптом | Корневая причина | Где | Исправление |
|---------|-----------------|-----|-------------|
| «Диалог #N» вместо имени | `participants as? Map` вместо `as? List` | MaxHttpServer.apiChats() | Заменить на `List<*>` |
| «Диалог #N» вместо имени | Нет извлечения имени из participant | MaxHttpServer.apiChats() | Читать `p["name"]`/`p["firstName"]` |
| «Пользователь» в сайдбаре | `apiMe()` возвращает `id:0, name:"Пользователь"` | MaxHttpServer.apiMe() | Использовать AppState.userProfile |
| Все сообщения слева | `currentUserId = 0` (не извлечён) | MaxProtocol.loginWithToken() | Проверять `userId` первым |
| Все сообщения слева | Нет fallback при `meId = 0` | MaxHttpServer.normalizeMessages() | Проверять `msg["outgoing"]`/`msg["fromMe"]` |
| Нет имени отправителя | Не отображается в UI | chat.html renderMessage() | Добавить `.msg-sender` |

---

## Ссылки

- **PyMax 2.0.0**: https://pymax.org
- **PyMax Chat Models**: `src/pymax/api/chats/models.py` — структура Chat, Participant
- **PyMax Message Models**: `src/pymax/api/messages/models.py` — структура Message (senderId)
- **PyMax Profile Models**: `src/pymax/api/profile/models.py` — структура Profile (userId)
- **PyMax CamelModel**: `src/pymax/api/models.py` — alias_generator=to_camel
- **NanoMaxChat**: https://github.com/advogr2022-max/nanomaxchat
