#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  echo "Использование: bash scripts/load-test.sh mvc|mvc-virtual|webflux database|http|delayed|resilience"
  echo "Параметры среды: RATE=20 WARMUP_SECONDS=60 DURATION_SECONDS=180 VUS=128"
  echo "Дополнительно: P95_MS=1000 SEARCH_LIMIT=64 MONGO_POOL_SIZE=64 TOMCAT_MAX_THREADS=200 REACTOR_IO_WORKERS=4"
}
if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then usage; exit 0; fi
[ "$#" -eq 2 ] || { usage >&2; exit 2; }
export VARIANT="$1" PROFILE="$2"
case "$VARIANT" in
  mvc)
    export EXPECTED_IMPLEMENTATION="mvc"
    export LOAD_JAR="./search-mvc/target/search-mvc.jar"
    export MVC_VIRTUAL_THREADS="false"
    export MVC_KEEP_ALIVE="false"
    export JVM_EXTRA_OPTS=""
    ;;
  mvc-virtual)
    export EXPECTED_IMPLEMENTATION="mvc"
    export LOAD_JAR="./search-mvc/target/search-mvc.jar"
    export MVC_VIRTUAL_THREADS="true"
    export MVC_KEEP_ALIVE="true"
    export JVM_EXTRA_OPTS="-Djdk.tracePinnedThreads=short"
    ;;
  webflux)
    export EXPECTED_IMPLEMENTATION="webflux"
    export LOAD_JAR="./search-webflux/target/search-webflux.jar"
    export MVC_VIRTUAL_THREADS="false"
    export MVC_KEEP_ALIVE="false"
    export JVM_EXTRA_OPTS=""
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
case "$PROFILE" in
  database|http) metadata_mode=normal; k6_script=/scripts/search.js;;
  delayed) metadata_mode=delayed; k6_script=/scripts/search.js;;
  resilience) metadata_mode=normal; k6_script=/scripts/resilience.js;;
  *) usage >&2; exit 2;;
esac
export RATE="${RATE:-20}" WARMUP_SECONDS="${WARMUP_SECONDS:-60}"
export DURATION_SECONDS="${DURATION_SECONDS:-180}" VUS="${VUS:-128}"
export P95_MS="${P95_MS:-1000}" SEARCH_LIMIT="${SEARCH_LIMIT:-64}"
export MONGO_POOL_SIZE="${MONGO_POOL_SIZE:-64}" TOMCAT_MAX_THREADS="${TOMCAT_MAX_THREADS:-200}"
export REACTOR_IO_WORKERS="${REACTOR_IO_WORKERS:-4}"
integer() {
  case "$2" in ''|*[!0-9]*) echo "$1: требуется целое число." >&2; exit 2;; esac
  # Decimal conversion avoids treating a leading zero as an octal number.
  value=$((10#$2))
  [ "$value" -ge "$3" ] && [ "$value" -le "$4" ] || {
    echo "$1: допустимо $3..$4." >&2; exit 2;
  }
}
integer RATE "$RATE" 1 3000
integer WARMUP_SECONDS "$WARMUP_SECONDS" 1 600
integer DURATION_SECONDS "$DURATION_SECONDS" 5 3600
integer VUS "$VUS" 1 2048
integer P95_MS "$P95_MS" 1 10000
integer SEARCH_LIMIT "$SEARCH_LIMIT" 1 2048
integer MONGO_POOL_SIZE "$MONGO_POOL_SIZE" 1 512
integer TOMCAT_MAX_THREADS "$TOMCAT_MAX_THREADS" 1 2000
integer REACTOR_IO_WORKERS "$REACTOR_IO_WORKERS" 1 64
for command in docker curl shasum; do
  command -v "$command" >/dev/null || { echo "Требуется $command." >&2; exit 1; }
done
docker info >/dev/null 2>&1 || { echo "Docker Engine недоступен." >&2; exit 1; }
if [ -n "$(docker ps -q --filter label=com.docker.compose.project=java-search-release)" ]; then
  echo "Перед измерениями остановите основной стенд: bash stop.sh" >&2; exit 1
fi
for module in search-core search-mvc search-webflux metadata-service; do
  [ -s "$module/target/$module.jar" ] || {
    echo "Нет $module/target/$module.jar. Выполните bash scripts/verify.sh --java." >&2; exit 1;
  }
done
# Concurrent experiments in one working tree would change the same dependency mode.
mkdir -p reports/load
lock=reports/load/.lock
mkdir "$lock" 2>/dev/null || {
  echo "Нагрузочный цикл уже выполняется. Проверьте reports/load/.lock." >&2; exit 1;
}
export LOCAL_UID="$(id -u)" LOCAL_GID="$(id -g)"
export LOAD_OUTPUT="$PWD/reports/load/$(date -u +%Y%m%dT%H%M%SZ)-$VARIANT-$PROFILE-r$RATE-$$"
mkdir -p "$LOAD_OUTPUT"
stack=(docker compose -p java-search-load -f compose.load.yaml)
sampler=""
cleanup() {
  status=$?
  trap - EXIT INT TERM
  touch "$LOAD_OUTPUT/monitor.stop"
  if [ -n "$sampler" ]; then wait "$sampler" 2>/dev/null || true; fi
  "${stack[@]}" logs --no-color > "$LOAD_OUTPUT/runtime.log" 2>&1 || true
  "${stack[@]}" ps -a > "$LOAD_OUTPUT/containers.txt" 2>&1 || true
  ids="$(docker ps -aq --filter label=com.docker.compose.project=java-search-load)"
  if [ -n "$ids" ]; then
    docker inspect --format '{{.Name}} OOM={{.State.OOMKilled}} Exit={{.State.ExitCode}} Image={{.Image}}' \
      $ids > "$LOAD_OUTPUT/container-state.txt" 2>&1 || true
  fi
  "${stack[@]}" down --remove-orphans >/dev/null 2>&1 || true
  rm -f "$LOAD_OUTPUT/monitor.stop"
  rmdir "$lock" 2>/dev/null || true
  printf '%s\n' "$status" > "$LOAD_OUTPUT/exit-code.txt"
  echo "Материалы испытания: $LOAD_OUTPUT"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Only the dedicated load-test stack is stopped; the application data volume is untouched.
"${stack[@]}" down --remove-orphans >/dev/null 2>&1 || true
"${stack[@]}" config > "$LOAD_OUTPUT/compose-resolved.yaml"
{
  date -u '+UTC %Y-%m-%dT%H:%M:%SZ'
  uname -sm
  docker version --format 'Docker server: {{.Server.Version}}'
  docker compose version
  docker info --format 'Docker VM: CPUs={{.NCPU}} MemoryBytes={{.MemTotal}} Architecture={{.Architecture}}'
  printf 'variant=%s implementation=%s profile=%s rate=%s warmup=%s duration=%s vus=%s search_limit=%s mongo_pool=%s tomcat_max_threads=%s reactor_io_workers=%s virtual_threads=%s p95_ms=%s\n' \
    "$VARIANT" "$EXPECTED_IMPLEMENTATION" "$PROFILE" "$RATE" "$WARMUP_SECONDS" "$DURATION_SECONDS" "$VUS" \
    "$SEARCH_LIMIT" "$MONGO_POOL_SIZE" "$TOMCAT_MAX_THREADS" "$REACTOR_IO_WORKERS" "$MVC_VIRTUAL_THREADS" "$P95_MS"
} > "$LOAD_OUTPUT/environment.txt"
shasum -a 256 */target/*.jar tests/load/*.js tests/load/queries.json data/documents.json compose.load.yaml scripts/load-test.sh \
  > "$LOAD_OUTPUT/artifacts.sha256"
"${stack[@]}" run --rm --no-deps k6 version > "$LOAD_OUTPUT/k6-version.txt"
echo "Проверка классификации ответов..."
if ! "${stack[@]}" run --rm --no-deps k6 run /scripts/response-check.test.js \
    > "$LOAD_OUTPUT/contract-test.log" 2>&1; then
  echo "Проверка классификации не пройдена. См. contract-test.log." >&2
  exit 1
fi
"${stack[@]}" up -d mongo
ready=false
for ((i=0; i<90; i++)); do
  if "${stack[@]}" exec -T mongo mongosh --quiet --eval \
    'quit(db.adminCommand({ping: 1}).ok ? 0 : 1)' >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 2
done
[ "$ready" = true ] || { echo "MongoDB не готова." >&2; exit 1; }
"${stack[@]}" up -d --no-deps metadata app

wait_http() {
  local url="$1"
  for ((i=0; i<90; i++)); do
    if curl --silent --fail --max-time 3 "$url" >/dev/null; then return 0; fi
    sleep 2
  done
  echo "Сервис не готов: $url" >&2; return 1
}
wait_http http://127.0.0.1:29080/actuator/health
wait_http http://127.0.0.1:28081/actuator/health/readiness
# The load database is persistent; compare with the intended fixture, not merely
# with a baseline learned from whatever data happened to remain in MongoDB.
echo "Проверка исходного корпуса нагрузочного стенда..."
if ! "${stack[@]}" exec -T mongo mongosh --quiet --eval '
const expected = JSON.parse(require("fs").readFileSync("/fixtures/documents.json", "utf8"));
if (!Array.isArray(expected) || expected.length === 0 ||
    new Set(expected.map(row => row.id)).size !== expected.length) {
    throw new Error("Invalid reference catalogue");
}
const database = db.getSiblingDB("load_catalog");
const actualCount = database.documents.countDocuments({});
const report = {
    database: database.getName(),
    fixture: "data/documents.json",
    expected_count: expected.length,
    actual_count: actualCount,
    matches: false
};
// A large leftover corpus must fail before loading all its documents into memory.
if (actualCount !== expected.length) {
    report.reason = "document_count";
    print(JSON.stringify(report));
    quit(1);
}
const fields = ["title", "text", "category", "sourceUrl"];
const actual = new Map(database.documents.find({}).toArray().map(row => [row._id, row]));
const mismatch = expected.find(row => {
    const stored = actual.get(row.id);
    return !stored || fields.some(field => stored[field] !== row[field]) ||
        stored.version == null || Number(stored.version) !== 0;
});
if (mismatch) {
    report.reason = "document_content_or_version";
    report.document_id = mismatch.id;
    print(JSON.stringify(report));
    quit(1);
}
report.matches = true;
print(JSON.stringify(report));
' > "$LOAD_OUTPUT/dataset-check.json" 2> "$LOAD_OUTPUT/dataset-check.log"; then
  cat "$LOAD_OUTPUT/dataset-check.json" >&2
  cat "$LOAD_OUTPUT/dataset-check.log" >&2
  echo "Корпус load_catalog не совпадает с data/documents.json. Нагрузка не запущена." >&2
  echo "Данные не удалены. Порядок сброса только тестового тома: docs/LOAD_TESTING.md." >&2
  exit 1
fi
cat "$LOAD_OUTPUT/dataset-check.json"

curl -fsS --max-time 5 "http://127.0.0.1:29080/admin/mode?mode=$metadata_mode" -X POST \
  > "$LOAD_OUTPUT/metadata-mode.json"
curl -fsS --max-time 5 http://127.0.0.1:28081/api/info > "$LOAD_OUTPUT/application.json"

snapshot() {
  "${stack[@]}" exec -T mongo mongosh --quiet --eval \
    'print(EJSON.stringify(db.getSiblingDB("load_catalog").documents.find().sort({_id:1}).toArray()))'
}
snapshot > "$LOAD_OUTPUT/dataset-before.json"
(cd "$LOAD_OUTPUT" && shasum -a 256 dataset-before.json) > "$LOAD_OUTPUT/dataset.sha256"

sample_resources() (
  trap - EXIT INT TERM
  while [ ! -e "$LOAD_OUTPUT/monitor.stop" ]; do
    stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    ids="$(docker ps -q --filter label=com.docker.compose.project=java-search-load)"
    if [ -n "$ids" ]; then
      docker stats --no-stream --format '{{json .}}' $ids 2>>"$LOAD_OUTPUT/monitor.log" |
        while IFS= read -r sample; do
          printf '{"utc":"%s","stats":%s}\n' "$stamp" "$sample"
        done >> "$LOAD_OUTPUT/resources.jsonl" || true
    fi
    for metric in jvm.threads.live jvm.memory.used jvm.gc.pause search.active process.cpu.usage; do
      metric_url="http://127.0.0.1:28081/actuator/metrics/$metric"
      [ "$metric" != jvm.memory.used ] || metric_url="$metric_url?tag=area:heap"
      payload="$(curl -fsS --max-time 1 "$metric_url" 2>>"$LOAD_OUTPUT/monitor.log")" || payload=null
      printf '{"utc":"%s","metric":"%s","response":%s}\n' "$stamp" "$metric" "$payload" \
        >> "$LOAD_OUTPUT/jvm.jsonl"
    done
    for type in platform virtual; do
      metric="search.request.thread"
      metric_url="http://127.0.0.1:28081/actuator/metrics/$metric?tag=type:$type"
      payload="$(curl -fsS --max-time 1 "$metric_url" 2>>"$LOAD_OUTPUT/monitor.log")" || payload=null
      printf '{"utc":"%s","metric":"%s","type":"%s","response":%s}\n' "$stamp" "$metric" "$type" "$payload" \
        >> "$LOAD_OUTPUT/jvm.jsonl"
    done
    sleep 3
  done
)
sample_resources &
sampler=$!
set +e
"${stack[@]}" run --rm --no-deps k6 run --out json=/results/samples.json.gz "$k6_script" \
  2>&1 | tee "$LOAD_OUTPUT/k6.log"
pipeline=("${PIPESTATUS[@]}")
status="${pipeline[0]}"
[ "${pipeline[1]}" -eq 0 ] || status=1
set -e
snapshot > "$LOAD_OUTPUT/dataset-after.json"
if ! cmp -s "$LOAD_OUTPUT/dataset-before.json" "$LOAD_OUTPUT/dataset-after.json"; then
  echo "Во время испытания изменились документы. Результат недействителен." >&2
  status=1
fi
shasum -a 256 -c "$LOAD_OUTPUT/artifacts.sha256" > "$LOAD_OUTPUT/artifacts-after.txt" || status=1
if [ "$status" -eq 99 ]; then
  echo "k6 завершён: заданные пороги качества не выполнены. Результаты сохранены."
elif [ "$status" -ne 0 ]; then
  echo "Испытание завершено с кодом $status. См. k6.log и runtime.log." >&2
else
  echo "Испытание завершено; заданные пороги качества выполнены."
fi
exit "$status"
