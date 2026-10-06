#!/usr/bin/env bash

set -euo pipefail

cd "$(dirname "$0")"

REPORTS="reports"
LOAD="$REPORTS/load"
OUT="$REPORTS/analysis-package.txt"

if [ ! -d "$LOAD" ]; then
    echo "ОШИБКА: каталог $LOAD не найден."
    exit 1
fi

: > "$OUT"

section() {
    printf '\n\n============================================================\n' >> "$OUT"
    printf '%s\n' "$1" >> "$OUT"
    printf '============================================================\n\n' >> "$OUT"
}

add_file() {
    local file="$1"

    if [ ! -f "$file" ]; then
        return 0
    fi

    printf '\n---------------- FILE: %s ----------------\n' "$file" >> "$OUT"

    # Обычные текстовые результаты.
    case "$file" in
        *.json|*.jsonl|*.txt|*.tsv|*.log|*.yaml|*.yml)
            cat "$file" >> "$OUT"
            ;;

        *)
            cat "$file" >> "$OUT"
            ;;
    esac

    printf '\n---------------- END FILE ----------------\n' >> "$OUT"
}

add_optional_files_from_dir() {
    local dir="$1"

    [ -d "$dir" ] || return 0

    # Итоговые результаты k6.
    for name in \
        summary.json \
        summary.txt \
        result.json \
        result.txt \
        resilience-summary.json \
        resilience-summary.txt \
        load-summary.json \
        load-summary.txt \
        k6-summary.json \
        k6-summary.txt
    do
        add_file "$dir/$name"
    done

    # Ресурсные метрики.
    for name in \
        resources.tsv \
        resources.csv \
        docker-stats.tsv \
        docker-stats.csv \
        jvm.jsonl \
        metrics.jsonl
    do
        add_file "$dir/$name"
    done

    # Конфигурация конкретного опыта.
    for name in \
        environment.txt \
        application.json \
        exit-code.txt \
        dataset-check.json \
        corpus-benchmark.txt \
        corpus-stats.json
    do
        add_file "$dir/$name"
    done

    # Runtime log нужен для:
    # - pinning virtual threads;
    # - серверных ошибок;
    # - Mongo timeout;
    # - диагностики WebFlux/MVC.
    if [ -f "$dir/runtime.log" ]; then
        printf '\n---------------- FILE: %s/runtime.log ----------------\n' "$dir" >> "$OUT"

        # Полный runtime.log может быть огромным из-за INFO каждого запроса.
        # Поэтому сохраняем диагностически значимые строки.
        grep -Ei \
            'ERROR|WARN|Exception|Caused by|timeout|timed out|pinned|VirtualThread|OutOfMemory|Mongo|OVERLOADED|degraded|Netty|Tomcat|Started|Picked up JAVA_TOOL_OPTIONS' \
            "$dir/runtime.log" \
            >> "$OUT" || true

        printf '\n---------------- END FILE ----------------\n' >> "$OUT"
    fi
}

section "JAVA SEARCH — COMPLETE EXPERIMENT ANALYSIS PACKAGE"

{
    echo "generated_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "project_directory=$PWD"
    echo "uname=$(uname -a)"
    echo "architecture=$(uname -m)"
} >> "$OUT"

# ============================================================
# 1. Полная проверка проекта
# ============================================================

section "1. FUNCTIONAL / INTEGRATION VERIFICATION"

if [ -f "$REPORTS/verify.log" ]; then
    add_file "$REPORTS/verify.log"
else
    echo "MISSING: reports/verify.log" >> "$OUT"
fi

# ============================================================
# 2. Threading suite
# ============================================================

section "2. THREADING SUITE — MVC / MVC VIRTUAL / WEBFLUX"

THREADING_SUITE="$(
    find "$LOAD" \
        -maxdepth 1 \
        -type d \
        -name 'threading-suite-*' \
        -print |
    LC_ALL=C sort |
    tail -1
)"

if [ -n "$THREADING_SUITE" ]; then

    echo "Selected suite: $THREADING_SUITE" >> "$OUT"

    add_file "$THREADING_SUITE/status.tsv"

    find "$THREADING_SUITE" \
        -maxdepth 1 \
        -type f \
        \( -name '*.log' -o -name '*.txt' -o -name '*.json' \) \
        -print |
    LC_ALL=C sort |
    while IFS= read -r file; do
        [ "$file" = "$THREADING_SUITE/status.tsv" ] || add_file "$file"
    done

    if [ -f "$THREADING_SUITE/status.tsv" ]; then

        while IFS=$'\t' read -r rate repeat variant exit_code result_dir
        do
            [ "$rate" = "rate" ] && continue
            [ -z "${result_dir:-}" ] && continue

            section \
                "THREADING: rate=$rate repeat=$repeat variant=$variant exit=$exit_code"

            add_optional_files_from_dir "$result_dir"

        done < "$THREADING_SUITE/status.tsv"
    fi

else
    echo "MISSING: threading-suite-*" >> "$OUT"
fi

# ============================================================
# 3. Resilience suite
# ============================================================

section "3. RESILIENCE SUITE — FAILURE / DEGRADATION / RECOVERY"

RESILIENCE_SUITE="$(
    find "$LOAD" \
        -maxdepth 1 \
        -type d \
        -name 'resilience-suite-*' \
        -print |
    LC_ALL=C sort |
    tail -1
)"

if [ -n "$RESILIENCE_SUITE" ]; then

    echo "Selected suite: $RESILIENCE_SUITE" >> "$OUT"

    add_file "$RESILIENCE_SUITE/status.tsv"

    find "$RESILIENCE_SUITE" \
        -maxdepth 1 \
        -type f \
        \( -name '*.log' -o -name '*.txt' -o -name '*.json' -o -name '*.tsv' \) \
        -print |
    LC_ALL=C sort |
    while IFS= read -r file; do
        [ "$file" = "$RESILIENCE_SUITE/status.tsv" ] || add_file "$file"
    done

    if [ -f "$RESILIENCE_SUITE/status.tsv" ]; then

        while IFS=$'\t' read -r c1 c2 c3 c4 c5 c6
        do
            # Поддерживаем разные версии status.tsv.
            case "$c1" in
                repeat|rate|implementation)
                    continue
                    ;;
            esac

            # result_dir обычно последняя колонка.
            result_dir=""

            for value in "$c6" "$c5" "$c4"; do
                if [[ "${value:-}" == */reports/load/* ]] ||
                   [[ "${value:-}" == reports/load/* ]]; then
                    result_dir="$value"
                    break
                fi
            done

            [ -z "$result_dir" ] && continue
            [ ! -d "$result_dir" ] && continue

            section "RESILIENCE RESULT: $result_dir"

            add_optional_files_from_dir "$result_dir"

        done < "$RESILIENCE_SUITE/status.tsv"
    fi

else
    echo "MISSING: resilience-suite-*" >> "$OUT"
fi

# ============================================================
# 4. Corpus scaling
# ============================================================

section "4. CORPUS SCALING — 1K / 10K / 50K"

CORPUS_SUITES="$(
    find "$LOAD" \
        -maxdepth 1 \
        -type d \
        -name 'corpus-suite-*' \
        -print |
    LC_ALL=C sort
)"

if [ -n "$CORPUS_SUITES" ]; then

    while IFS= read -r suite
    do
        [ -z "$suite" ] && continue

        section "CORPUS SUITE: $suite"

        add_file "$suite/corpus-suite.tsv"

        find "$suite" \
            -maxdepth 1 \
            -type f \
            \( \
                -name 'corpus-*-stats.json' \
                -o -name '*.log' \
                -o -name '*.txt' \
            \) \
            -print |
        LC_ALL=C sort |
        while IFS= read -r file; do
            add_file "$file"
        done

        if [ -f "$suite/corpus-suite.tsv" ]; then

            while IFS=$'\t' read -r \
                corpus repeat implementation rate exit_code result_dir
            do
                [ "$corpus" = "corpus_size" ] && continue
                [ -z "${result_dir:-}" ] && continue
                [ "$result_dir" = "NOT_FOUND" ] && continue
                [ ! -d "$result_dir" ] && continue

                section \
                    "CORPUS: size=$corpus repeat=$repeat implementation=$implementation"

                add_optional_files_from_dir "$result_dir"

            done < "$suite/corpus-suite.tsv"

        fi

    done <<< "$CORPUS_SUITES"

else
    echo "MISSING: corpus-suite-*" >> "$OUT"
fi

# ============================================================
# 5. Старые основные/stress результаты
#
# Они полезны для сопоставления первоначальной и уточнённой
# методики. Добавляем только summary/метрики, не raw samples.
# ============================================================

section "5. OTHER LOAD / STRESS RESULTS"

find "$LOAD" \
    -maxdepth 1 \
    -type d \
    \( \
        -name '*-mvc-http-r*' \
        -o -name '*-webflux-http-r*' \
        -o -name '*-mvc-delayed-r*' \
        -o -name '*-webflux-delayed-r*' \
    \) \
    -print |
LC_ALL=C sort |
while IFS= read -r dir
do
    # Результаты threading-suite уже были добавлены.
    # Повторное включение не страшно, но уменьшаем размер файла.
    if [ -n "${THREADING_SUITE:-}" ] &&
       [ -f "$THREADING_SUITE/status.tsv" ] &&
       grep -Fq "$dir" "$THREADING_SUITE/status.tsv"; then
        continue
    fi

    section "LOAD RESULT: $dir"

    add_optional_files_from_dir "$dir"

done

# ============================================================
# 6. Итоговый индекс
# ============================================================

section "6. RESULT DIRECTORY INDEX"

find "$LOAD" \
    -maxdepth 2 \
    -type f \
    \( \
        -name 'summary.json' \
        -o -name 'summary.txt' \
        -o -name 'resilience-summary.json' \
        -o -name 'resilience-summary.txt' \
        -o -name 'resources.tsv' \
        -o -name 'jvm.jsonl' \
        -o -name 'runtime.log' \
        -o -name 'status.tsv' \
        -o -name 'corpus-suite.tsv' \
        -o -name 'corpus-stats.json' \
        -o -name 'dataset-check.json' \
    \) \
    -print |
LC_ALL=C sort \
>> "$OUT"

# ============================================================
# Размер и контрольная сумма
# ============================================================

section "7. PACKAGE INFORMATION"

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
echo "Создан файл:"
echo "$OUT"
echo
ls -lh "$OUT"
echo
echo "Количество строк:"
wc -l "$OUT"
echo
echo "SHA-256:"
shasum -a 256 "$OUT"
echo
echo "Ничего из исходников проекта не изменено."
echo "Файл находится в reports/ и не должен попадать в Git."
