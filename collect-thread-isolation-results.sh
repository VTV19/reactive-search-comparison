#!/usr/bin/env bash

set -Eeuo pipefail

cd "$(dirname "$0")"

LOAD="reports/load"

if [[ ! -d "$LOAD" ]]; then
    echo "ОШИБКА: каталог $LOAD не найден"
    exit 1
fi

# ============================================================
# Найти последний завершённый thread-isolation suite
# ============================================================

SUITE="$(
    find "$LOAD" \
        -maxdepth 1 \
        -type d \
        -name 'thread-isolation-suite-*' \
        -print |
    LC_ALL=C sort |
    tail -1
)"

if [[ -z "$SUITE" ]]; then
    echo "ОШИБКА: thread-isolation-suite-* не найден"
    exit 1
fi

echo "Используется suite:"
echo "$SUITE"
echo

OUT="$SUITE/analysis-package.txt"
TABLE="$SUITE/analysis-table.tsv"

: > "$OUT"

section() {
    printf '\n\n============================================================\n' >> "$OUT"
    printf '%s\n' "$1" >> "$OUT"
    printf '============================================================\n\n' >> "$OUT"
}

add_file() {
    local file="$1"

    [[ -f "$file" ]] || return 0

    printf '\n---------------- FILE: %s ----------------\n' "$file" >> "$OUT"
    cat "$file" >> "$OUT"
    printf '\n---------------- END FILE ----------------\n' >> "$OUT"
}

# ============================================================
# Заголовок
# ============================================================

section "THREAD MODEL ISOLATION — COMPLETE ANALYSIS PACKAGE"

{
    echo "generated_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "suite=$SUITE"
    echo "host=$(uname -a)"
    echo "architecture=$(uname -m)"
} >> "$OUT"

# ============================================================
# 1. Методика
# ============================================================

section "1. METHODOLOGY"

add_file "$SUITE/methodology.txt"
add_file "$SUITE/status.tsv"
add_file "$SUITE/aggregate.tsv"

# ============================================================
# 2. Проверка полноты эксперимента
# ============================================================

section "2. COMPLETENESS CHECK"

python3 - "$SUITE" <<'PY' >> "$OUT"
from pathlib import Path
import csv
import sys
from collections import Counter, defaultdict

suite = Path(sys.argv[1])
status = suite / "status.tsv"

if not status.exists():
    print("ERROR: status.tsv missing")
    raise SystemExit(0)

rows = []

with status.open(encoding="utf-8") as f:
    reader = csv.DictReader(f, delimiter="\t")
    rows = list(reader)

print(f"runs_in_status={len(rows)}")

counts = Counter()
exit_codes = Counter()

for r in rows:
    key = (r.get("concurrency"), r.get("variant"))
    counts[key] += 1
    exit_codes[r.get("exit_code")] += 1

print()
print("runs_by_concurrency_variant:")

for key in sorted(counts, key=lambda x: (int(x[0]), x[1])):
    print(f"  concurrency={key[0]} variant={key[1]} repeats={counts[key]}")

print()
print("exit_codes:")

for code, count in sorted(exit_codes.items()):
    print(f"  {code}: {count}")

expected_variants = {"mvc", "mvc-virtual", "webflux"}

concurrencies = sorted(
    {int(r["concurrency"]) for r in rows if r.get("concurrency")}
)

print()
print(f"concurrencies={concurrencies}")

for c in concurrencies:
    present = {
        r["variant"]
        for r in rows
        if int(r["concurrency"]) == c
    }

    missing = expected_variants - present

    if missing:
        print(f"WARNING concurrency={c}: missing variants={sorted(missing)}")
PY

# ============================================================
# 3. Сводная таблица из summary.json
# ============================================================

section "3. PER-RUN SUMMARY"

printf \
'concurrency\trepeat\tvariant\trequests\tthroughput_rps\tavg_ms\tmedian_ms\tp90_ms\tp95_ms\tp99_ms\tmax_ms\terrors\tdropped\n' \
> "$TABLE"

python3 - "$SUITE" "$TABLE" <<'PY'
from pathlib import Path
import csv
import json
import sys

suite = Path(sys.argv[1])
table = Path(sys.argv[2])
status = suite / "status.tsv"

rows_out = []

with status.open(encoding="utf-8") as f:
    reader = csv.DictReader(f, delimiter="\t")

    for row in reader:
        result_dir = Path(row["result_dir"])

        # status.tsv содержит абсолютные пути с машины пользователя.
        # Если путь существует — используем его.
        # Иначе ищем каталог по basename внутри suite.
        if not result_dir.exists():
            result_dir = suite / result_dir.name

        summary = result_dir / "summary.json"

        if not summary.exists():
            # Иногда каталоги результатов лежат рядом с suite.
            candidate = suite.parent / Path(row["result_dir"]).name
            if (candidate / "summary.json").exists():
                result_dir = candidate
                summary = candidate / "summary.json"

        if not summary.exists():
            rows_out.append({
                "concurrency": row["concurrency"],
                "repeat": row["repeat"],
                "variant": row["variant"],
                "requests": "MISSING",
                "throughput_rps": "",
                "avg_ms": "",
                "median_ms": "",
                "p90_ms": "",
                "p95_ms": "",
                "p99_ms": "",
                "max_ms": "",
                "errors": "",
                "dropped": "",
            })
            continue

        with summary.open(encoding="utf-8") as sf:
            data = json.load(sf)

        lat = data.get("latency_ms", {})

        rows_out.append({
            "concurrency": row["concurrency"],
            "repeat": row["repeat"],
            "variant": row["variant"],
            "requests": data.get("requests", ""),
            "throughput_rps": data.get("throughput_rps", ""),
            "avg_ms": lat.get("avg", ""),
            "median_ms": lat.get("med", ""),
            "p90_ms": lat.get("p90", ""),
            "p95_ms": lat.get("p95", ""),
            "p99_ms": lat.get("p99", ""),
            "max_ms": lat.get("max", ""),
            "errors": data.get("errors", ""),
            "dropped": data.get("dropped_iterations", ""),
        })

fields = [
    "concurrency",
    "repeat",
    "variant",
    "requests",
    "throughput_rps",
    "avg_ms",
    "median_ms",
    "p90_ms",
    "p95_ms",
    "p99_ms",
    "max_ms",
    "errors",
    "dropped",
]

with table.open("w", encoding="utf-8", newline="") as f:
    writer = csv.DictWriter(f, delimiter="\t", fieldnames=fields)
    writer.writeheader()
    writer.writerows(rows_out)
PY

add_file "$TABLE"

# ============================================================
# 4. Медианы трёх повторов
# ============================================================

section "4. MEDIANS BY CONCURRENCY AND VARIANT"

python3 - "$TABLE" <<'PY' >> "$OUT"
import csv
import statistics
import sys
from collections import defaultdict

table = sys.argv[1]

data = defaultdict(lambda: defaultdict(list))

with open(table, encoding="utf-8") as f:
    reader = csv.DictReader(f, delimiter="\t")

    for r in reader:
        if r["requests"] == "MISSING":
            continue

        key = (int(r["concurrency"]), r["variant"])

        for field in [
            "throughput_rps",
            "avg_ms",
            "median_ms",
            "p95_ms",
            "p99_ms",
            "max_ms",
            "errors",
            "dropped",
        ]:
            try:
                data[key][field].append(float(r[field]))
            except (ValueError, TypeError):
                pass

print(
    "concurrency\tvariant\t"
    "throughput_median\tp95_median\tp99_median\t"
    "avg_latency_median\tmax_latency_median\t"
    "errors_median\tdropped_median"
)

for key in sorted(data, key=lambda x: (x[0], x[1])):
    c, variant = key
    values = data[key]

    def med(name):
        xs = values.get(name, [])
        return statistics.median(xs) if xs else float("nan")

    print(
        f"{c}\t{variant}\t"
        f"{med('throughput_rps'):.3f}\t"
        f"{med('p95_ms'):.3f}\t"
        f"{med('p99_ms'):.3f}\t"
        f"{med('avg_ms'):.3f}\t"
        f"{med('max_ms'):.3f}\t"
        f"{med('errors'):.0f}\t"
        f"{med('dropped'):.0f}"
    )
PY

# ============================================================
# 5. Отношения MVC vs WebFlux vs Virtual
# ============================================================

section "5. RELATIVE PERFORMANCE"

python3 - "$TABLE" <<'PY' >> "$OUT"
import csv
import statistics
import sys
from collections import defaultdict

table = sys.argv[1]

values = defaultdict(list)

with open(table, encoding="utf-8") as f:
    reader = csv.DictReader(f, delimiter="\t")

    for r in reader:
        if r["requests"] == "MISSING":
            continue

        c = int(r["concurrency"])
        v = r["variant"]

        values[(c, v, "throughput")].append(
            float(r["throughput_rps"])
        )
        values[(c, v, "p95")].append(
            float(r["p95_ms"])
        )
        values[(c, v, "p99")].append(
            float(r["p99_ms"])
        )

concurrencies = sorted({
    k[0] for k in values
})

def median(c, v, metric):
    xs = values.get((c, v, metric), [])
    return statistics.median(xs) if xs else None

for c in concurrencies:
    mvc = median(c, "mvc", "throughput")
    virt = median(c, "mvc-virtual", "throughput")
    wf = median(c, "webflux", "throughput")

    mvc95 = median(c, "mvc", "p95")
    virt95 = median(c, "mvc-virtual", "p95")
    wf95 = median(c, "webflux", "p95")

    print(f"CONCURRENCY {c}")

    if mvc and virt:
        print(
            f"  throughput virtual/mvc = {virt/mvc:.3f}x "
            f"({(virt/mvc - 1)*100:+.1f}%)"
        )

    if mvc and wf:
        print(
            f"  throughput webflux/mvc = {wf/mvc:.3f}x "
            f"({(wf/mvc - 1)*100:+.1f}%)"
        )

    if virt and wf:
        print(
            f"  throughput webflux/virtual = {wf/virt:.3f}x "
            f"({(wf/virt - 1)*100:+.1f}%)"
        )

    if mvc95 and wf95:
        print(
            f"  p95 webflux/mvc = {wf95/mvc95:.3f}x"
        )

    if mvc95 and virt95:
        print(
            f"  p95 virtual/mvc = {virt95/mvc95:.3f}x"
        )

    print()
PY

# ============================================================
# 6. Проверка ожидаемого thread-pool saturation
# ============================================================

section "6. THREAD SATURATION EVIDENCE"

python3 - "$SUITE" <<'PY' >> "$OUT"
from pathlib import Path
import csv
import json
import re
import sys
from collections import defaultdict

suite = Path(sys.argv[1])
status = suite / "status.tsv"

records = defaultdict(lambda: defaultdict(list))

def numbers_from_metric(obj):
    result = []

    if not isinstance(obj, dict):
        return result

    for measurement in obj.get("measurements", []):
        value = measurement.get("value")

        if isinstance(value, (int, float)):
            result.append(float(value))

    return result

with status.open(encoding="utf-8") as f:
    reader = csv.DictReader(f, delimiter="\t")

    for row in reader:
        result_dir = Path(row["result_dir"])

        if not result_dir.exists():
            candidate = suite.parent / result_dir.name
            if candidate.exists():
                result_dir = candidate

        actuator = result_dir / "actuator.jsonl"

        if not actuator.exists():
            continue

        c = int(row["concurrency"])
        variant = row["variant"]

        with actuator.open(
            encoding="utf-8",
            errors="replace"
        ) as af:

            for line in af:
                try:
                    item = json.loads(line)
                except json.JSONDecodeError:
                    continue

                metric = item.get("metric")
                response = item.get("response")

                values = numbers_from_metric(response)

                if not values:
                    continue

                records[(c, variant)][metric].extend(values)

def print_stats(c, variant, metric):
    xs = records.get((c, variant), {}).get(metric, [])

    if not xs:
        print(
            f"  {metric}: no samples"
        )
        return

    print(
        f"  {metric}: "
        f"min={min(xs):.1f} "
        f"max={max(xs):.1f} "
        f"avg={sum(xs)/len(xs):.1f} "
        f"samples={len(xs)}"
    )

for c, variant in sorted(records):
    print(
        f"concurrency={c} variant={variant}"
    )

    for metric in [
        "benchmark.active",
        "jvm.threads.live",
        "tomcat.threads.busy",
        "tomcat.threads.current",
        "process.cpu.usage",
    ]:
        print_stats(c, variant, metric)

    print()
PY

# ============================================================
# 7. Проверка типов потоков
# ============================================================

section "7. PLATFORM VS VIRTUAL REQUEST THREADS"

python3 - "$SUITE" <<'PY' >> "$OUT"
from pathlib import Path
import csv
import json
import sys
from collections import defaultdict

suite = Path(sys.argv[1])

status = suite / "status.tsv"

max_values = defaultdict(
    lambda: {
        "platform": [],
        "virtual": [],
    }
)

with status.open(encoding="utf-8") as f:
    reader = csv.DictReader(f, delimiter="\t")

    for row in reader:
        result_dir = Path(row["result_dir"])

        if not result_dir.exists():
            candidate = suite.parent / result_dir.name
            if candidate.exists():
                result_dir = candidate

        actuator = result_dir / "actuator.jsonl"

        if not actuator.exists():
            continue

        key = (
            int(row["concurrency"]),
            row["variant"],
        )

        with actuator.open(
            encoding="utf-8",
            errors="replace"
        ) as af:

            for line in af:
                try:
                    item = json.loads(line)
                except json.JSONDecodeError:
                    continue

                if (
                    item.get("metric")
                    != "benchmark.request.thread"
                ):
                    continue

                thread_type = item.get("type")

                if thread_type not in {
                    "platform",
                    "virtual",
                }:
                    continue

                response = item.get("response")

                if not isinstance(response, dict):
                    continue

                for m in response.get(
                    "measurements",
                    []
                ):
                    value = m.get("value")

                    if isinstance(
                        value,
                        (int, float),
                    ):
                        max_values[key][
                            thread_type
                        ].append(float(value))

for key in sorted(max_values):
    c, variant = key

    platform = max_values[key]["platform"]
    virtual = max_values[key]["virtual"]

    print(
        f"concurrency={c} "
        f"variant={variant} "
        f"platform_counter_max="
        f"{max(platform) if platform else 'n/a'} "
        f"virtual_counter_max="
        f"{max(virtual) if virtual else 'n/a'}"
    )
PY

# ============================================================
# 8. Virtual-thread pinning
# ============================================================

section "8. VIRTUAL THREAD PINNING"

PINNING_FOUND=0

while IFS= read -r file; do

    [[ -f "$file" ]] || continue

    matches="$(
        grep -niE \
            'Pinned|VirtualThread.*pinned|reason:.*pin' \
            "$file" \
            2>/dev/null \
        || true
    )"

    if [[ -n "$matches" ]]; then
        PINNING_FOUND=1

        echo "FILE: $file" >> "$OUT"
        echo "$matches" >> "$OUT"
        echo >> "$OUT"
    fi

done < <(
    find "$SUITE/.." \
        -maxdepth 2 \
        -type f \
        \( \
            -name 'pinning.txt' \
            -o -name 'runtime.log' \
        \) \
        -path '*mvc-virtual*' \
        -print |
    LC_ALL=C sort
)

if [[ "$PINNING_FOUND" -eq 0 ]]; then
    echo \
"No pinning evidence matched in collected mvc-virtual runtime logs." \
    >> "$OUT"
fi

# ============================================================
# 9. CPU / RAM / PID evidence
# ============================================================

section "9. RESOURCE SAMPLES"

while IFS= read -r file; do
    add_file "$file"
done < <(
    find "$SUITE/.." \
        -maxdepth 2 \
        -type f \
        -name 'resources.tsv' \
        -path '*thread-isolation*' \
        -print |
    LC_ALL=C sort
)

# ============================================================
# 10. Actuator metrics
# ============================================================

section "10. ACTUATOR METRICS"

while IFS= read -r file; do
    add_file "$file"
done < <(
    find "$SUITE/.." \
        -maxdepth 2 \
        -type f \
        -name 'actuator.jsonl' \
        -path '*thread-isolation*' \
        -print |
    LC_ALL=C sort
)

# ============================================================
# 11. Environment каждого опыта
# ============================================================

section "11. EXPERIMENT ENVIRONMENTS"

while IFS= read -r file; do
    add_file "$file"
done < <(
    find "$SUITE/.." \
        -maxdepth 2 \
        -type f \
        -name 'environment.txt' \
        -path '*thread-isolation*' \
        -print |
    LC_ALL=C sort
)

# ============================================================
# 12. Runtime diagnostics — только важные строки
# ============================================================

section "12. RUNTIME DIAGNOSTICS"

while IFS= read -r file; do

    echo \
"---------------- FILE: $file ----------------" \
        >> "$OUT"

    grep -Ei \
'ERROR|WARN|Exception|OutOfMemory|Pinned|VirtualThread|Tomcat|Netty|Started|JAVA_TOOL_OPTIONS|Rejected|timeout' \
        "$file" \
        >> "$OUT" \
        || true

    echo \
"---------------- END FILE ----------------" \
        >> "$OUT"

done < <(
    find "$SUITE/.." \
        -maxdepth 2 \
        -type f \
        -name 'runtime.log' \
        -path '*thread-isolation*' \
        -print |
    LC_ALL=C sort
)

# ============================================================
# 13. Автоматическая предварительная интерпретация
#
# Это НЕ окончательный научный вывод.
# Она нужна, чтобы быстро увидеть форму результата.
# ============================================================

section "13. AUTOMATIC PRELIMINARY INTERPRETATION"

python3 - "$TABLE" <<'PY' >> "$OUT"
import csv
import statistics
import sys
from collections import defaultdict

rows = defaultdict(list)

with open(sys.argv[1], encoding="utf-8") as f:
    for r in csv.DictReader(f, delimiter="\t"):

        if r["requests"] == "MISSING":
            continue

        key = (
            int(r["concurrency"]),
            r["variant"],
        )

        rows[key].append({
            "rps": float(r["throughput_rps"]),
            "p95": float(r["p95_ms"]),
            "p99": float(r["p99_ms"]),
            "errors": float(r["errors"]),
        })

def med(c, v, field):
    xs = [
        x[field]
        for x in rows.get((c, v), [])
    ]

    if not xs:
        return None

    return statistics.median(xs)

concurrencies = sorted({
    c for c, _ in rows
})

print(
    "Interpretation uses medians of repeated runs."
)
print()

for c in concurrencies:

    mvc = med(c, "mvc", "rps")
    virt = med(c, "mvc-virtual", "rps")
    wf = med(c, "webflux", "rps")

    mvc95 = med(c, "mvc", "p95")
    virt95 = med(c, "mvc-virtual", "p95")
    wf95 = med(c, "webflux", "p95")

    print(f"Concurrency {c}:")

    for name, rps, p95 in [
        ("MVC platform", mvc, mvc95),
        ("MVC virtual", virt, virt95),
        ("WebFlux", wf, wf95),
    ]:
        if rps is not None:
            print(
                f"  {name}: "
                f"median throughput={rps:.2f} rps, "
                f"median p95={p95:.1f} ms"
            )

    if mvc and wf:
        diff = (wf / mvc - 1.0) * 100.0
        print(
            f"  WebFlux throughput vs MVC: "
            f"{diff:+.1f}%"
        )

    if mvc and virt:
        diff = (virt / mvc - 1.0) * 100.0
        print(
            f"  Virtual throughput vs MVC: "
            f"{diff:+.1f}%"
        )

    print()

# Проверка главной гипотезы на самой высокой нагрузке.
if concurrencies:
    c = max(concurrencies)

    mvc = med(c, "mvc", "rps")
    virt = med(c, "mvc-virtual", "rps")
    wf = med(c, "webflux", "rps")

    mvc95 = med(c, "mvc", "p95")
    virt95 = med(c, "mvc-virtual", "p95")
    wf95 = med(c, "webflux", "p95")

    print("HIGH-CONCURRENCY HYPOTHESIS CHECK")
    print(f"highest_concurrency={c}")

    if all(x is not None for x in [
        mvc, virt, wf,
        mvc95, virt95, wf95,
    ]):

        wf_gain = wf / mvc
        vt_gain = virt / mvc

        print(
            f"webflux_vs_mvc_throughput="
            f"{wf_gain:.3f}x"
        )
        print(
            f"virtual_vs_mvc_throughput="
            f"{vt_gain:.3f}x"
        )
        print(
            f"mvc_p95={mvc95:.1f} ms"
        )
        print(
            f"virtual_p95={virt95:.1f} ms"
        )
        print(
            f"webflux_p95={wf95:.1f} ms"
        )

        if (
            wf_gain >= 1.25
            and vt_gain >= 1.25
            and mvc95 > wf95 * 1.25
        ):
            print()
            print(
                "PRELIMINARY: strong evidence of "
                "platform-thread saturation."
            )

        elif (
            wf_gain >= 1.10
            or vt_gain >= 1.10
        ):
            print()
            print(
                "PRELIMINARY: partial evidence of "
                "platform-thread saturation."
            )

        else:
            print()
            print(
                "PRELIMINARY: throughput alone does "
                "not demonstrate strong platform-thread "
                "saturation."
            )

print()
print(
    "Final conclusion must also use actuator "
    "thread/busy metrics and resource samples."
)
PY

# ============================================================
# 14. Файловый индекс
# ============================================================

section "14. FILE INDEX"

find "$SUITE/.." \
    -maxdepth 2 \
    -type f \
    -path '*thread-isolation*' \
    \( \
        -name 'summary.json' \
        -o -name 'actuator.jsonl' \
        -o -name 'resources.tsv' \
        -o -name 'environment.txt' \
        -o -name 'runtime.log' \
        -o -name 'pinning.txt' \
        -o -name 'exit-code.txt' \
    \) \
    -print |
LC_ALL=C sort \
>> "$OUT"

# ============================================================
# 15. Package metadata
# ============================================================

section "15. PACKAGE INFORMATION"

{
    echo "file=$OUT"
    echo "bytes=$(wc -c < "$OUT" | tr -d ' ')"
    echo "lines=$(wc -l < "$OUT" | tr -d ' ')"
    echo "sha256=$(shasum -a 256 "$OUT" | awk '{print $1}')"
} >> "$OUT"

echo
echo "============================================================"
echo "ГОТОВО"
echo "============================================================"
echo
echo "Suite:"
echo "$SUITE"
echo
echo "Сводная таблица:"
echo "$TABLE"
echo
echo "Пакет для анализа:"
echo "$OUT"
echo
ls -lh "$OUT" "$TABLE"
echo
echo "SHA-256:"
shasum -a 256 "$OUT"
