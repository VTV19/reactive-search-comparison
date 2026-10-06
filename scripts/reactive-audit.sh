#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

root="search-webflux/src/main/java"
initializer="$root/ru/hse/search/webflux/CatalogInitializer.java"

fail=0

check_forbidden() {
  local pattern="$1"
  local description="$2"
  local hits
  hits="$(grep -R -nE "$pattern" "$root" --include='*.java' || true)"
  if [ -n "$hits" ]; then
    echo "FAIL: $description"
    echo "$hits"
    fail=1
  fi
}

check_forbidden 'org\.springframework\.web\.client\.RestClient' 'RestClient найден в реактивном модуле'
check_forbidden 'org\.springframework\.data\.mongodb\.core\.MongoTemplate;' 'блокирующий MongoTemplate найден в реактивном модуле'
check_forbidden 'Thread\.sleep\(' 'Thread.sleep найден в реактивном модуле'
check_forbidden '\.(blockFirst|blockLast|blockOptional)\(' 'блокирующий Reactor API найден в реактивном модуле'
check_forbidden '\.toFuture\(\)\.get\(' 'Future.get найден в реактивном модуле'

all_blocks="$(grep -R -nE '\.block\(' "$root" --include='*.java' || true)"
unexpected="$(printf '%s\n' "$all_blocks" | grep -v "^$initializer:" || true)"

if [ -n "$unexpected" ]; then
  echo "FAIL: .block() разрешён только в startup-инициализаторе:"
  echo "$unexpected"
  fail=1
fi

initializer_blocks="$(grep -cE '\.block\(' "$initializer" || true)"
if [ "$initializer_blocks" -ne 1 ]; then
  echo "FAIL: ожидался ровно один startup .block(), найдено: $initializer_blocks"
  fail=1
fi

if grep -R -nE '\.subscribe\(' "$root" --include='*.java' >/tmp/java-search-reactive-subscribe.$$ 2>/dev/null; then
  echo "FAIL: ручной subscribe() в production-коде WebFlux:"
  cat /tmp/java-search-reactive-subscribe.$$
  rm -f /tmp/java-search-reactive-subscribe.$$
  fail=1
else
  rm -f /tmp/java-search-reactive-subscribe.$$ 2>/dev/null || true
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi

echo "PASS: request-path WebFlux не содержит известных блокирующих API."
echo "Допустимая блокирующая граница: CatalogInitializer при startup."
echo "Динамический контроль BlockHound выполняется JUnit-тестом BlockingAuditTest."
