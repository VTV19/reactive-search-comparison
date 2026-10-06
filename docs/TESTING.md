# Тестирование

## 1. Полная функциональная проверка

```bash
bash scripts/verify.sh --full
```

Проверяются сборка Java 21, JUnit, HTTP API, браузерный интерфейс, CRUD,
оптимистическая блокировка, полнотекстовый индекс, сохранение данных,
остановка/возврат MongoDB и остановка/возврат metadata-service.

### Матрица отказов

| Точка | Проверка | Ожидаемый результат |
|---|---|---|
| входящий HTTP | неверные параметры / перегрузка | корректный 4xx/503, приложение остаётся доступно |
| внутренняя логика | исключение, timeout, cancel | ошибка не превращается в пустой ответ; семафор освобождён |
| MongoDB | контейнер остановлен | поиск недоступен явно; после старта базы соединение восстанавливается |
| исходящий HTTP | metadata остановлен / 5xx | основной поиск 200 + `degraded=true`; после возврата снова полный ответ |

## 2. Проверка реактивности

```bash
bash scripts/reactive-audit.sh
bash scripts/verify.sh --java
```

`reactive-audit.sh` статически запрещает в production request-path WebFlux:
`RestClient`, blocking `MongoTemplate`, `Thread.sleep`, `blockFirst`,
`blockLast`, `blockOptional`, `Future.get`, ручной `subscribe`.
Одна startup `.block()` разрешена только в `CatalogInitializer`.

JUnit `BlockingAuditTest` устанавливает BlockHound и проверяет обнаружение
известной блокирующей операции и выполнение `SearchService` на Reactor
non-blocking scheduler. В этом тесте MongoDB и HTTP-клиент заменены Mockito-моками,
обогащение выключено. Проверка не охватывает реальные сетевые драйверы, все ветви
обработки ошибок и ввод-вывод production-конфигурации логирования; она не является
доказательством отсутствия любых блокировок во всём приложении.

В модуле `search-webflux` Surefire запускает каждый тестовый класс последовательно
в отдельной JVM (`forkCount=1`, `reuseForks=false`). BlockHound инструментирует JVM
на весь срок её жизни, поэтому результат `ApiControllerTest` и `MongoHttpTest`
не должен зависеть от того, запускался ли перед ними `BlockingAuditTest`.
Все четыре класса остаются в обычном цикле `test`/`verify`; отдельный профиль
для включения аудита не требуется.

Ожидание сигналов в обоих тестах аудита ограничено пятью секундами. WARN/ERROR
тестового WebFlux-модуля выводятся в консоль и попадают в `reports/verify.log`;
подробные результаты JUnit находятся в `search-webflux/target/surefire-reports/`.

## 3. Нагрузочное сравнение потоковых моделей

```bash
bash stop.sh
caffeinate -i bash scripts/threading-suite.sh
```

Сравниваются:
- `mvc` — platform threads;
- `mvc-virtual` — тот же MVC-код с virtual threads Java 21;
- `webflux` — Reactor Netty.

Подробная методика приведена в `LOAD_TESTING.md`.

## 4. Восстановление под нагрузкой

```bash
bash stop.sh
caffeinate -i bash scripts/resilience-suite.sh
```

В каждом опыте: нормальная работа → HTTP 503 от metadata-service →
возврат metadata-service в normal. Сохраняются доли full/degraded ответов,
p95/p99 каждой фазы и время перехода к устойчивой degraded/full выдаче.

## Что не является доказательством

- Один удачный прогон не доказывает превосходство архитектуры.
- Одинаковый RPS при `SEARCH_MAX_CONCURRENT=64` не является пределом MVC/WebFlux.
- Ошибка генератора или `dropped_iterations` не должна объявляться пределом сервера.
- WebFlux не делает MongoDB-запрос быстрее сам по себе.
- Graceful degradation реализована прикладной политикой; Reactor сам по себе
  не является circuit breaker.
