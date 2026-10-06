package ru.hse.search.webflux;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ru.hse.search.core.MetadataUnavailableException;
import ru.hse.search.core.SearchDocument;
import ru.hse.search.core.SearchQueries;
import ru.hse.search.core.SearchRequest;
import ru.hse.search.core.SearchProperties;
import ru.hse.search.core.SearchResponse;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final ReactiveMongoTemplate mongo;
    private final MetadataClient metadata;
    private final Semaphore slots;
    private final Counter requests;
    private final Counter degraded;
    private final Counter rejected;
    private final Counter platformThreadRequests;
    private final Counter virtualThreadRequests;

    public SearchService(ReactiveMongoTemplate mongo, MetadataClient metadata, MeterRegistry registry,
                         SearchProperties properties) {
        int maxConcurrent = properties.maxConcurrent();
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("Лимит параллельных поисков должен быть положительным");
        }
        this.mongo = mongo;
        this.metadata = metadata;
        this.slots = new Semaphore(maxConcurrent);
        requests = registry.counter("search.requests");
        degraded = registry.counter("search.degraded");
        rejected = registry.counter("search.rejected");
        platformThreadRequests = registry.counter("search.request.thread", "type", "platform");
        virtualThreadRequests = registry.counter("search.request.thread", "type", "virtual");
        Gauge.builder("search.active", slots, value -> maxConcurrent - value.availablePermits()).register(registry);
    }

    public Mono<SearchResponse> search(SearchRequest request) {
        return Mono.defer(() -> {
            requests.increment();
            recordRequestThread();
            if (!slots.tryAcquire()) {
                rejected.increment();
                return Mono.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Сервис занят. Повторите запрос"));
            }
            // Вложенный defer гарантирует освобождение разрешения и при ошибке сборки цепочки.
            return Mono.defer(() -> execute(request))
                    .timeout(Duration.ofSeconds(8))
                    .doOnError(error -> log.warn("Поиск WebFlux завершён с ошибкой: {}",
                            error.getClass().getSimpleName()))
                    .doFinally(signal -> slots.release());
        });
    }

    private Mono<SearchResponse> execute(SearchRequest request) {
        log.info("Поиск WebFlux: limit={}, offset={}, category={}", request.limit(), request.offset(), request.category());
        // collectList собирает только страницу, уже ограниченную limit в запросе к MongoDB.
        return mongo.count(SearchQueries.count(request), SearchDocument.class)
                .flatMap(total -> mongo.find(SearchQueries.page(request), SearchDocument.class)
                        .collectList().map(page -> SearchResponse.of(request, total, page)))
                .flatMap(result -> enrich(result, request.enrich()))
                .doOnNext(result -> {
                    if (result.degraded()) {
                        degraded.increment();
                    }
                    log.info("Поиск WebFlux завершён: total={}, degraded={}", result.total(), result.degraded());
                });
    }

    private Mono<SearchResponse> enrich(SearchResponse result, boolean enabled) {
        if (!enabled || result.items().isEmpty()) {
            return Mono.just(result);
        }
        return metadata.fetch(result.categories()).map(result::withReferences)
                .onErrorResume(MetadataUnavailableException.class, error -> {
                    log.warn("Справочник недоступен, основная выдача сохранена: cause={}",
                            NestedExceptionUtils.getMostSpecificCause(error).getClass().getSimpleName());
                    log.debug("Подробности ошибки HTTP-справочника", error);
                    return Mono.just(result.unavailable());
                });
    }

    private void recordRequestThread() {
        if (Thread.currentThread().isVirtual()) {
            virtualThreadRequests.increment();
        } else {
            platformThreadRequests.increment();
        }
    }
}
