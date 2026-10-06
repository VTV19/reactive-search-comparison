# Поисковая система: MVC, MVC + virtual threads, WebFlux

Учебный проект на Java 21 и Spring Boot 3.5.16. Реализованы две версии одной
поисковой системы: Spring MVC и Spring WebFlux. Для исследования Java 21 та же
MVC-реализация дополнительно запускается в тестовом режиме с virtual threads;
это не третий модуль и не отдельная бизнес-реализация.

Общий модуль `search-core` содержит модель документа, DTO, правила валидации,
формирование MongoDB-запросов и схему индексов. MVC использует Tomcat,
`MongoTemplate`/`MongoRepository` и `RestClient`; WebFlux — Reactor Netty,
`ReactiveMongoTemplate`/`ReactiveMongoRepository` и `WebClient`.

## Быстрый запуск

```bash
bash scripts/verify.sh --java
bash start.sh
```

MVC: http://localhost:18081  
WebFlux: http://localhost:18082

Остановка:

```bash
bash stop.sh
```

## Проверки

Полный функциональный цикл:

```bash
bash scripts/verify.sh --full
```

Аудит реактивного request-path на известные блокирующие API:

```bash
bash scripts/reactive-audit.sh
```

Сравнение моделей выполнения под I/O-ожиданием:

```bash
bash stop.sh
caffeinate -i bash scripts/threading-suite.sh
```

Отказ и восстановление необязательного HTTP-сервиса под нагрузкой:

```bash
bash stop.sh
caffeinate -i bash scripts/resilience-suite.sh
```

Один произвольный нагрузочный опыт:

```bash
RATE=100 WARMUP_SECONDS=45 DURATION_SECONDS=90 \
bash scripts/load-test.sh mvc delayed

RATE=100 WARMUP_SECONDS=45 DURATION_SECONDS=90 \
bash scripts/load-test.sh mvc-virtual delayed

RATE=100 WARMUP_SECONDS=45 DURATION_SECONDS=90 \
bash scripts/load-test.sh webflux delayed
```

Результаты создаются только локально в `reports/` и Git не отслеживает их.

## Что сравнивается

Основной эксперимент рассматривает четыре точки взаимодействия: входящий HTTP,
внутреннюю обработку, MongoDB и исходящий HTTP к необязательному справочнику.
Для нагрузки сохраняются p50/p95/p99, полезный RPS, CPU, память, JVM threads,
GC, активные поиски и тип потока запроса (`platform`/`virtual`).

В WebFlux единственная разрешённая `.block()` находится в startup-инициализаторе,
до обработки пользовательских HTTP-запросов. `BlockHound` включён только в тесты,
а `scripts/reactive-audit.sh` защищает production request-path от известных
блокирующих API.

## Структура

```text
search-core/       общий каркас и модель
search-mvc/        Spring MVC
search-webflux/    Spring WebFlux
metadata-service/ управляемая внешняя HTTP-зависимость
ui/                общий интерфейс
tests/             browser и k6-сценарии
scripts/           сборка, проверки и эксперименты
docs/              минимальная техническая документация
data/              начальный каталог
```

Подробности: [архитектура](docs/ARCHITECTURE.md),
[тестирование](docs/TESTING.md), [нагрузка](docs/LOAD_TESTING.md).

Версия: 3.1.0.
