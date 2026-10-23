#!/usr/bin/env bash
# Foreground launcher: local credentials stay in .env, outside version control.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"
case "${1:-}" in
  ""|--check-config) ;;
  *) echo "Usage: ./scripts/start-workflow.sh [--check-config]" >&2; exit 2 ;;
esac
configuration_file="${SHIPPING_ENV_FILE:-$PROJECT_ROOT/.env}"
if [[ -n "${SHIPPING_ENV_FILE:-}" && ! -f "$configuration_file" ]]; then
  echo "The specified SHIPPING_ENV_FILE does not exist." >&2; exit 1
fi
if [[ -f "$configuration_file" ]]; then
  set -a
  # This is a trusted local shell configuration, not an automatically downloaded file.
  source "$configuration_file"
  set +a
fi
for setting in SHIPPING_PROVIDER_BASE_URL SHIPPING_PROVIDER_USERNAME SHIPPING_PROVIDER_PASSWORD; do
  if [[ -z "${!setting:-}" ]]; then
    echo "Missing $setting. Configure .env using .env.example, or export it in your shell." >&2
    exit 1
  fi
done
# macOS may have an unregistered Homebrew JDK while /usr/bin/java is only a stub.
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
      if [[ -x "$candidate/bin/java" ]]; then JAVA_HOME="$candidate"; break; fi
    done
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! version_output="$(java -version 2>&1)"; then
  echo "Java is unavailable. Install JDK 17 and set JAVA_HOME." >&2; exit 1
fi
java_major="$(printf '%s\n' "$version_output" | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
if [[ ! "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 17 )); then
  echo "JDK 17 or newer is required. Set JAVA_HOME to the intended JDK." >&2; exit 1
fi
if [[ "${1:-}" == --check-config ]]; then
  echo "Required provider settings are present; Java $java_major is available. No sandbox call was made."
  exit 0
fi
export SERVER_PORT="${SERVER_PORT:-8080}"
export MANAGEMENT_SERVER_PORT="${MANAGEMENT_SERVER_PORT:-9091}"
echo "Building the application (tests are run separately)..."
./mvnw -q -Dmaven.test.skip=true package
JAR_FILE=target/multi-courier-aggregation-service-1.0.0.jar
if [[ ! -f "$JAR_FILE" ]]; then echo "Application JAR was not produced." >&2; exit 1; fi
printf 'Open http://127.0.0.1:%s/ after startup. Stop with Ctrl+C.\n' "$SERVER_PORT"
echo "Starting the workflow profile; local SQLite state is retained between runs."
exec java -jar "$JAR_FILE" --spring.profiles.active=workflow
