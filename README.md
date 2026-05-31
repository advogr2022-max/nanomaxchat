# MAX Mini — минимальный Android-клиент для мессенджера MAX

Нативная Android-сборка без Python. TCP+TLS+msgpack напрямую к серверам MAX.

## Размер APK

- arm64-v8a release: **~4 MB** (с ProGuard + shrinkResources)
- Без внешних прокси — только прямые сокеты к `api.oneme.ru:443`

## Структура

```
app/src/main/java/com/maxmini/
├── AppState.kt        — Глобальное состояние (как server.py AppState)
├── MaxTcpClient.kt    — TCP+TLS+msgpack протокол MAX
├── MaxApi.kt          — Высокоуровневое API (auth, chats, messages)
├── MaxHttpServer.kt   — Встраиваемый HTTP сервер (NanoHTTPD)
├── MainActivity.kt    — WebView + ForegroundService
└── MaxService.kt      — Сервис для удержания процесса
```

## Сборка

```bash
cd D:\maxmini
# Установить Android SDK, настроить ANDROID_HOME
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk (~4 MB)
```

Для debug:
```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk (~12 MB)
```

## Зависимости (минимум)

- NanoHTTPD 2.3.1 (100 KB) — HTTP сервер локально
- msgpack-core 0.9.3 (80 KB) — MAX протокол
- kotlinx-serialization-json (120 KB)
- AndroidX AppCompat + Lifecycle
