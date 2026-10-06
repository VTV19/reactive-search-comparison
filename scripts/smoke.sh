#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
for entry in '18081 mvc' '18082 webflux'; do
  read -r port implementation <<< "$entry"
  base="http://127.0.0.1:$port"
  curl -fsS --max-time 6 "$base/actuator/health/readiness" > "$temporary/health"
  grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$temporary/health"
  curl -fsS --max-time 5 "$base/api/info" > "$temporary/info"
  grep -Eq "\"implementation\"[[:space:]]*:[[:space:]]*\"$implementation\"" "$temporary/info"
  grep -Eq '"version"[[:space:]]*:[[:space:]]*"3.1.0"' "$temporary/info"
  curl -fsS --max-time 5 "$base/" > "$temporary/page"
  grep -q 'assets/app-v3.js' "$temporary/page"
  curl -fsS --max-time 5 "$base/assets/app-v3.js" > "$temporary/script"
  grep -q 'AbortController' "$temporary/script"
  curl -fsS --max-time 12 "$base/api/search?limit=1&enrich=false" > "$temporary/search"
  grep -q '"items"' "$temporary/search"
  curl -fsS --max-time 12 "$base/api/search?q=java&limit=1&enrich=false" > "$temporary/text-search"
  grep -q '"items"' "$temporary/text-search"
  echo "OK: $implementation — версия 3.1.0, база, страница, JavaScript и поисковый API"
done
