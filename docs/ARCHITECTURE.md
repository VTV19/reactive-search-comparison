# Архитектура

## Сравниваемые варианты

В проекте два модуля приложения: `search-mvc` и `search-webflux`.
`mvc-virtual` — это тот же JAR `search-mvc`, запущенный с
`spring.threads.virtual.enabled=true`, поэтому бизнес-логика не дублируется.

- MVC: Tomcat, синхронный MongoDB driver, `MongoRepository`, `MongoTemplate`, `RestClient`.
- MVC virtual: тот же стек, но обработка servlet-запросов выполняется virtual threads Java 21.
- WebFlux: Reactor Netty, reactive MongoDB driver, `ReactiveMongoRepository`,
  `ReactiveMongoTemplate`, `WebClient`, `Mono`/`Flux`.

`search-core` одинаков для MVC и WebFlux: модели, DTO, валидация, запросы,
индексы и начальные документы.

## Четыре точки взаимодействия

1. **Входящий HTTP.** Проверяются валидация, HTTP-коды, перегрузка и отсутствие
   повреждённых ответов.
2. **Внутренняя обработка.** Проверяются исключения, тайм-ауты, освобождение
   разрешения семафора и отмена reactive subscription.
3. **MongoDB.** Недоступность основной базы не маскируется пустой выдачей:
   операции поиска возвращают ошибку доступности и после возврата MongoDB
   восстанавливаются без перезапуска приложения.
4. **Исходящий HTTP.** Справочник необязателен. При его ошибке основная выдача
   сохраняется с `degraded=true`; после восстановления снова возвращается
   полный ответ.

## Модель восстановления

В текущей версии используется **graceful degradation + fail-fast timeout**.
Circuit breaker, retry loop и service-level load balancing сознательно не
добавлены: они являются отдельными resilience-паттернами и изменили бы предмет
сравнения. В пояснительной записке их следует рассмотреть как альтернативы
и направление развития.

## WebFlux и блокирующие операции

HTTP request-path WebFlux использует только reactive MongoDB и `WebClient`.
Ручных `subscribe()` и блокирующих `block()/blockFirst()/blockLast()` в
production request-path нет.

Единственная `.block(Duration...)` находится в `CatalogInitializer`.
Она выполняется при startup на основном потоке, чтобы readiness не стал UP
до создания индексов и загрузки данных; это не Netty event-loop.

Контроль:
- `BlockingAuditTest` проверяет, что BlockHound действительно обнаруживает
  известную блокирующую операцию на Reactor non-blocking scheduler;
- `scripts/reactive-audit.sh` запрещает известные blocking API в production
  коде WebFlux;
- нагрузочные тесты контролируют число platform JVM threads и тип потока,
  на котором принят поисковый запрос.

## Потоки

Для MVC platform фиксируется `TOMCAT_MAX_THREADS=200`.
Для WebFlux в нагрузочном стенде фиксируется
`reactor.netty.ioWorkerCount=4`. Это именно число I/O workers, а не общее
число JVM threads: MongoDB driver, GC и служебные компоненты создают
дополнительные потоки.

Для MVC virtual значение Tomcat pool не задаёт число одновременно ожидающих
запросов; virtual threads планируются JVM на меньшем количестве carrier threads.
В этом режиме дополнительно включён `-Djdk.tracePinnedThreads=short`, и вывод
сохраняется в `runtime.log`.

## Ограничение конкурентности

Обычный режим приложения сохраняет защитный `SEARCH_MAX_CONCURRENT=64`.
Это эксплуатационный механизм controlled overload.

Для отдельного эксперимента, сравнивающего **модель потоков**, используется
`SEARCH_LIMIT=512`. Это необходимо, чтобы общий семафор 64 не становился
искусственным общим потолком раньше Tomcat/WebFlux. Одновременно увеличивается
MongoDB pool до 256. Эти значения относятся только к benchmark-стенду.
