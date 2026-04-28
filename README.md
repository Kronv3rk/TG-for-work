# TG WS Proxy для Android

**Локальный MTProto-прокси** для Telegram, портированный на Android из проекта
[Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy). Ускоряет работу
Telegram, перенаправляя трафик через WebSocket-соединения к Telegram-доменам.
Данные передаются в том же зашифрованном виде, сторонние сервера не нужны.

```
Telegram Android → MTProto Proxy (127.0.0.1:1443) → WebSocket → Telegram DC
```

## Как это работает

1. Foreground-сервис поднимает MTProto-прокси на `127.0.0.1:1443`.
2. Перехватывает 64-байтный obfuscation-init MTProto, валидирует secret.
3. Извлекает DC ID и протокол (abridged / intermediate / padded-intermediate).
4. Открывает WebSocket-over-TLS к нужному DC через домен `kwsN(-1).web.telegram.org`,
   используя IP из настроек (по умолчанию `149.154.167.220`) и SNI на домен
   Telegram.
5. Перешифровывает трафик в обе стороны (отдельные ключи клиент-сторона / DC-сторона).
6. Если WebSocket недоступен (302 / TLS-ошибка) — автоматический fallback на
   прямое TCP-соединение к стандартному IP DC.

## Сборка

Откройте проект в Android Studio (Hedgehog или новее) и соберите APK:

```bash
./gradlew assembleDebug
# или
./gradlew assembleRelease
```

Минимальная версия Android — 7.0 (API 24). Targets API 35 (Android 15).

После клонирования, если `gradle/wrapper/gradle-wrapper.jar` отсутствует —
запустите один раз:

```bash
gradle wrapper --gradle-version 8.10.2
```

или просто откройте проект в Android Studio — он сам подтянет wrapper.

## Подключение Telegram

После запуска прокси (кнопка «Запустить»):

### Через ссылку

Нажмите **«Открыть в Telegram»** — откроется Telegram с диалогом подключения
к прокси. Если кнопка не сработала, нажмите иконку «копировать», отправьте ссылку
себе в избранное и нажмите по ней.

### Вручную

Telegram → **Настройки** → **Данные и память** → **Настройки прокси** →
**Добавить прокси** → **MTProto**:

- **Сервер:** `127.0.0.1`
- **Порт:** `1443`
- **Secret:** значение из главного экрана приложения

## Настройки

| Поле | По умолчанию | Описание |
|------|--------------|----------|
| Host | `127.0.0.1` | Адрес, на котором слушает прокси |
| Port | `1443` | TCP-порт прокси |
| Secret | random 32 hex | Ключ MTProto-обфускации (генерируется автоматически) |
| DC:IP | `2:149.154.167.220, 4:149.154.167.220` | IP, к которым ходим через WSS |
| Verbose logging | off | Подробные логи в `logcat` (тег `tg-mtproto-proxy`) |

> **Не грузит фото/видео?** Удалите в DC:IP всё, кроме `4:149.154.167.220`.
> Если не помогло — удалите оба значения. Эта проблема встречается на аккаунтах
> без Telegram Premium.

## Архитектура

```
app/src/main/java/com/flowseal/tgwsproxy/
├── MainActivity.kt           — Compose-хост и binding к ProxyService
├── ui/
│   ├── MainScreen.kt         — главный экран (start/stop, secret, ссылка, статистика)
│   └── Theme.kt              — Material3 + dynamic colors на Android 12+
└── proxy/
    ├── Constants.kt          — константы протокола (proto-tags, позиции в init и т.д.)
    ├── Config.kt             — ProxyConfig + сериализация в SharedPreferences
    ├── Stats.kt              — потокобезопасные счётчики со StateFlow
    ├── Crypto.kt             — обёртка над AES-CTR (стримовая, держит counter)
    ├── Handshake.kt          — парсинг 64-байтного init и генерация relay-init
    ├── MsgSplitter.kt        — нарезка TCP-потока на отдельные MTProto-пакеты
    ├── RawWebSocket.kt       — TLS+SNI socket к IP DC, ручной WS upgrade и фреймы
    ├── Bridge.kt             — двунаправленный перешифровывающий пайп
    ├── ProxyServer.kt        — accept-цикл, WS-blacklist, fallback на TCP
    └── ProxyService.kt       — foreground-сервис c постоянной нотификацией
```

Соответствие модулям оригинального Python-проекта:

| Python | Kotlin |
|--------|--------|
| `proxy/tg_ws_proxy.py` | `proxy/ProxyServer.kt` |
| `proxy/raw_websocket.py` | `proxy/RawWebSocket.kt` |
| `proxy/bridge.py` | `proxy/Bridge.kt`, `MsgSplitter.kt`, `Handshake.kt`, `Crypto.kt` |
| `proxy/utils.py`, `proxy/stats.py` | `proxy/Constants.kt`, `proxy/Stats.kt` |
| `proxy/config.py` | `proxy/Config.kt` |
| `windows.py` / `linux.py` / `macos.py` | `MainActivity.kt`, `ui/MainScreen.kt`, `proxy/ProxyService.kt` |

### Что реализовано

- MTProto handshake parse + relay-init re-generation (все 3 протокола: abridged / intermediate / padded-intermediate)
- AES-CTR re-encryption обоих направлений со stateful counter
- Raw WebSocket клиент с TLS + SNI на любой IP
- MsgSplitter — разбиение TCP-потока на отдельные WS-фреймы
- TCP-fallback к стандартным IP DC (`DC_DEFAULT_IPS`) при 302/ошибке WSS
- Per-DC cooldown / blacklist при подряд идущих 302
- Foreground service с partial wakelock и нотификацией Stop

### Что не портировано (пока)

- Cloudflare-proxy fallback (`balancer.py` + динамический список доменов)
- Fake-TLS маскировка (`fake_tls.py`, `ee`-secret) — на loopback бессмысленно
- HAProxy PROXY-protocol v1 — только серверная фича
- WS connection pool warmup на старте (соединения создаются по запросу)
- Авто-обновление через GitHub releases

## Permissions

- `INTERNET` — исходящие WSS/TCP к DC
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` — постоянный сервис
- `POST_NOTIFICATIONS` (API 33+) — иконка в шторке, чтобы система не убивала сервис
- `WAKE_LOCK` — partial wake lock на время работы прокси

## Лицензия

[MIT License](https://github.com/Flowseal/tg-ws-proxy/blob/main/LICENSE) — как
и в оригинальном проекте.
