package ru.hse.search.webflux;

import java.time.Duration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import ru.hse.search.core.Category;
import ru.hse.search.core.DocumentInput;
import ru.hse.search.core.SearchDocument;
import ru.hse.search.core.SearchProperties;
import ru.hse.search.core.SearchRequest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlockingAuditTest {
    static {
        BlockHound.install();
    }

    @Test
    void blockHoundDetectsKnownBlockingCallOnNonBlockingScheduler() {
        Mono<Long> blocking = Mono.delay(Duration.ofMillis(1))
                .publishOn(Schedulers.parallel())
                .map(value -> {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(error);
                    }
                    return value;
                });

        StepVerifier.create(blocking)
                .expectError(BlockingOperationError.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void searchServicePathCompletesOnNonBlockingScheduler() {
        ReactiveMongoTemplate mongo = mock(ReactiveMongoTemplate.class);
        MetadataClient metadata = mock(MetadataClient.class);
        var meters = new SimpleMeterRegistry();
        var service = new SearchService(mongo, metadata, meters, new SearchProperties(4));

        var document = new SearchDocument("d1", new DocumentInput(
                "java", "text", Category.JAVA, "https://example.org", null));

        when(mongo.count(any(Query.class), eq(SearchDocument.class))).thenReturn(Mono.just(1L));
        when(mongo.find(any(Query.class), eq(SearchDocument.class))).thenReturn(Flux.just(document));

        var request = new SearchRequest("java", null, null, null, 10, 0, false);

        StepVerifier.create(service.search(request).subscribeOn(Schedulers.parallel()))
                .expectNextMatches(result -> result.total() == 1 && !result.degraded())
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }
}
