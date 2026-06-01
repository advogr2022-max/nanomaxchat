# NanoMaxChat — Ошибка загрузки истории чатов: причины и исправления

## Симптомы (из лога на скриншоте)

```
[10:31:02] [INFO]  Вход по токену успешен!
[10:31:12] [INFO]  fetchHistory ERROR: {error:proto.payLoad.message
               Fetch requirement failed: chatId ...
               Ошибка валидации: title Ошибка валид...
[10:31:18] [ERROR] Ошибка загрузки истории: Not connected
[10:31:21] [ERROR] Ошибка загрузки истории: Not connected
```

**Вход работает**, но:
1. Сервер отклоняет запрос истории: **"Fetch requirement failed: chatId"**
2. После ошибки валидации сервер разрывает соединение → **"Not connected"**

---

## Корневые причины

Сравнение кода NanoMaxChat с PyMax 2.0.0 выявило **системную проблему**: все API-вызовы
используют **snake_case** ключи вместо **camelCase**, который ожидает сервер MAX
(PyMax через `CamelModel` с `to_camel` alias-generator сериализует все поля в camelCase).

---

## Ошибка 1 (КРИТИЧЕСКАЯ): `fetchHistory` — неверные имена полей

### NanoMaxChat

```kotlin
// MaxProtocol.kt:558-561
suspend fun fetchHistory(chatId: Long, count: Int = 50): List<Map<String, Any>> {
    val payload = msgpackMap(
        "chat_id" to chatId,    // ❌ snake_case
        "limit" to count        // ❌ этого поля нет в PyMax
    )
    val resp = client.request(OP_CHAT_HISTORY, payload)
    // ...
}
```

### PyMax 2.0.0

```python
# pymax/api/messages/payloads.py
class ChatHistoryPayload(CamelModel):
    chat_id: int          # → сериализуется как "chatId" (camelCase!)
    forward: int          # → "forward"
    backward: int = 40    # → "backward"
    backward_time: int = 0   # → "backwardTime"
    forward_time: int = 0    # → "forwardTime"
    get_chat: bool = False   # → "getChat"
    from_: int = Field(serialization_alias="from")  # → "from"
    item_type: ItemType = ItemType.REGULAR  # → "itemType": "REGULAR"
    get_messages: bool = True    # → "getMessages"
    interactive: bool = False    # → "interactive"
```

После сериализации (`to_payload()` = `model_dump(by_alias=True, exclude_none=True)`):

```python
{
    "chatId": 12345,           # ← camelCase!
    "forward": 0,
    "backward": 40,
    "backwardTime": 0,
    "forwardTime": 0,
    "getChat": False,
    "from": 1717234000000,     # ← timestamp в мс
    "itemType": "REGULAR",     # ← строка, не int
    "getMessages": True,
    "interactive": False
}
```

### Различия

| Поле | NanoMaxChat | PyMax (правильно) |
|------|-------------|-------------------|
| ID чата | `"chat_id"` (snake_case) | `"chatId"` (camelCase) |
| Ограничение | `"limit": 50` | Нет поля `limit`; есть `forward` и `backward` |
| Откуда | Отсутствует | `"from": <timestamp_ms>` |
| Тип элементов | Отсутствует | `"itemType": "REGULAR"` |
| Получить чат | Отсутствует | `"getChat": false` |
| Получить сообщения | Отсутствует | `"getMessages": true` |
| Направление | Отсутствует | `"forward": 0, "backward": 40` |
| Временные границы | Отсутствует | `"backwardTime": 0, "forwardTime": 0` |

**Именно `"chat_id"` вместо `"chatId"`** — прямая причина ошибки
"Fetch requirement failed: chatId" — сервер не находит обязательное поле `chatId`.

### Исправление

```kotlin
suspend fun fetchHistory(chatId: Long, count: Int = 40): List<Map<String, Any>> {
    val now = System.currentTimeMillis()
    val payload = msgpackMap(
        "chatId" to chatId,               // ✅ camelCase
        "forward" to 0,                    // ✅ как в PyMax
        "backward" to count,               // ✅ backward вместо limit
        "backwardTime" to 0,               // ✅
        "forwardTime" to 0,                // ✅
        "getChat" to false,                // ✅
        "from" to now,                     // ✅ timestamp в мс
        "itemType" to "REGULAR",           // ✅ строка, не int
        "getMessages" to true,             // ✅
        "interactive" to false             // ✅
    )
    val resp = client.request(OP_CHAT_HISTORY, payload) ?: return emptyList()
    if (resp.cmd == MaxTcpClient.CMD_ERROR) {
        AppStateHelper.addLogEntry("fetchHistory ERROR: ${String(resp.payload, Charsets.UTF_8)}")
        return emptyList()
    }
    val data = unpackMap(resp.payload, resp.flags)
    val msgs = data["messages"] as? List<Map<String, Any>>
        ?: data["CHAT_HISTORY"] as? List<Map<String, Any>>
    return msgs?.filterIsInstance<Map<String, Any>>() ?: emptyList()
}
```

---

## Ошибка 2 (КРИТИЧЕСКАЯ): `fetchChats` — неверные имена полей

### NanoMaxChat

```kotlin
// MaxProtocol.kt:542-556
suspend fun fetchChats(): List<Map<String, Any>> {
    val payload = msgpackMap("limit" to 100)   // ❌ нет поля limit в PyMax
    val resp = client.request(OP_CHATS_LIST, payload)
    // ...
}
```

### PyMax 2.0.0

```python
# pymax/api/chats/payloads.py
class FetchChatsPayload(CamelModel):
    marker: int    # → "marker" (timestamp в мс)

# pymax/api/chats/service.py
async def fetch_chats(self, marker: int | None = None) -> list[Chat]:
    frame = FetchChatsPayload(marker=marker or int(time.time() * 1000))
    response = await self.app.invoke(Opcode.CHATS_LIST, frame.to_payload())
```

Сериализуется как: `{"marker": 1717234000000}`

### Различия

| Поле | NanoMaxChat | PyMax (правильно) |
|------|-------------|-------------------|
| Маркер | `"limit": 100` | `"marker": <timestamp_ms>` |

Поле `limit` сервером MAX не распознаётся, вместо него ожидается `marker` —
временная метка в миллисекундах, от которой сервер отдаёт чаты.

### Исправление

```kotlin
suspend fun fetchChats(): List<Map<String, Any>> {
    val marker = System.currentTimeMillis()
    val payload = msgpackMap("marker" to marker)    // ✅ как в PyMax
    val resp = client.request(OP_CHATS_LIST, payload) ?: return emptyList()
    if (resp.cmd == MaxTcpClient.CMD_ERROR) {
        AppStateHelper.addLogEntry("fetchChats ERROR: ${String(resp.payload, Charsets.UTF_8)}")
        return emptyList()
    }
    val data = unpackMap(resp.payload, resp.flags)
    val chats = data["chats"] as? List<Map<String, Any>>
        ?: data["CHATS_LIST"] as? List<Map<String, Any>>
    return chats?.filterIsInstance<Map<String, Any>>() ?: emptyList()
}
```

---

## Ошибка 3 (КРИТИЧЕСКАЯ): `sendMessage` — неверная структура payload

### NanoMaxChat

```kotlin
// MaxProtocol.kt:574-587
suspend fun sendMessage(chatId: Long, text: String): String? {
    val payload = msgpackMap(
        "chat_id" to chatId,    // ❌ snake_case
        "text" to text,         // ❌ текст на верхнем уровне
        "type" to "text"        // ❌ нет такого поля
    )
    // ...
}
```

### PyMax 2.0.0

```python
# pymax/api/messages/payloads.py
class SendMessagePayload(CamelModel):
    chat_id: int                     # → "chatId"
    message: SendMessagePayloadMessage  # → вложенный объект "message"
    notify: bool = False             # → "notify"

class SendMessagePayloadMessage(CamelModel):
    text: str                        # → "text" внутри message
    cid: int                         # → "cid" (уникальный ID сообщения)
    elements: list[Any]              # → "elements" (форматирование)
    attaches: list[...]              # → "attaches" (вложения)
    link: ReplyLink | None = None    # → "link" (ответ на сообщение)
```

Сериализуется как:

```python
{
    "chatId": 12345,                    # ← camelCase
    "message": {                        # ← вложенная структура!
        "text": "Привет",
        "cid": 1717234000000,           # ← уникальный timestamp-based ID
        "elements": [],                 # ← массив элементов форматирования
        "attaches": []                  # ← массив вложений
    },
    "notify": True                      # ← уведомить получателя
}
```

### Различия

| Поле | NanoMaxChat | PyMax (правильно) |
|------|-------------|-------------------|
| ID чата | `"chat_id"` (snake_case) | `"chatId"` (camelCase) |
| Текст | `"text"` на верхнем уровне | `"message": {"text": "..."}` — вложенный объект |
| Тип | `"type": "text"` | Нет поля `type` |
| CID | Отсутствует | `"cid": <unique_id>` — обязателен |
| Elements | Отсутствует | `"elements": []` — обязателен |
| Attaches | Отсутствует | `"attaches": []` — обязателен |
| Notify | Отсутствует | `"notify": true` |

### Исправление

```kotlin
suspend fun sendMessage(chatId: Long, text: String): String? {
    val cid = System.currentTimeMillis()
    val payload = msgpackMap(
        "chatId" to chatId,                          // ✅ camelCase
        "message" to mapOf(                          // ✅ вложенный объект
            "text" to text,
            "cid" to cid,                            // ✅ уникальный ID
            "elements" to emptyList<Any>(),          // ✅ пустой массив
            "attaches" to emptyList<Any>()           // ✅ пустой массив
        ),
        "notify" to true                             // ✅ уведомление
    )
    val resp = client.request(OP_MSG_SEND, payload) ?: return null
    if (resp.cmd == MaxTcpClient.CMD_ERROR) {
        AppStateHelper.addLogEntry("sendMessage ERROR: ${String(resp.payload, Charsets.UTF_8)}")
        return null
    }
    val data = unpackMap(resp.payload, resp.flags)
    return (data["id"] as? Number)?.toString()
}
```

---

## Ошибка 4 (СЕРЬЁЗНАЯ): `fetchChats` не вызывается после логина — список чатов пуст

### Проблема

После успешного входа (`loginWithToken`) приложение устанавливает `AppState.isAuthenticated = true`
и запускает ping, но **не загружает список чатов**. Список `AppState.chatsCache` остаётся пустым.

Когда WebView переходит на `/chat`, JavaScript вызывает `loadChats()` → `GET /api/chats`,
а `apiChats()` просто возвращает `AppState.chatsCache.toList()` — пустой список.

### В логе видно

```
[10:31:02] [INFO] Вход по токену успешен!
```

Но запроса `fetchChats()` нет — UI сразу пытается открыть историю чата,
отправляя `fetchHistory()` с `chatId`, который неизвестно откуда взят.

### Исправление

Добавить загрузку чатов после успешного логина в `loginWithToken()`:

```kotlin
private suspend fun loginWithToken(token: String): Boolean {
    // ... существующий код логина ...

    AppState.isAuthenticated = true
    AppState.connectionAlive = true
    startPing()
    AppStateHelper.addLogEntry("Вход по токену успешен!")

    // ✅ Загрузить список чатов после входа
    try {
        val chats = fetchChats()
        AppState.chatsCache.clear()
        AppState.chatsCache.addAll(chats)
        AppStateHelper.addLogEntry("Загружено чатов: ${chats.size}")
    } catch (e: Exception) {
        AppStateHelper.addLogEntry("Ошибка загрузки чатов: ${e.message}")
    }

    return true
}
```

---

## Ошибка 5 (СЕРЬЁЗНАЯ): ERROR-фрейм от сервера разрывает соединение

### Проблема

Когда сервер MAX возвращает ERROR-фрейм (cmd=3) на невалидный запрос (например,
`fetchHistory` с `"chat_id"` вместо `"chatId"`), логика в `MaxTcpClient.request()`
доставляет этот фрейм, но `MaxProtocol` не обрабатывает корректно ситуацию.

Из скриншота видно, что после ошибки валидации появляются ошибки **"Not connected"** —
сервер разрывает TCP-соединение после получения невалидного запроса.

### Почему "Not connected"

1. `fetchHistory` отправляет `"chat_id"` → сервер возвращает ERROR
2. В `fetchHistory()` проверка `resp.cmd == CMD_ERROR` логирует ошибку, но
   соединение при этом **остаётся открытым**
3. Однако сервер MAX может закрыть соединение после нескольких невалидных запросов
4. Следующий `fetchHistory` пытается отправить запрос через уже закрытое соединение
   → `client.request()` возвращает `null` → но код не проверяет `isConnected`

### Исправление

Добавить проверку соединения перед отправкой и обработку ERROR-фреймов:

```kotlin
suspend fun fetchHistory(chatId: Long, count: Int = 40): List<Map<String, Any>> {
    if (!client.isConnected) {
        AppStateHelper.addLogEntry("fetchHistory: нет соединения")
        return emptyList()
    }
    // ... остальной код ...
}
```

Также в `loginWithToken()` после успешного логина стоит добавить подписку на события
через event handler, чтобы не потерять соединение:

```kotlin
// После логина — подписаться на входящие события
client.onFrame = { frame ->
    if (frame.cmd == MaxTcpClient.CMD_EVENT) {
        scope.launch { handleEvent(frame) }
    }
}
```

---

## Ошибка 6 (СРЕДНЯЯ): `searchByPhone` — неверное имя поля

### NanoMaxChat

```kotlin
val payload = msgpackMap("phone" to phone)  // ✓ совпадает
```

Это поле корректно — `phone` используется и в PyMax.

---

## Полный список исправлений (чеклист)

### 🔴 Критические (блокируют загрузку истории)

- [ ] **#1** `fetchHistory`: `"chat_id"` → `"chatId"` (camelCase)
- [ ] **#2** `fetchHistory`: заменить `"limit"` на `"backward"` + `"forward"`
- [ ] **#3** `fetchHistory`: добавить обязательные поля `"from"`, `"itemType"`, `"getMessages"`, `"getChat"`, `"backwardTime"`, `"forwardTime"`, `"interactive"`
- [ ] **#4** `fetchChats`: `"limit"` → `"marker"` (timestamp в мс)
- [ ] **#5** `sendMessage`: `"chat_id"` → `"chatId"` (camelCase)
- [ ] **#6** `sendMessage`: обернуть текст в вложенный объект `"message": {"text", "cid", "elements", "attaches"}`
- [ ] **#7** `sendMessage`: убрать `"type": "text"`, добавить `"notify": true`, `"cid"`

### 🟠 Серьёзные (часть проблемы)

- [ ] **#8** Добавить `fetchChats()` после успешного логина в `loginWithToken()`
- [ ] **#9** Проверять `client.isConnected` перед API-вызовами
- [ ] **#10** Подписаться на входящие EVENT-фреймы после логина (восстановление из loginWithToken)

### 🟡 Умеренные

- [ ] **#11** Обрабатывать ERROR-фреймы с извлечением `payLoad.message` для понятного лога
- [ ] **#12** Добавить reconnect при потере соединения (ошибка "Not connected")

---

## Минимальный патч для исправления загрузки истории

Для быстрого решения достаточно исправить `fetchHistory` и `fetchChats` в `MaxProtocol.kt`:

```kotlin
// Было:
val payload = msgpackMap("chat_id" to chatId, "limit" to count)
// Стало:
val payload = msgpackMap(
    "chatId" to chatId,
    "forward" to 0,
    "backward" to count,
    "from" to System.currentTimeMillis(),
    "itemType" to "REGULAR",
    "getMessages" to true,
    "getChat" to false,
    "interactive" to false,
    "backwardTime" to 0,
    "forwardTime" to 0
)

// Было:
val payload = msgpackMap("limit" to 100)
// Стало:
val payload = msgpackMap("marker" to System.currentTimeMillis())
```

И добавить загрузку чатов после логина:

```kotlin
// В loginWithToken(), после "Вход по токену успешен!":
val chats = fetchChats()
AppState.chatsCache.clear()
AppState.chatsCache.addAll(chats)
```

---

## Сводная таблица всех snake_case → camelCase замен

Все API-вызовы в `MaxProtocol.kt` должны использовать **camelCase** ключи,
поскольку сервер MAX ожидает именно такой формат (PyMax сериализует через
`CamelModel` с `to_camel` alias-generator).

| Текущий ключ | Правильный ключ | Метод |
|-------------|----------------|-------|
| `"chat_id"` | `"chatId"` | fetchHistory, sendMessage |
| `"limit"` (fetchChats) | `"marker"` | fetchChats |
| `"limit"` (fetchHistory) | `"backward"` + `"forward"` | fetchHistory |
| `"type"` (sendMessage) | Убрать | sendMessage |
| `"text"` (sendMessage) | → внутри `"message": {...}` | sendMessage |
| `"verify_code"` | `"verifyCode"` | AUTH |
| `"authTokenType"` | `"authTokenType"` | ✅ Уже верно |
| `"chatsSync"` | `"chatsSync"` | ✅ Уже верно |

---

## Ссылки

- **PyMax 2.0.0**: https://pymax.org
- **PyMax Chat Payloads**: `src/pymax/api/chats/payloads.py`
- **PyMax Chat Service**: `src/pymax/api/chats/service.py`
- **PyMax Message Payloads**: `src/pymax/api/messages/payloads.py`
- **PyMax Message Service**: `src/pymax/api/messages/service.py`
- **PyMax CamelModel**: `src/pymax/api/models.py` (alias_generator=to_camel)
- **PyMax Protocol Opcodes**: `src/pymax/protocol/enums.py`
