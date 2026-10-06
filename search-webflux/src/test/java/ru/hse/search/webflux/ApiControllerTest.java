package ru.hse.search.webflux;

import com.mongodb.MongoException;
import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;
import org.springframework.http.MediaType;
import ru.hse.search.core.SearchRequest;
import static org.mockito.Mockito.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class ApiControllerTest {
    private SearchService search;
    private DocumentService documents;
    private WebTestClient client;

    @BeforeEach void setUp() {
        search = mock(SearchService.class);
        documents = mock(DocumentService.class);
        client = WebTestClient.bindToController(new ApiController(search, documents))
                .controllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void informationDoesNotQueryTheDatabaseOrMetadata() {
        client.get().uri("/api/info").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.implementation").isEqualTo("webflux")
                .jsonPath("$.version").isEqualTo("3.1.0");
        verifyNoInteractions(search, documents);
    }

    @Test void invalidSearchDoesNotReachTheService() {
        client.get().uri("/api/search?limit=0").exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(search);
    }

    @Test void invalidDocumentIsRejectedByBeanValidation() {
        client.post().uri("/api/documents").contentType(MediaType.APPLICATION_JSON).bodyValue("""
                {"title":"","text":"","category":"JAVA","sourceUrl":"https://example.org"}
                """).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST")
                .jsonPath("$.fields.title").exists();
        verifyNoInteractions(documents);
    }

    @Test void directDatabaseTimeoutReturns503() {
        assertSearchError(new MongoTimeoutException("offline"), 503, "DATABASE_UNAVAILABLE");
    }

    @Test void translatedDriverTimeoutReturns503() {
        assertSearchError(new MongoExceptionTranslator().translateExceptionIfPossible(
                new MongoOperationTimeoutException("expired")), 503, "DATABASE_UNAVAILABLE");
    }

    @Test void wrappedDatabaseTimeoutOnDocumentReadReturns503() {
        when(documents.get("d001")).thenReturn(Mono.error(new UncategorizedMongoDbException(
                "wrapped", new MongoOperationTimeoutException("expired"))));
        client.get().uri("/api/documents/d001").exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("DATABASE_UNAVAILABLE");
    }

    @Test void versionConflictRemains409() {
        when(documents.delete("d001", 0)).thenReturn(Mono.error(new OptimisticLockingFailureException("conflict")));
        client.delete().uri("/api/documents/d001?version=0").exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("VERSION_CONFLICT");
    }

    @Test void unrelatedDatabaseErrorRemains500() {
        assertSearchError(new UncategorizedMongoDbException("unknown", new MongoException("unknown")),
                500, "INTERNAL_ERROR");
    }

    @Test void programmingErrorRemains500() {
        assertSearchError(new IllegalStateException("bug"), 500, "INTERNAL_ERROR");
    }

    @Test void overallDeadlineDoesNotClaimThatTheDatabaseIsDown() {
        when(search.search(any(SearchRequest.class)))
                .thenReturn(Mono.error(new java.util.concurrent.TimeoutException("search deadline")));
        client.get().uri("/api/search").exchange().expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.code").isEqualTo("SEARCH_TIMEOUT");
    }

    private void assertSearchError(RuntimeException error, int statusCode, String code) {
        when(search.search(any(SearchRequest.class))).thenReturn(Mono.error(error));
        client.get().uri("/api/search").exchange().expectStatus().isEqualTo(statusCode)
                .expectBody().jsonPath("$.code").isEqualTo(code);
    }
}
