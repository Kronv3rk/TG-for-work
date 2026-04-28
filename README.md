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
