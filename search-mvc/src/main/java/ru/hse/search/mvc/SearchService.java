package ru.hse.search.mvc;

import java.util.List;
import java.util.concurrent.Semaphore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.hse.search.core.MetadataUnavailableException;
import ru.hse.search.core.SearchDocument;
import ru.hse.search.core.SearchQueries;
import ru.hse.search.core.SearchRequest;
import ru.hse.search.core.SearchProperties;
import ru.hse.search.core.SearchResponse;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private final MongoTemplate mongo;
    private final MetadataClient metadata;
    private final Semaphore slots;
    private final Counter requests;
    private final Counter degraded;
    private final Counter rejected;
    private final Counter platformThreadRequests;
    private final Counter virtualThreadRequests;

    public SearchService(MongoTemplate mongo, MetadataClient metadata, MeterRegistry registry,
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

    public SearchResponse search(SearchRequest request) {
        requests.increment();
        recordRequestThread();
        if (!slots.tryAcquire()) {
            rejected.increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис занят. Повторите запрос");
        }
        try {
            log.info("Поиск MVC: limit={}, offset={}, category={}", request.limit(), request.offset(), request.category());
            long total = mongo.count(SearchQueries.count(request), SearchDocument.class);
            List<SearchDocument> page = mongo.find(SearchQueries.page(request), SearchDocument.class);
            SearchResponse result = SearchResponse.of(request, total, page);
            if (request.enrich() && !page.isEmpty()) {
                try {
                    result = result.withReferences(metadata.fetch(result.categories()));
                } catch (MetadataUnavailableException error) {
                    log.warn("Справочник недоступен, основная выдача сохранена: cause={}",
                            NestedExceptionUtils.getMostSpecificCause(error).getClass().getSimpleName());
                    log.debug("Подробности ошибки HTTP-справочника", error);
                    result = result.unavailable();
                }
            }
            if (result.degraded()) {
                degraded.increment();
            }
            log.info("Поиск MVC завершён: total={}, degraded={}", total, result.degraded());
            return result;
        } catch (RuntimeException error) {
            log.warn("Поиск MVC завершён с ошибкой: {}", error.getClass().getSimpleName());
            throw error;
        } finally {
            slots.release();
        }
    }

    private void recordRequestThread() {
        if (Thread.currentThread().isVirtual()) {
            virtualThreadRequests.increment();
        } else {
            platformThreadRequests.increment();
        }
    }
}
