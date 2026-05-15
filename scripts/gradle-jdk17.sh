#!/usr/bin/env bash
set -euo pipefail

find_jdk17() {
  if [[ -n "${JAVA17_HOME:-}" && -x "$JAVA17_HOME/bin/java" ]]; then
    printf '%s\n' "$JAVA17_HOME"
    return 0
  fi

  for candidate in \
    /root/.local/share/mise/installs/java/17.0.2 \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/temurin-17-jdk-amd64 \
    /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  if command -v mise >/dev/null 2>&1; then
    local mise_java
    mise_java="$(MISE_TRUSTED_CONFIG_PATHS="${MISE_TRUSTED_CONFIG_PATHS:-}:$PWD/.mise.toml" mise where java@17.0.2 2>/dev/null || true)"
    if [[ -n "$mise_java" && -x "$mise_java/bin/java" ]]; then
      printf '%s\n' "$mise_java"
      return 0
    fi
  fi

  return 1
}

find_gradle() {
  if [[ -n "${GRADLE_CMD:-}" && -x "$GRADLE_CMD" ]]; then
    printf '%s\n' "$GRADLE_CMD"
    return 0
  fi

  for candidate in \
    /root/.local/share/mise/installs/gradle/8.14.4/gradle-8.14.4/bin/gradle \
    /opt/gradle/gradle-8.14.4/bin/gradle; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  if command -v gradle >/dev/null 2>&1; then
    command -v gradle
    return 0
  fi

  return 1
}

if ! JDK17_HOME="$(find_jdk17)"; then
  cat >&2 <<'MESSAGE'
JDK 17 was not found. Install JDK 17 or set JAVA17_HOME to a JDK 17 home directory.
MESSAGE
  exit 1
fi

if ! GRADLE_BIN="$(find_gradle)"; then
  cat >&2 <<'MESSAGE'
Gradle was not found. Install Gradle 8.14.4 or set GRADLE_CMD to a Gradle executable.
MESSAGE
  exit 1
fi

export JAVA_HOME="$JDK17_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
exec "$GRADLE_BIN" "$@"
