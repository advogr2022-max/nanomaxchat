# NanoMaxChat — Ошибка "expected string at 26" и несоответствия протокола PyMax

## Описание ошибки

При попытке запросить SMS-код в логе появляется ошибка **"expected string at 26"**.
Это ошибка msgpack-десериализации: сервер MAX в ответ на `AUTH_REQUEST` (opcode 17) возвращает
msgpack-map, в котором на позиции/типе 26 вместо строки (`ValueType.STRING`) приходит значение
другого типа (скорее всего `ValueType.INTEGER` или `ValueType.BINARY`).

### Контекст ошибки

Ошибка возникает в цепочке:

1. Пользователь вводит номер телефона и нажимает «Отправить SMS-код»
2. `MaxProtocol.startAuth()` отправляет `AUTH_REQUEST` с payload:
   ```kotlin
   msgpackMap(
       "phone" to phone,
       "type" to AUTH_TYPE_START,      // AUTH_TYPE_START = 0 (Int)
       "language" to "ru"
   )
   ```
3. Сервер отвечает msgpack-картой, и `unpackMap()` → `unpackKey()` → `unpackAny()` падает
   при разборе 26-го поля, потому что ожидает строку, а получает другой тип.

---

## Корневая причина: несоответствие протокола PyMax

Сравнение реализации NanoMaxChat с PyMax 2.0.0 выявило **несколько критических расхождений**
в форматах payload, которые приводят к ошибке "expected string at 26" и другим проблемам.

---

## Несоответствие 1 (КРИТИЧЕСКОЕ): AuthType — Int вместо String

### NanoMaxChat (текущий код)

```kotlin
// MaxProtocol.kt:33-34
const val AUTH_TYPE_START = 0        // ← Int
const val AUTH_TYPE_CHECK_CODE = 1   // ← Int

// MaxProtocol.kt:382-386 — AUTH_REQUEST payload
val authReqPayload = msgpackMap(
    "phone" to phone,
    "type" to AUTH_TYPE_START,        // ← отправляется как Int (0)
    "language" to "ru"
)

// MaxProtocol.kt:411-415 — AUTH payload
val authPayload = msgpackMap(
    "token" to authToken,
    "verify_code" to code,
    "authTokenType" to AUTH_TYPE_CHECK_CODE  // ← отправляется как Int (1)
)
```

### PyMax 2.0.0 (эталон)

```python
# pymax/api/auth/enums.py
class AuthType(str, Enum):
    START_AUTH = "START_AUTH"    # ← String!
    CHECK_CODE = "CHECK_CODE"   # ← String!
    REGISTER = "REGISTER"
    RESEND = "RESEND"

# pymax/api/auth/payloads.py
class RequestCodePayload(CamelModel):
    phone: str
    type: AuthType = AuthType.START_AUTH    # ← "START_AUTH" (строка)
    language: str = "ru"

class SendCodePayload(CamelModel):
    token: str
    verify_code: str
    auth_token_type: AuthType = AuthType.CHECK_CODE  # ← "CHECK_CODE" (строка)
```

### Почему это вызывает "expected string at 26"

Сервер MAX ожидает, что поле `type` в `AUTH_REQUEST` будет строкой `"START_AUTH"`.
Когда NanoMaxChat отправляет `0` (Int), сервер воспринимает это как некорректный запрос
и возвращает ответ с изменённой структурой (error-карту или модифицированный payload),
в котором 26-й элемент имеет тип, отличный от ожидаемого строкового.
Десериализатор `unpackAny()` на этом элементе бросает исключение.

### Исправление

```kotlin
// MaxProtocol.kt — заменить константы
companion object {
    // ...
    const val AUTH_TYPE_START = "START_AUTH"        // String, как в PyMax
    const val AUTH_TYPE_CHECK_CODE = "CHECK_CODE"   // String, как в PyMax
}
```

---

## Несоответствие 2 (КРИТИЧЕСКОЕ): Имена полей — snake_case вместо camelCase

### Проблема

NanoMaxChat использует `snake_case` для ключей msgpack, но сервер MAX ожидает
`camelCase` (как PyMax через `CamelModel` с `by_alias=True`).

### Сравнение полей

| NanoMaxChat (отправляет) | PyMax (отправляет) | Правильно |
|--------------------------|--------------------|-----------|
| `"authTokenType"` | `"authTokenType"` | ✅ Совпадает |
| `"verify_code"` | `"verifyCode"` | ❌ Должно быть `verifyCode` |
| `"client_session_id"` | — | См. ниже (handshake) |
| `"mt_instanceid"` | `"mt_instanceid"` | ✅ Совпадает (исключение) |

### Ключевое расхождение в AUTH payload

```kotlin
// NanoMaxChat — MaxProtocol.kt:411-415
val authPayload = msgpackMap(
    "token" to authToken,
    "verify_code" to code,                    // ❌ snake_case
    "authTokenType" to AUTH_TYPE_CHECK_CODE   // ✅ camelCase
)
```

```python
# PyMax — pymax/api/auth/payloads.py
class SendCodePayload(CamelModel):
    token: str
    verify_code: str              # Python поле = snake_case
    auth_token_type: AuthType     # Python поле = snake_case

# CamelModel с by_alias=True сериализует как:
# "token", "verifyCode", "authTokenType"  ← camelCase!
```

### Исправление

```kotlin
// MaxProtocol.kt — AUTH payload (отправка SMS-кода)
val authPayload = msgpackMap(
    "token" to authToken,
    "verifyCode" to code,                     // ← camelCase, как в PyMax
    "authTokenType" to AUTH_TYPE_CHECK_CODE
)
```

---

## Несоответствие 3 (СЕРЬЁЗНОЕ): Handshake payload — отсутствуют поля и неверные типы

### NanoMaxChat

```kotlin
// MaxProtocol.kt:278-283
val payload = msgpackMap(
    "mt_instanceid" to "",                     // Пустая строка вместо UUID
    "userAgent" to userAgentMap(),             // Встроенный map вместо CamelModel
    "clientSessionId" to 42,                   // Захардкожено
    "deviceId" to AppStateHelper.deviceId
)
```

### PyMax

```python
# pymax/api/session/payloads.py
class MobileHandshakePayload(CamelModel):
    mt_instance_id: str = Field(..., alias="mt_instanceid")  # alias → "mt_instanceid"
    user_agent: MobileUserAgentPayload                         # alias → "userAgent"
    client_session_id: int = Field(default_factory=lambda: randint(1, 70))  # alias → "clientSessionId"
    device_id: str                                             # alias → "deviceId"

# После сериализации (by_alias=True):
# {
#   "mt_instanceid": "<UUID>",        ← Реальный UUID, не пустая строка!
#   "userAgent": { ... },             ← CamelCase ключи внутри
#   "clientSessionId": <random 1-70>, ← Случайное число
#   "deviceId": "<UUID>"
# }
```

### Проблемы

1. **`mt_instanceid` = `""` (пустая строка):** PyMax генерирует реальный UUID.
   Сервер может отклонить пустой instance ID или выдать некорректный ответ.

2. **UserAgent map с неверными ключами:** NanoMaxChat отправляет:

   ```kotlin
   mapOf(
       "deviceType" to "android",     // ❌ "android" вместо "ANDROID"
       "appVersion" to "2.1.1",       // ❌ Устаревшая версия
       "osVersion" to Build.VERSION.RELEASE,
       "timezone" to TimeZone.getDefault().id,
       "screen" to "1080x1920",
       "locale" to "ru",
       "deviceLocale" to "ru",
       "deviceName" to Build.MODEL
   )
   ```

   PyMax отправляет (через `CamelModel` + `generate_user_agent()`):

   ```python
   {
       "deviceType": "ANDROID",                    # ← Верхний регистр!
       "appVersion": "26.14.1",                    # ← Актуальная версия
       "osVersion": "Android 14",
       "timezone": "Europe/Moscow",
       "screen": "405dpi 405dpi 1080x2400",        # ← С DPI
       "pushDeviceType": "GCM",                     # ← Дополнительное поле
       "arch": "arm64-v8a",                         # ← Дополнительное поле
       "locale": "ru",
       "buildNumber": 6686,                         # ← Дополнительное поле
       "deviceName": "Samsung SM-A525F",
       "deviceLocale": "ru"
   }
   ```

3. **Отсутствующие поля в UserAgent:** `pushDeviceType`, `arch`, `buildNumber` —
   сервер может ожидать их и формировать некорректный ответ.

### Исправление

```kotlin
// MaxProtocol.kt — исправить userAgentMap()
private fun userAgentMap(): Map<String, Any> = mapOf(
    "deviceType" to "ANDROID",                         // Верхний регистр
    "appVersion" to "26.14.1",                         // Актуальная версия
    "buildNumber" to 6686,                             // Из PyMax APP_VERSIONS
    "osVersion" to "Android ${android.os.Build.VERSION.RELEASE}",
    "timezone" to TimeZone.getDefault().id,
    "screen" to "405dpi 405dpi 1080x2400",             // С DPI
    "pushDeviceType" to "GCM",                         // Добавлено
    "arch" to "arm64-v8a",                             // Добавлено
    "locale" to "ru",
    "deviceLocale" to "ru",
    "deviceName" to android.os.Build.MODEL
)

// MaxProtocol.kt — исправить handshake
private suspend fun doHandshake(): Boolean {
    val mtInstanceId = java.util.UUID.randomUUID().toString()  // Реальный UUID
    val payload = msgpackMap(
        "mt_instanceid" to mtInstanceId,
        "userAgent" to userAgentMap(),
        "clientSessionId" to (1..70).random(),    // Случайное, как в PyMax
        "deviceId" to AppStateHelper.deviceId
    )
    // ...
}
```

---

## Несоответствие 4 (СЕРЬЁЗНОЕ): LOGIN payload — отсутствуют поля и неверные ключи

### NanoMaxChat

```kotlin
// MaxProtocol.kt:321-329
val loginPayload = msgpackMap(
    "userAgent" to userAgentMap(),
    "token" to token,
    "chatsSync" to -1,          // ❌ camelCase, но в PyMax = chats_sync → chatsSync ✓
    "contactsSync" to -1,       // ❌ camelCase, но в PyMax = contacts_sync → contactsSync ✓
    "draftsSync" to -1,         // ❌ camelCase, но в PyMax = drafts_sync → draftsSync ✓
    "interactive" to true,
    "presenceSync" to -1        // ❌ camelCase, но в PyMax = presence_sync → presenceSync ✓
)
```

### PyMax (SyncPayload)

```python
class SyncPayload(CamelModel):
    user_agent: MobileUserAgentPayload   # → "userAgent"
    token: str                           # → "token"
    chat_hash_fingerprint: str | None    # → "chatHashFingerprint"   ← ОТСУТСТВУЕТ
    chats_count: int | None              # → "chatsCount"            ← ОТСУТСТВУЕТ
    chats_sync: int = -1                 # → "chatsSync"
    contacts_sync: int = -1              # → "contactsSync"
    drafts_sync: int = -1                # → "draftsSync"
    interactive: bool = True             # → "interactive"
    presence_sync: int = -1              # → "presenceSync"
    exp: Exp = Field(default_factory=Exp)  # → "exp"                 ← ОТСУТСТВУЕТ
    config_hash: ConfigHash = DEFAULT_CONFIG_HASH  # → "configHash"  ← ОТСУТСТВУЕТ
```

### Отсутствующие поля в LOGIN

1. **`chatHashFingerprint`** — отсутствует (PyMax отправляет `None`)
2. **`chatsCount`** — отсутствует (PyMax отправляет `None`)
3. **`exp`** — специальный payload: `{"chatsCountGroups": <bytearray 0x0a32>}`
4. **`configHash`** — хеш конфигурации, по умолчанию `DEFAULT_CONFIG_HASH`

### Исправление

```kotlin
// MaxProtocol.kt — loginWithToken()
val loginPayload = msgpackMap(
    "userAgent" to userAgentMap(),
    "token" to token,
    "chatsSync" to -1,
    "contactsSync" to -1,
    "draftsSync" to -1,
    "interactive" to true,
    "presenceSync" to -1,
    "exp" to mapOf("chatsCountGroups" to byteArrayOf(0x0a, 0x32)),  // Добавлено
    "configHash" to mapOf(                                           // Добавлено
        "settings" to 0,
        "contacts" to 0,
        "presences" to 0,
        "chats" to 0,
        "drafts" to 0
    )
)
```

---

## Несоответствие 5 (СРЕДНЕЕ): Отсутствие opcode ASSETS_GET = 26

### Контекст

Ошибка "expected string at 26" может также быть связана с opcode `ASSETS_GET = 26`.
PyMax определяет этот opcode, но NanoMaxChat его не обрабатывает.

```python
# PyMax protocol/enums.py
class Opcode(int, Enum):
    # ...
    ASSETS_GET = 26          # ← Отсутствует в NanoMaxChat
    ASSETS_UPDATE = 27
    ASSETS_GET_BY_IDS = 28
    ASSETS_ADD = 29
    # ...
```

Если после handshake или auth сервер отправляет event с opcode 26 (ASSETS_GET),
а NanoMaxChat не умеет его обрабатывать, ответ может быть интерпретирован неверно.

### Исправление

Добавить недостающие opcode в `MaxProtocol.kt`:

```kotlin
companion object {
    // ... существующие opcode ...
    const val OP_ASSETS_GET = 26
    const val OP_ASSETS_UPDATE = 27
    const val OP_ASSETS_GET_BY_IDS = 28
    const val OP_ASSETS_ADD = 29
    const val OP_AUTH_CONFIRM = 23
    const val OP_AUTH_CREATE_TRACK = 112
    const val OP_AUTH_CHECK_PASSWORD = 113
    const val OP_AUTH_LOGIN_CHECK_PASSWORD = 115
    const val OP_AUTH_2FA_DETAILS = 104
    const val OP_DRAFT_SAVE = 176
    const val OP_DRAFT_DISCARD = 177
    const val OP_MSG_REACTION = 178
    const val OP_MSG_CANCEL_REACTION = 179
    const val OP_NOTIF_MESSAGE = 128     // Уже используется как 128
    const val OP_NOTIF_TYPING = 129
    const val OP_NOTIF_PRESENCE = 132
    const val OP_NOTIF_CHAT = 135
}
```

---

## Несоответствие 6 (СРЕДНЕЕ): unpackAny() не обрабатывает EXTENSION тип

### Проблема

Функция `unpackAny()` в `MaxProtocol.kt` обрабатывает базовые типы msgpack,
но не обрабатывает `ValueType.EXTENSION`. Сервер MAX может отправлять
extension-типы (например, timestamp msgpack или пользовательские типы),
что вызовет `skipValue()` и потерю данных или сдвиг потока.

### Исправление

```kotlin
private fun unpackAny(unpacker: org.msgpack.core.MessageUnpacker): Any? {
    val fmt = unpacker.getNextFormat()
    return when (fmt.getValueType()) {
        ValueType.NIL -> { unpacker.unpackNil(); null }
        ValueType.BOOLEAN -> unpacker.unpackBoolean()
        ValueType.INTEGER -> unpacker.unpackLong()
        ValueType.FLOAT -> unpacker.unpackDouble()
        ValueType.STRING -> unpacker.unpackString()
        ValueType.BINARY -> {
            val len = unpacker.unpackBinaryHeader()
            val bytes = ByteArray(len)
            unpacker.readPayload(bytes)
            bytes
        }
        ValueType.ARRAY -> {
            val size = unpacker.unpackArrayHeader()
            (0 until size).map { unpackAny(unpacker) }
        }
        ValueType.MAP -> unpackValue(unpacker)
        ValueType.EXTENSION -> {
            // Обработка extension-типов (например, timestamp)
            val extHeader = unpacker.unpackExtensionTypeHeader()
            val extBytes = ByteArray(extHeader.length)
            unpacker.readPayload(extBytes)
            extBytes  // Возвращаем как ByteArray
        }
        else -> { unpacker.skipValue(); null }
    }
}
```

**Важно:** В текущем коде `ValueType.BINARY` декодируется как `bytes.decodeToString()`,
но в некоторых случаях binary-данные — это не UTF-8 текст (например, хеши, токены,
шифрованные данные). Лучше возвращать `ByteArray` и декодировать только при необходимости.

---

## Полный список исправлений (чеклист)

### 🔴 Критические (блокируют работу SMS-авторизации)

- [ ] **#1** Заменить `AUTH_TYPE_START = 0` → `"START_AUTH"` (String)
- [ ] **#2** Заменить `AUTH_TYPE_CHECK_CODE = 1` → `"CHECK_CODE"` (String)
- [ ] **#3** Заменить `"verify_code"` → `"verifyCode"` в AUTH payload
- [ ] **#4** Исправить `deviceType` с `"android"` → `"ANDROID"` (верхний регистр)
- [ ] **#5** Обновить `appVersion` с `"2.1.1"` → `"26.14.1"` (актуальная)
- [ ] **#6** Добавить поля `pushDeviceType`, `arch`, `buildNumber` в userAgent
- [ ] **#7** Генерировать реальный UUID для `mt_instanceid` вместо пустой строки

### 🟠 Серьёзные (могут вызывать проблемы при работе)

- [ ] **#8** Добавить `exp` и `configHash` в LOGIN payload
- [ ] **#9** Возвращать `ByteArray` из `unpackAny()` для BINARY вместо `decodeToString()`
- [ ] **#10** Добавить обработку `ValueType.EXTENSION` в `unpackAny()`
- [ ] **#11** Добавить недостающие opcode из PyMax (23, 26-29, 104, 112-113, 115 и т.д.)
- [ ] **#12** Использовать формат screen с DPI: `"405dpi 405dpi 1080x2400"`

### 🟡 Умеренные (для полной совместимости с PyMax)

- [ ] **#13** Рандомизировать `clientSessionId` (1..70) вместо 42
- [ ] **#14** Добавить `chatHashFingerprint` и `chatsCount` в LOGIN payload
- [ ] **#15** Реализовать обработку 2FA password (opcode 113, 115)
- [ ] **#16** Обрабатывать event-opcode 129 (NOTIF_TYPING), 132 (NOTIF_PRESENCE), 135 (NOTIF_CHAT)

---

## Минимальный патч для исправления "expected string at 26"

Для быстрого решения проблемы достаточно изменить три строки в `MaxProtocol.kt`:

```kotlin
// До (вызывает ошибку):
const val AUTH_TYPE_START = 0
const val AUTH_TYPE_CHECK_CODE = 1

// После (соответствует PyMax):
const val AUTH_TYPE_START = "START_AUTH"
const val AUTH_TYPE_CHECK_CODE = "CHECK_CODE"
```

И исправить ключ поля verify_code:

```kotlin
// До:
"verify_code" to code,

// После:
"verifyCode" to code,
```

И исправить deviceType:

```kotlin
// До:
"deviceType" to "android",

// После:
"deviceType" to "ANDROID",
```

Эти три изменения должны устранить ошибку "expected string at 26", так как сервер
MAX начнёт корректно распознавать запрос AUTH_REQUEST и возвращать ожидаемый
msgpack-ответ с правильной структурой полей.

---

## Ссылки

- **PyMax 2.0.0**: https://pymax.org
- **PyMax GitHub**: https://github.com/MaxApiTeam/PyMax
- **NanoMaxChat**: https://github.com/advogr2022-max/nanomaxchat
- **PyMax Auth Payloads**: `src/pymax/api/auth/payloads.py`
- **PyMax Auth Enums**: `src/pymax/api/auth/enums.py`
- **PyMax Protocol Opcodes**: `src/pymax/protocol/enums.py`
- **PyMax Config**: `src/pymax/config.py`
