# TG-for-work

Минимальный Android-проект для сборки APK.

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

## Использование Android APK

1. Установите APK из Releases.
2. Откройте приложение и нажмите `Открыть Telegram`.
3. Telegram должен открыть экран подключения MTProto proxy.
4. Подтвердите прокси `127.0.0.1:1443`.

Если Telegram не открылся автоматически, нажмите `Скопировать`, отправьте ссылку себе в Telegram и откройте её из клиента.
