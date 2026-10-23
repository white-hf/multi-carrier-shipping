#!/usr/bin/env bash
# Check local readiness only; do not obtain rates or create shipments.
set -euo pipefail
app_port="${1:-${SERVER_PORT:-8080}}"
management_port="${2:-${MANAGEMENT_SERVER_PORT:-9091}}"
for port in "$app_port" "$management_port"; do
  if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
    echo "Usage: ./scripts/check-workflow.sh [app-port] [management-port]" >&2; exit 2
  fi
done
curl --fail --silent --show-error --max-time 5 "http://127.0.0.1:$management_port/actuator/health"
printf '\n'
curl --fail --silent --show-error --max-time 5 --output /dev/null "http://127.0.0.1:$app_port/"
printf 'Frontend is reachable: http://127.0.0.1:%s/\n' "$app_port"
