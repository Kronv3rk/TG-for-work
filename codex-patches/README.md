# Как применить изменения Codex к локальному TG-for-work

Ветка `work` на GitHub может быть идентична `main`, если вы пушите свою локальную копию без коммитов Codex. Этот patch переносит изменения из контейнерной ветки Codex в ваш локальный репозиторий.


## Если `git checkout work` показывает много строк `M ...`

Это значит, что изменения уже лежат в вашем рабочем дереве, но ещё не закоммичены. В таком случае patch-файл не нужен: нужно создать коммит и отправить его.

```powershell
git switch work
git status
git add -A
git commit -m "Improve proxy stability and build tooling"
git push origin work
```

После этого GitHub сравнение `base: main` -> `compare: work` должно показать изменения.

## Если локальная `main` отслеживает `origin/work`

Если после `git checkout main` Git пишет `Your branch is up to date with 'origin/work'`, исправьте upstream ветки `main`:

```powershell
git switch main
git branch --set-upstream-to=origin/main main
git pull --ff-only origin main
```

## Если тег релиза уже отправлен до попадания изменений

Тег указывает на старый коммит и не подтянет будущие изменения автоматически. Удалите ошибочный тег и создайте новый только после merge изменений в `main`:

```powershell
git push origin --delete v1.6.2
git tag -d v1.6.2
```

## Применить patch

Скопируйте файл `codex-patches/tg-for-work-improvements.patch` в корень вашего локального репозитория и выполните:

```powershell
git checkout work
git pull origin work
git am codex-patches/tg-for-work-improvements.patch
git push origin work
```

После этого GitHub сравнение `base: main` -> `compare: work` должно показать изменения.

## Если `git am` остановился на конфликте

```powershell
git status
# исправьте конфликтующие файлы
git add <исправленные-файлы>
git am --continue
```

Если хотите отменить применение patch:

```powershell
git am --abort
```

## Создать релиз APK

После merge изменений в `main` отправьте новый тег:

```powershell
git checkout main
git pull origin main
git tag v1.6.2
git push origin v1.6.2
```

Тег запустит GitHub Actions workflow `Android Release`.
