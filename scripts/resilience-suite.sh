#!/usr/bin/env bash
set -u
set -o pipefail
cd "$(dirname "$0")/.."

RATE="${RATE:-100}"
DURATION_SECONDS="${DURATION_SECONDS:-90}"
VUS="${VUS:-256}"
SEARCH_LIMIT="${SEARCH_LIMIT:-64}"
MONGO_POOL_SIZE="${MONGO_POOL_SIZE:-64}"
P95_MS="${P95_MS:-3000}"
COOLDOWN_SECONDS="${COOLDOWN_SECONDS:-20}"

mkdir -p reports/load
suite="$PWD/reports/load/resilience-suite-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$suite"
status_file="$suite/status.tsv"
echo -e "repeat\tvariant\texit_code\tresult_dir" > "$status_file"

run_case() {
  local rep="$1" variant="$2"
  local case_log="$suite/${rep}-${variant}.log"

  set +e
  RATE="$RATE" WARMUP_SECONDS=1 DURATION_SECONDS="$DURATION_SECONDS" VUS="$VUS" \
  SEARCH_LIMIT="$SEARCH_LIMIT" MONGO_POOL_SIZE="$MONGO_POOL_SIZE" P95_MS="$P95_MS" \
    bash scripts/load-test.sh "$variant" resilience 2>&1 | tee "$case_log"
  code="${PIPESTATUS[0]}"
  set -e

  result_dir="$(grep 'Материалы испытания:' "$case_log" | tail -1 | sed 's/^Материалы испытания: //' || true)"
  echo -e "${rep}\t${variant}\t${code}\t${result_dir:-NOT_FOUND}" >> "$status_file"

  if [ "$code" -ne 0 ] && [ "$code" -ne 99 ]; then
    echo "Техническая ошибка $code: серия остановлена." >&2
    exit "$code"
  fi
  sleep "$COOLDOWN_SECONDS"
}

run_case 1 mvc
run_case 1 webflux
run_case 2 webflux
run_case 2 mvc
run_case 3 mvc
run_case 3 webflux

echo
echo "RESILIENCE SUITE FINISHED"
echo "$status_file"
cat "$status_file"
