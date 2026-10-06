package ru.hse.search.webflux;

import java.time.Duration;
import java.util.Map;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import ru.hse.search.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchServiceTest {
    private ReactiveMongoTemplate mongo;
    private MetadataClient metadata;
    private SearchService service;
    private SimpleMeterRegistry meters;
    private final SearchRequest request = new SearchRequest("java", null, null, null, 10, 0, true);

    @BeforeEach void setUp() {
        mongo = mock(ReactiveMongoTemplate.class);
        metadata = mock(MetadataClient.class);
        meters = new SimpleMeterRegistry();
        service = new SearchService(mongo, metadata, meters, new SearchProperties(1));
        SearchDocument document = new SearchDocument("d1", new DocumentInput(
                "java", "text", Category.JAVA, "https://example.org", null));
        when(mongo.count(any(Query.class), eq(SearchDocument.class))).thenReturn(Mono.just(1L));
        when(mongo.find(any(Query.class), eq(SearchDocument.class))).thenReturn(Flux.just(document));
    }

    @Test void workStartsOnlyAfterSubscription() {
        Mono<SearchResponse> result = service.search(request);
        verifyNoInteractions(metadata);
        assertEquals(0, meters.get("search.requests").counter().count());
        when(metadata.fetch(anyList())).thenReturn(Mono.just(Map.of("JAVA", new SearchResponse.Reference("Java", "https://example.org"))));
        StepVerifier.create(result).expectNextMatches(value -> !value.degraded()).verifyComplete();
    }

    @Test void optionalFailureProducesDegradedResponse() {
        when(metadata.fetch(anyList())).thenReturn(Mono.error(new MetadataUnavailableException(new RuntimeException())));
        StepVerifier.create(service.search(request)).expectNextMatches(SearchResponse::degraded).verifyComplete();
        assertEquals(1, meters.get("search.degraded").counter().count());
    }

    @Test void databaseFailureDoesNotBecomeEmptySearch() {
        when(mongo.count(any(Query.class), eq(SearchDocument.class)))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("offline")));
        StepVerifier.create(service.search(request)).expectError(DataAccessResourceFailureException.class).verify();
        verifyNoInteractions(metadata);
        assertEquals(0, meters.get("search.active").gauge().value());
    }

    @Test void synchronousConstructionErrorAlsoReleasesPermit() {
        when(mongo.count(any(Query.class), eq(SearchDocument.class))).thenThrow(new IllegalStateException("test"));
        StepVerifier.create(service.search(request)).expectError(IllegalStateException.class).verify();
        assertEquals(0, meters.get("search.active").gauge().value());
    }

    @Test void cancelledSubscriptionReleasesPermit() {
        when(mongo.count(any(Query.class), eq(SearchDocument.class))).thenReturn(Mono.never());
        StepVerifier.create(service.search(request))
                .then(() -> assertEquals(1, meters.get("search.active").gauge().value()))
                .thenCancel().verify(Duration.ofSeconds(2));
        assertEquals(0, meters.get("search.active").gauge().value());
    }

    @Test void busyServiceRejectsAnotherSearchThenRecovers() {
        Sinks.One<Long> pending = Sinks.one();
        when(mongo.count(any(Query.class), eq(SearchDocument.class))).thenReturn(pending.asMono());
        var first = service.search(new SearchRequest("java", null, null, null, 10, 0, false)).subscribe();
        try {
            StepVerifier.create(service.search(request))
                    .expectErrorMatches(error -> error instanceof ResponseStatusException status
                            && status.getStatusCode().value() == 503).verify();
            assertEquals(1, meters.get("search.rejected").counter().count());
        } finally {
            first.dispose();
        }
        when(mongo.count(any(Query.class), eq(SearchDocument.class))).thenReturn(Mono.just(1L));
        StepVerifier.create(service.search(new SearchRequest("java", null, null, null, 10, 0, false)))
                .expectNextMatches(value -> value.total() == 1).verifyComplete();
    }

    @Test void unfinishedWorkTimesOutAndReleasesThePermit() {
        when(mongo.count(any(Query.class), eq(SearchDocument.class))).thenReturn(Mono.never());
        StepVerifier.withVirtualTime(() -> service.search(request))
                .thenAwait(Duration.ofSeconds(9))
                .expectError(java.util.concurrent.TimeoutException.class).verify();
        assertEquals(0, meters.get("search.active").gauge().value());
    }

    @Test void disabledEnrichmentDoesNotCallDependency() {
        StepVerifier.create(service.search(new SearchRequest("java", null, null, null, 10, 0, false)))
                .expectNextMatches(value -> !value.degraded()).verifyComplete();
        verifyNoInteractions(metadata);
    }
}
