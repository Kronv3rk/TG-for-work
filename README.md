# TG-for-work

Android-порт схемы Flowseal tg-ws-proxy для запуска локального MTProto proxy на телефоне.
Приложение открывает Telegram с готовой `tg://proxy` ссылкой, держит прокси в foreground service
и показывает постоянное уведомление с быстрыми действиями.

## Быстрый старт

Локально нужен установленный JDK 17, Android SDK и Gradle:

```bash
gradle :app:assembleDebug
```

Если SDK не настроен, укажите `ANDROID_SDK_ROOT` и примите лицензии Android SDK.

## Релизы через GitHub Actions

APK собирается в GitHub Actions и публикуется на странице Releases при пуше тега:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Workflow также можно запустить вручную через Actions -> Android Release, указав тег вида `v1.0.0`.

Для signed release APK добавьте в GitHub repository secrets:

- `ANDROID_KEYSTORE_BASE64` - keystore `.jks`, закодированный в Base64
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Если signing secrets не настроены, workflow публикует debug-signed APK, пригодный для установки и тестирования.

## Возможности APK

- локальный MTProto proxy на `127.0.0.1:1443`;
- Fake TLS secret `ee...` с маскирующим доменом по умолчанию;
- Cloudflare fallback по списку доменов из оригинального `Flowseal/tg-ws-proxy`;
- прямой TCP fallback к Telegram DC, если WebSocket/Cloudflare недоступны;
- постоянное Android-уведомление: открыть Telegram, скопировать ссылку, остановить прокси;
- интерактивный UI: запуск, проверка, копирование, перезапуск, переключатели Fake TLS и Cloudflare.

## Использование Android APK

1. Установите APK из Releases.
2. Откройте приложение и нажмите `Открыть Telegram`.
3. Telegram должен открыть экран подключения MTProto proxy.
4. Подтвердите прокси `127.0.0.1:1443`.
5. Не убирайте постоянное уведомление, если хотите держать прокси активным в фоне.

Если Telegram не открылся автоматически, нажмите `Скопировать`, отправьте ссылку себе в Telegram и откройте её из клиента.

На Android нет desktop tray, поэтому его роль выполняет foreground notification.
