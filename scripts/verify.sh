#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$#" -gt 1 ]; then
  echo "Использование: bash scripts/verify.sh [--java|--full]" >&2
  exit 2
fi
mode="${1:---java}"
case "$mode" in
  --java|--full) ;;
  --help|-h)
    echo "Использование: bash scripts/verify.sh [--java|--full]"
    echo "--java: сборка и все JUnit-тесты с MongoDB; режим по умолчанию."
    echo "--full: дополнительно браузер, перезапуски и проверки восстановления."
    exit 0
    ;;
  *)
    echo "Неизвестный режим: $mode" >&2
    echo "Использование: bash scripts/verify.sh [--java|--full]" >&2
    exit 2
    ;;
esac
mkdir -p reports
exec > >(tee reports/verify.log) 2>&1

command -v docker >/dev/null || { echo "Docker не установлен."; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker Engine недоступен. Запустите Docker Desktop."; exit 1; }
docker compose version >/dev/null

echo "Проверка реактивного request-path на известные блокирующие API"
bash scripts/reactive-audit.sh

test_stack=(docker compose -p java-search-release-test -f compose.test.yaml)
production=(docker compose -p java-search-release -f compose.yaml)

# clean заменяет JAR-файлы; останавливаются только приложения текущей поставки.
# Основной том MongoDB не подключается к тестовому стенду.
"${production[@]}" stop mvc webflux metadata
docker volume create java-search_maven-cache >/dev/null
"${test_stack[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
rm -f reports/browser.xml reports/lifecycle.log reports/lifecycle-markers.json

cleanup() {
  status=$?
  trap - EXIT
  "${test_stack[@]}" logs --no-color > reports/test-runtime.log 2>&1 || true
  "${test_stack[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  if [ "$status" -ne 0 ]; then
    echo "Проверка остановлена с ошибкой. Основные приложения автоматически не запускаются."
    echo "Диагностика: reports/verify.log и reports/test-runtime.log"
  fi
  exit "$status"
}
trap cleanup EXIT

if [ "$mode" = "--java" ]; then
  echo "Сборка обеих реализаций и все JUnit-тесты с отдельной MongoDB"
else
  echo "1/5 — Компиляция обеих реализаций и JUnit с отдельной MongoDB"
fi
"${test_stack[@]}" up -d --wait --wait-timeout 180 mongo
"${test_stack[@]}" run --rm --no-deps builder
if [ "$mode" = "--java" ]; then
  echo "Сборка и Java-тесты завершены успешно. Запуск: bash start.sh"
  echo "Браузерные испытания и проверки перезапусков входят в отдельный режим --full."
  exit 0
fi

echo "2/5 — Запуск собранных JAR и проверка готовности"
"${test_stack[@]}" up -d --force-recreate --wait --wait-timeout 180 metadata mvc webflux

echo "3/5 — Браузерные и сквозные проверки MVC и WebFlux"
"${test_stack[@]}" build browser
"${test_stack[@]}" run --rm --no-deps browser

echo "4/5 — Сохранение данных, удаление и повторное создание индекса"
"${test_stack[@]}" run --rm --no-deps browser python lifecycle.py prepare
"${test_stack[@]}" stop mvc webflux
"${test_stack[@]}" exec -T mongo mongosh --quiet --eval \
  "['acceptance_mvc','acceptance_webflux'].forEach(n => db.getSiblingDB(n).documents.dropIndex('documents_text'))"
"${test_stack[@]}" up -d --wait --wait-timeout 180 mvc webflux
"${test_stack[@]}" run --rm --no-deps browser python lifecycle.py persisted

echo "5/5 — Отказ и восстановление основной базы и внешнего справочника"
"${test_stack[@]}" stop mongo
"${test_stack[@]}" run --rm --no-deps browser python lifecycle.py database-down
"${test_stack[@]}" start mongo
"${test_stack[@]}" up -d --wait --wait-timeout 180 mongo mvc webflux
"${test_stack[@]}" run --rm --no-deps browser python lifecycle.py recovered
"${test_stack[@]}" stop metadata
"${test_stack[@]}" run --rm --no-deps browser python lifecycle.py metadata-down
"${test_stack[@]}" up -d --wait --wait-timeout 180 metadata
"${test_stack[@]}" run --rm --no-deps browser python lifecycle.py metadata-recovered

echo "Все этапы проверки завершены успешно."
