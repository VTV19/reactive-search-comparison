# Технические источники

## Книги

**Крейг Уоллс. Spring в действии. 6-е издание. ДМК Пресс, 2022.**

Использованы архитектурные и прикладные идеи разделов:
2.3 «Проверка данных в форме»; 4.2 «Определение репозиториев MongoDB»;
глава 7 «Создание служб REST»;
глава 12 «Разработка реактивных API»;
13.2 «Реактивное хранилище документов в MongoDB»;
глава 15 «Spring Boot Actuator»;
глава 18 «Развертывание Spring».

В проекте этим разделам соответствуют валидация DTO, контроллеры REST,
обычный и реактивный репозитории, Actuator, исполняемые JAR и проверки готовности.
Шестое издание книги не означает использование Spring Framework 6 в её примерах.

**Олег Докука, Игорь Лозинский. Практика реактивного программирования в Spring 5. ДМК Пресс, 2019.**

Связанные разделы: глава 4 «Project Reactor — основа реактивных приложений»,
глава 6 «Неблокирующие и асинхронные взаимодействия с WebFlux»,
глава 7 «Реактивный доступ к базам данных»,
глава 9 «Тестирование реактивных приложений»,
глава 10 «И наконец, выпуск!».

В проекте применены `Mono`/`Flux`, `defer`, композиция операций,
обработка ошибки необязательной зависимости, `ReactiveMongoTemplate`,
StepVerifier, Actuator и Micrometer.
Различие реактивных потоков и общей отказоустойчивости системы сохраняется:
сам по себе Reactor не обеспечивает репликацию или восстановление потерянных данных.

Код каталога и тексты начальных карточек не воспроизводят листинги и главы книг.
Полнотекстовый поиск, версия документа, конкретная конфигурация запуска и браузерные проверки
дополнены по документации применяемых инструментов.

## Применение разделов книг

Ниже указана печатная нумерация страниц, а не номер листа PDF.

| Механизм проекта | «Spring в действии», 6-е издание | «Практика реактивного программирования в Spring 5» |
|---|---|---|
| Контекст Spring, внедрение зависимостей, организация проекта | § 1.1, с. 26; § 1.2.2, с. 34 | — |
| Конфигурационные свойства и валидация настроек | § 6.2–6.2.1, с. 182–186 | — |
| Проверка входных данных | § 2.3.2, с. 79 | — |
| Обычный и реактивный доступ к MongoDB | § 4.2, с. 137–138, 141; § 13.2.2, с. 403 | Глава 7, с. 336 |
| Составление запросов через Template | — | Глава 7, с. 346–347 |
| WebFlux и реактивные контроллеры | § 12.1, с. 352, 354 | Глава 6, с. 238 |
| Реактивный HTTP-клиент и обработка ошибок | § 12.4, с. 369 | Глава 6, с. 246–247; глава 4, с. 180–181 |
| Проверки потоков и API | § 12.3.3, с. 367 | Глава 9, с. 436, 446 |
| Actuator, прикладные метрики, готовность | Глава 15, с. 464; § 18.3.3, с. 502 | Глава 10, с. 460, 472 |

`ApiControllerTest` использует MockMvc в MVC и WebTestClient в WebFlux,
как в соответствующих разделах книг.
В текущих `MongoHttpTest` используется JDK `HttpClient` с `@SpringBootTest(RANDOM_PORT)`;
это другой тестовый клиент для той же задачи, а не буквальное воспроизведение книжного примера.
`StepVerifier` используется непосредственно для реактивных служб, включая виртуальное время.
Синхронный `RestClient` выбран по документации Spring Framework 6.2; в книгах синхронный HTTP-доступ
рассматривается на примере `RestTemplate`.

Полнотекстовые запросы MongoDB, `@Version`, конкретные Bash/Compose-сценарии и браузерный
контейнер — отдельные инженерные решения проекта. Наличие в книгах глав о Kafka, RSocket,
Spring Cloud или Kubernetes не означает, что эти компоненты нужны для работы каталога.

## Официальная документация

Проверять API следует для версии библиотеки, управляемой Spring Boot, а не по номеру издания книги.

| Тема | Источник |
|---|---|
| Spring Boot 3.5, системные требования | https://docs.spring.io/spring-boot/3.5/system-requirements.html |
| Упаковка исполняемого JAR | https://docs.spring.io/spring-boot/3.5/maven-plugin/packaging.html |
| Actuator | https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html |
| Spring WebFlux | https://docs.spring.io/spring-framework/reference/web/webflux.html |
| HTTP-клиенты Spring | https://docs.spring.io/spring-framework/reference/6.2/integration/rest-clients.html |
| ConfigurationProperties | https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html |
| WebTestClient | https://docs.spring.io/spring-framework/reference/6.2/testing/webtestclient.html |
| Перевод ошибок MongoDB | https://github.com/spring-projects/spring-data-mongodb/blob/4.5.x/spring-data-mongodb/src/main/java/org/springframework/data/mongodb/core/MongoExceptionTranslator.java |
| Создание MongoDB-индексов | https://docs.spring.io/spring-data/mongodb/reference/4.5/mongodb/mapping/mapping-index-management.html |
| TextCriteria, включая matchingPhrase | https://docs.spring.io/spring-data/mongodb/docs/4.5.x/api/org/springframework/data/mongodb/core/query/TextCriteria.html |
| Запись и оптимистическая блокировка | https://docs.spring.io/spring-data/mongodb/reference/4.5/mongodb/template-crud-operations.html |
| Клиентский тайм-аут MongoDB | https://www.mongodb.com/docs/drivers/java/sync/current/connection/specify-connection-options/csot/ |
| Reactor Test | https://projectreactor.io/docs/core/release/reference/testing.html |
| Java 21 virtual threads | https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html |
| Spring Boot 3.5 virtual threads | https://docs.spring.io/spring-boot/3.5/reference/features/spring-application.html |
| Reactor Netty Event Loop Group | https://projectreactor.io/docs/netty/release/reference/http-server.html |
| BlockHound | https://github.com/reactor/BlockHound |
| Готовность и порядок запуска Compose | https://docs.docker.com/compose/how-tos/startup-order/ |
| Playwright в Docker | https://playwright.dev/python/docs/docker |
| Maven, фильтрация ресурсов | https://maven.apache.org/plugins/maven-resources-plugin/examples/filter.html |

Веб-страницы с путями `current` и `reference` могут обновляться независимо от проекта.
Набор зависимостей Java зафиксирован через Spring Boot 3.5.16; версии остальных инструментов указаны в конфигурации поставки.


## Сетевое восстановление и диагностика

- Java SE 21, `InetAddress`, раздел InetAddress Caching:
  https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/InetAddress.html
- Docker Compose, Networking, обновление контейнеров и восстановление соединений:
  https://docs.docker.com/compose/how-tos/networking/
- Spring Framework 6.2, `NestedExceptionUtils.getMostSpecificCause`:
  https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/core/NestedExceptionUtils.html

Общее окно восстановительной проверки и требование трёх последовательных полных
ответов являются критериями испытания данного проекта, а не готовым примером из книг.

## Дополнительные технические ссылки

- Докука, Лозинский, гл. 6, с. 263–272: сравнение MVC/WebFlux и показатели нагрузки.
- Spring Framework 6.2, Performance и Concurrency Model:
  https://docs.spring.io/spring-framework/reference/6.2/web/webflux/new-framework.html
- Mockito, explicit instrumentation on Java 21 (настройка test JVM, не серверная зависимость):
  https://github.com/mockito/mockito/blob/main/mockito-core/src/main/java/org/mockito/Mockito.java

## Нагрузочное тестирование

- Grafana k6 1.8.1, выпуск:
  https://github.com/grafana/k6/releases/tag/v1.8.1
- Модели нагрузки:
  https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/
- Constant arrival rate:
  https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/
- Пропущенные итерации:
  https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/
- Пороговые условия:
  https://grafana.com/docs/k6/latest/using-k6/thresholds/
- Экспорт исходных наблюдений:
  https://grafana.com/docs/k6/latest/results-output/real-time/json/
- Ограничения ресурсов контейнеров:
  https://docs.docker.com/reference/compose-file/services/
- Метрики контейнеров:
  https://docs.docker.com/reference/cli/docker/container/stats/

В нагрузочном комплекте зафиксирована версия k6 1.8.1.
Методика и конкретные значения нагрузки задаются в LOAD_TESTING.md.
Они не являются результатами или готовыми сценариями из книг.
