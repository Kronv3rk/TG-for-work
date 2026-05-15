# TG-for-work agent guide

## Project overview
- Android/Kotlin app that runs a local MTProto proxy in a foreground service.
- Core proxy code lives in `app/src/main/java/com/flowseal/tgwsproxy/proxy/`.
- UI and foreground-service entry points live in `MainActivity.kt` and `ProxyService.kt`.

## Canonical checks
<<<<<<< ours
- Preferred local build: `gradle :app:assembleDebug`.
- If the local JVM is newer than the Kotlin/Gradle toolchain supports, rerun with JDK 17 and document the environment limitation.
=======
- Preferred local build: `scripts/gradle-jdk17.sh :app:assembleDebug`.
- The repository pins Java 17 in `.mise.toml`; use `JAVA17_HOME` if your local JDK 17 is not managed by mise.
>>>>>>> theirs
- For release workflow changes, inspect `.github/workflows/android-release.yml` and keep JDK 17 unless intentionally upgrading the Android Gradle/Kotlin toolchain.

## Stability review checklist
- Bound all background concurrency; avoid unbounded thread pools and uncontrolled queues.
- Close sockets/streams on every failed connect, handshake, route switch, or service stop path.
- Keep network timeouts finite during route probing; only long-lived bridge reads may block until socket close.
- Protect protocol parsers from unbounded frame/header/body allocation.
- Preserve foreground-service behavior and Android permission compatibility across API 24+.

## Code style
- Kotlin official style, small focused changes, no try/catch around imports.
- Prefer explicit failure messages in `lastMessage` when a route is unavailable.
- Do not add dependencies unless the task explicitly requires them.

## Agent configuration references
- AGENTS.md format and examples: https://github.com/agentsmd/agents.md
- Android Studio AGENTS.md guidance: https://developer.android.com/studio/gemini/agent-files
- Codex AGENTS.md vs SKILL.md guidance: https://codex-best-practices-d67bea.pages.oit.duke.edu/best-practices/agents.html
