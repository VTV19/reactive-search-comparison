# Нагрузочное тестирование

## Цель

Основной эксперимент после замечаний руководителя проверяет не тезис
«WebFlux всегда быстрее», а влияние модели выполнения на систему с I/O-ожиданием.

Измеряются три варианта одного API:

| variant | модель |
|---|---|
| `mvc` | Spring MVC + Tomcat platform threads |
| `mvc-virtual` | тот же MVC + Java 21 virtual threads |
| `webflux` | Spring WebFlux + Reactor Netty |

Во время одного опыта запущена только одна измеряемая версия.

## Почему изменён старый stress-test

Эксплуатационный лимит 64 активных поисков при задержке 200 мс создаёт общий
теоретический потолок около 320 req/s и скрывает различия модели потоков.
Поэтому для **threading benchmark** лимит увеличен до 512, Mongo pool — до 256.
В обычной эксплуатации лимит остаётся 64.

Metadata-service в benchmark-стенде использует virtual threads. Иначе его
искусственный `Thread.sleep(200)` сам насыщал бы servlet pool и становился
ограничением раньше измеряемого приложения.

## Исходный корпус и изоляция данных

Штатные профили `database`, `http`, `delayed` и `resilience` используют только
эталонный каталог из `data/documents.json` (36 документов в версии 3.1.0).
До нагрузки `load-test.sh` проверяет количество, идентификаторы, содержимое
и начальные версии документов. Протокол сохраняется в `dataset-check.json`.
При несовпадении нагрузка не запускается; скрипт не удаляет данные автоматически.

Тестовая MongoDB хранит `load_catalog` в именованном томе
`java-search-load_load-data`. Команда `down` без `--volumes` сохраняет этот том.
В нём могут остаться данные отдельного эксперимента с увеличенным корпусом.
Сравнение снимков «до/после» само по себе не проверяет соответствие эталону:
одинаковыми могут оказаться два снимка ошибочно выбранного корпуса.

Для сброса **только нагрузочного стенда** сначала завершите все серии и сохраните
нужные результаты из `reports/load/`. Следующая команда удаляет тестовый том с
содержимым `load_catalog`; демонстрационные базы и файлы отчётов не затрагиваются:

```bash
docker compose -p java-search-load -f compose.load.yaml down --volumes --remove-orphans
```

При следующем запуске приложение создаст штатные индексы и начальный каталог.
Не заменяйте эту команду на `docker system prune` и не применяйте `--volumes`
к основному `compose.yaml`. Исследования увеличенного корпуса проводятся
отдельно и не смешиваются со штатной серией сравнения моделей потоков.

После изменения стенда сначала выполните один проверочный опыт с параметрами
основной серии, но сокращёнными интервалами:

```bash
RATE=100 WARMUP_SECONDS=30 DURATION_SECONDS=30 VUS=768 \
SEARCH_LIMIT=512 MONGO_POOL_SIZE=256 P95_MS=3000 \
TOMCAT_MAX_THREADS=200 REACTOR_IO_WORKERS=4 \
bash scripts/load-test.sh mvc delayed
```

Проверьте `dataset-check.json`, `summary.json`, `runtime.log` и код завершения.
При транспортных ошибках уже на 100 req/s длинную серию не запускают до
выяснения причины. Код k6 `107` означает исключение сценария, а не только
нарушение порога производительности (код `99`).

## Основная серия

```bash
bash scripts/verify.sh --full
bash scripts/reactive-audit.sh
bash stop.sh
caffeinate -i bash scripts/threading-suite.sh
```

По умолчанию:
- профиль `delayed`, metadata delay 200 мс;
- 100, 900 и 1400 req/s;
- 45 с прогрева;
- 90 с измерения;
- 30 с между опытами;
- 3 повтора;
- 3 варианта;
- всего 27 опытов;
- `SEARCH_LIMIT=512`;
- `MONGO_POOL_SIZE=256`;
- `TOMCAT_MAX_THREADS=200`;
- `REACTOR_IO_WORKERS=4`;
- `VUS=768`.

Порядок вариантов чередуется между повторами.

Ориентировочное время серии: 1,5–2 часа.

## Что сохраняется

Каждый запуск сохраняет:
- `summary.json`, `summary.txt`;
- исходные k6 samples;
- CPU/память Docker;
- JVM heap, GC, live platform threads, process CPU;
- `search.active`;
- счётчики `search.request.thread{type=platform|virtual}`;
- JAR/JS hashes;
- dataset before/after;
- resolved Compose;
- runtime/container logs.

Для `mvc-virtual` `runtime.log` также проверяется на сообщения о pinned virtual threads.

## Как интерпретировать

Сначала проверить:
1. `contract_errors == 0`;
2. `transport_errors == 0` или объяснить их;
3. `dropped_iterations == 0`, иначе генератор мог стать ограничением;
4. отсутствие OOM;
5. одинаковые hashes данных и неизменность корпуса.

После этого сравнивать p50/p95/p99, полезный RPS, CPU, память, JVM platform
threads и GC.

Если WebFlux и MVC virtual показывают близкие результаты — это нормальный итог.
Virtual threads и reactive event loop решают схожую задачу масштабирования
I/O-ожидания разными программными моделями.

Нельзя заявлять «WebFlux быстрее вообще». Допустимый вывод:
при I/O-bound нагрузке WebFlux и/или MVC virtual могут поддерживать больше
одновременных ожидающих запросов без линейного роста platform threads;
эффект на latency и throughput зависит от базы, внешней службы, CPU и лимитов.

## Resilience-серия

```bash
caffeinate -i bash scripts/resilience-suite.sh
```

6 опытов: MVC и WebFlux по 3 повтора, 100 req/s, 90 с:
normal → metadata error → normal.

Ключевые показатели:
- normal full rate;
- fault degraded rate;
- recovery full rate;
- `transition_to_degraded_ms`;
- `transition_to_full_ms`;
- отсутствие повреждения primary search results.

Это тест прикладной стратегии degradation/recovery, а не доказательство того,
что reactive programming автоматически обеспечивает отказоустойчивость.
