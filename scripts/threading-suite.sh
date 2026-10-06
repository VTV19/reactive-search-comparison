#!/usr/bin/env bash
set -u
set -o pipefail
cd "$(dirname "$0")/.."

RATES=(${RATES:-100 900 1400})
REPEATS="${REPEATS:-3}"
WARMUP_SECONDS="${WARMUP_SECONDS:-45}"
DURATION_SECONDS="${DURATION_SECONDS:-90}"
COOLDOWN_SECONDS="${COOLDOWN_SECONDS:-30}"
VUS="${VUS:-768}"
SEARCH_LIMIT="${SEARCH_LIMIT:-512}"
MONGO_POOL_SIZE="${MONGO_POOL_SIZE:-256}"
P95_MS="${P95_MS:-3000}"
TOMCAT_MAX_THREADS="${TOMCAT_MAX_THREADS:-200}"
REACTOR_IO_WORKERS="${REACTOR_IO_WORKERS:-4}"

mkdir -p reports/load
suite="$PWD/reports/load/threading-suite-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$suite"
status_file="$suite/status.tsv"
echo -e "rate\trepeat\tvariant\texit_code\tresult_dir" > "$status_file"

if pgrep -f 'scripts/load-test.sh' >/dev/null 2>&1; then
  echo "Уже выполняется load-test.sh." >&2
  exit 1
fi
if [ -d reports/load/.lock ]; then
  echo "Существует reports/load/.lock. Удаляйте его только если load-test.sh точно не запущен." >&2
  exit 1
fi

run_case() {
  local rate="$1" repeat="$2" variant="$3"
  local case_log="$suite/${rate}-${repeat}-${variant}.log"
  echo "=== rate=$rate repeat=$repeat variant=$variant ===" | tee -a "$case_log"

  set +e
  RATE="$rate" \
  WARMUP_SECONDS="$WARMUP_SECONDS" \
  DURATION_SECONDS="$DURATION_SECONDS" \
  VUS="$VUS" \
  SEARCH_LIMIT="$SEARCH_LIMIT" \
  MONGO_POOL_SIZE="$MONGO_POOL_SIZE" \
  P95_MS="$P95_MS" \
  TOMCAT_MAX_THREADS="$TOMCAT_MAX_THREADS" \
  REACTOR_IO_WORKERS="$REACTOR_IO_WORKERS" \
    bash scripts/load-test.sh "$variant" delayed 2>&1 | tee -a "$case_log"
  code="${PIPESTATUS[0]}"
  set -e

  result_dir="$(grep 'Материалы испытания:' "$case_log" | tail -1 | sed 's/^Материалы испытания: //' || true)"
  echo -e "${rate}\t${repeat}\t${variant}\t${code}\t${result_dir:-NOT_FOUND}" >> "$status_file"

  if [ "$code" -ne 0 ] && [ "$code" -ne 99 ]; then
    echo "Техническая ошибка $code: серия остановлена." >&2
    exit "$code"
  fi

  sleep "$COOLDOWN_SECONDS"
}

orders=(
  "mvc mvc-virtual webflux"
  "webflux mvc-virtual mvc"
  "mvc-virtual webflux mvc"
)

for rate in "${RATES[@]}"; do
  for ((rep=1; rep<=REPEATS; rep++)); do
    order="${orders[$(((rep-1)%3))]}"
    for variant in $order; do
      run_case "$rate" "$rep" "$variant"
    done
  done
done

echo
echo "THREADING SUITE FINISHED"
echo "$status_file"
cat "$status_file"
