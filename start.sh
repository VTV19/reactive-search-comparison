#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

command -v docker >/dev/null || { echo "Docker не установлен."; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker Engine недоступен. Запустите Docker Desktop."; exit 1; }
docker compose version >/dev/null

missing=0
for module in search-core search-mvc search-webflux metadata-service; do
  artifact="$module/target/$module.jar"
  if [ ! -s "$artifact" ]; then
    echo "Отсутствует собранный файл: $artifact"
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then
  echo "Сначала выполните сборку и Java-тесты: bash scripts/verify.sh --java"
  exit 1
fi

mkdir -p reports
echo "Запуск основного каталога из собранных JAR..."
if ! docker compose -p java-search-release up -d --force-recreate --wait --wait-timeout 180 2>&1 | tee reports/start.log; then
  docker compose -p java-search-release logs --no-color > reports/runtime.log 2>&1 || true
  echo "Запуск не завершён. Диагностика: reports/start.log и reports/runtime.log"
  exit 1
fi
if ! bash scripts/smoke.sh 2>&1 | tee -a reports/start.log; then
  docker compose -p java-search-release logs --no-color > reports/runtime.log 2>&1 || true
  echo "Контейнеры запущены, но проверка HTTP завершилась ошибкой."
  echo "Диагностика: reports/start.log и reports/runtime.log"
  exit 1
fi
printf '\nREADY 3.1.0\nMVC:     http://localhost:18081\nWebFlux: http://localhost:18082\n'
