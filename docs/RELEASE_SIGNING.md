# Подпись и выпуск Android APK

## Состояние релизного процесса

- Релизный APK должен быть подписан тем же сертификатом, что и v1.9.0. Иначе Android отклонит обновление.
- Резервный keystore исключён из текущего дерева исходников. Это не удаляет его из истории Git; исторические копии ключа доступны тем, кто имел доступ к репозиторию.
- Автоматическая смена ключа не выполняется: она сломает установку поверх прежней версии, особенно на старых Android. При возможной утечке ключа потребуется отдельный план миграции.

## Перед первым запуском нового release workflow

Добавьте в Settings → Secrets and variables → Actions все четыре значения:

- ANDROID_KEYSTORE_BASE64 — исходный релизный .jks в Base64.
- ANDROID_KEYSTORE_PASSWORD — пароль хранилища.
- ANDROID_KEY_ALIAS — псевдоним ключа.
- ANDROID_KEY_PASSWORD — пароль ключа.

Нужен именно прежний релизный ключ, а не новый случайно созданный.
Не помещайте keystore, его Base64 или пароли в Issues, коммиты, PR, отчёты либо логи.
Перед удалением исторических источников ключа убедитесь, что секреты настроены и есть защищённая резервная копия.

## Локальная проверка без GitHub Actions

1. python3 -m unittest discover -s scripts -p test_release_preflight.py
2. python3 scripts/release_preflight.py --tag v1.9.1 --event-name workflow_dispatch --ref-type branch --ref-name main

При наличии Android SDK:

3. ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug

Для проверки готового APK:

4. $ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs path/to/app.apk

Сравните Signer #1 certificate SHA-256 digest с опубликованным подписанным v1.9.0.

## Правила публикации

1. Обновить одновременно versionName и versionCode.
2. Провести локальное тестирование и проверить Android APK.
3. Выпускать только из ветки main или по тегу, указывающему на коммит main.
4. Если отсутствуют signing secrets, неверен тег, релиз уже опубликован либо сертификат не совпадает с v1.9.0, workflow останавливается.
5. Публиковать APK вместе с SHA-256. Запрещено заменять APK внутри уже опубликованного релиза.

Пока не проведён полный релизный прогон с настроенными GitHub Secrets,
фактическая возможность выпуска обновляющегося APK не подтверждена.
