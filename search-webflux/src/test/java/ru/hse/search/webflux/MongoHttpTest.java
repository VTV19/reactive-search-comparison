package ru.hse.search.webflux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.hse.search.core.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"metadata.url=http://127.0.0.1:1", "metadata.timeout-ms=100", "catalog.seed.enabled=false"})
class MongoHttpTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired DocumentRepository repository;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        // По умолчанию это тестовый порт 27018, а не пользовательская база на 27017.
        String url = System.getenv().getOrDefault("TEST_MONGO_URL", "mongodb://localhost:27018");
        properties.add("spring.data.mongodb.uri", () -> url
                + "/search_webflux_test?timeoutMS=3000&connectTimeoutMS=1000&serverSelectionTimeoutMS=2000&retryReads=false&retryWrites=false&w=majority&journal=true");
    }

    @BeforeEach void resetTestData() {
        repository.deleteAll().block(Duration.ofSeconds(5));
        List<SearchDocument> documents = List.of(
                new SearchDocument("a", input("alpha", "beta", Category.JAVA, null)),
                new SearchDocument("b", input("beta", "alpha", Category.SPRING, null)),
                new SearchDocument("c", input("gamma", "alpha beta", Category.REACTOR, null)));
        repository.insert(documents).blockLast(Duration.ofSeconds(5));
    }

    @AfterAll static void closeClient() {
        CLIENT.close();
    }

    @Test void applicationAndDatabaseAreHealthy() throws Exception {
        assertEquals("UP", json(call("GET", "/actuator/health", null)).path("status").asText());
        var home = call("GET", "/", null);
        assertEquals(200, home.statusCode());
        assertTrue(home.body().contains("Поисковый каталог"));
    }

    @Test void fullTextSearchUsesWeightedTitleAndCategoryFilter() throws Exception {
        var reply = call("GET", "/api/search?q=alpha&enrich=false", null);
        assertEquals(200, reply.statusCode());
        JsonNode result = json(reply);
        assertEquals(3, result.path("total").asInt());
        assertEquals("a", result.path("items").get(0).path("id").asText());
        assertTrue(result.path("items").get(0).path("score").asDouble() > 0);
        JsonNode filtered = json(call("GET", "/api/search?q=alpha&category=JAVA&enrich=false", null));
        assertEquals(1, filtered.path("total").asInt());
    }

    @Test void phraseSearchPreservesWordOrder() throws Exception {
        JsonNode result = json(call("GET", "/api/search?q=alpha%20beta&mode=PHRASE&enrich=false", null));
        assertEquals(1, result.path("total").asInt());
        assertEquals("c", result.path("items").get(0).path("id").asText());
    }

    @Test void anyModeIsOrAndCatalogueHasPagination() throws Exception {
        JsonNode any = json(call("GET", "/api/search?q=alpha%20missing&enrich=false", null));
        assertEquals(3, any.path("total").asInt());
        JsonNode catalogue = json(call("GET", "/api/search?limit=1&offset=1&enrich=false", null));
        assertEquals(3, catalogue.path("total").asInt());
        assertEquals(1, catalogue.path("items").size());
        assertEquals("b", catalogue.path("items").get(0).path("id").asText());
    }

    @Test void createUpdateDeleteAlsoUpdateSearchIndex() throws Exception {
        var created = call("POST", "/api/documents", body("uniquealpha", "text", null));
        assertEquals(201, created.statusCode());
        JsonNode document = json(created);
        String id = document.path("id").asText();
        long version = document.path("version").asLong(-1);
        assertEquals(0, version);
        assertTrue(created.headers().firstValue("Location").orElseThrow().endsWith(id));
        assertEquals(1, json(call("GET", "/api/search?q=uniquealpha&enrich=false", null)).path("total").asInt());
        var updated = call("PUT", "/api/documents/" + id, body("uniquebeta", "changed", version));
        assertEquals(200, updated.statusCode());
        assertEquals(version + 1, json(updated).path("version").asLong());
        assertEquals(0, json(call("GET", "/api/search?q=uniquealpha&enrich=false", null)).path("total").asInt());
        assertEquals(1, json(call("GET", "/api/search?q=uniquebeta&enrich=false", null)).path("total").asInt());
        assertEquals(204, call("DELETE", "/api/documents/" + id + "?version=" + (version + 1), null).statusCode());
        assertEquals(404, call("GET", "/api/documents/" + id, null).statusCode());
        assertEquals(0, json(call("GET", "/api/search?q=uniquebeta&enrich=false", null)).path("total").asInt());
    }

    @Test void staleHttpVersionCannotOverwriteOrDeleteNewContent() throws Exception {
        var current = json(call("GET", "/api/documents/a", null));
        long version = current.path("version").asLong();
        assertEquals(200, call("PUT", "/api/documents/a", body("first change", "text", version)).statusCode());
        var conflict = call("PUT", "/api/documents/a", body("lost update", "text", version));
        assertEquals(409, conflict.statusCode());
        assertEquals("VERSION_CONFLICT", json(conflict).path("code").asText());
        assertEquals(409, call("DELETE", "/api/documents/a?version=" + version, null).statusCode());
        assertEquals("first change", json(call("GET", "/api/documents/a", null)).path("title").asText());
    }

    @Test void repositoryChecksVersionInTheDatabaseNotOnlyInTheController() {
        SearchDocument first = repository.findById("a").block(Duration.ofSeconds(5));
        SearchDocument second = repository.findById("a").block(Duration.ofSeconds(5));
        assertNotNull(first);
        assertNotNull(second);
        Long oldVersion = second.getVersion();
        first.setTitle("changed independently");
        repository.save(first).block(Duration.ofSeconds(5));
        assertThrows(OptimisticLockingFailureException.class, () -> repository.save(second).block(Duration.ofSeconds(5)));
        second.setVersion(oldVersion);
        assertThrows(OptimisticLockingFailureException.class, () -> repository.delete(second).block(Duration.ofSeconds(5)));
    }

    @Test void invalidInputAndMalformedJsonReturn400() throws Exception {
        assertEquals(400, call("POST", "/api/documents", body("", "text", null)).statusCode());
        assertEquals(400, call("POST", "/api/documents", Map.of(
                "title", "test", "text", "text", "category", "JAVA", "sourceUrl", "javascript:alert(1)")).statusCode());
        assertEquals(400, call("POST", "/api/documents", Map.of(
                "title", "test", "text", "text", "category", "UNKNOWN", "sourceUrl", "https://example.org")).statusCode());
        HttpRequest malformed = HttpRequest.newBuilder(URI.create(base() + "/api/documents"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{bad")).build();
        assertEquals(400, CLIENT.send(malformed, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, call("PUT", "/api/documents/a", body("changed", "text", null)).statusCode());
    }

    @Test void invalidSearchParametersReturn400() throws Exception {
        for (String query : List.of("limit=0", "offset=-1", "mode=UNKNOWN", "q=%21", "limit=word", "category=UNKNOWN")) {
            assertEquals(400, call("GET", "/api/search?" + query, null).statusCode(), query);
        }
    }

    @Test void missingDocumentReturns404ForReadWriteAndDelete() throws Exception {
        assertEquals(404, call("GET", "/api/documents/missing", null).statusCode());
        assertEquals(404, call("PUT", "/api/documents/missing", body("valid", "text", 0L)).statusCode());
        assertEquals(404, call("DELETE", "/api/documents/missing?version=0", null).statusCode());
    }

    @Test void optionalDependencyFailureDoesNotEraseSearchResults() throws Exception {
        JsonNode result = json(call("GET", "/api/search?q=alpha&enrich=true", null));
        assertTrue(result.path("degraded").asBoolean());
        assertEquals(3, result.path("total").asInt());
        assertEquals(3, result.path("items").size());
        assertFalse(json(call("GET", "/api/search?q=alpha&enrich=false", null)).path("degraded").asBoolean());
    }

    @Test void actuatorExposesCountersButNotEnvironment() throws Exception {
        call("GET", "/api/search?q=alpha&enrich=false", null);
        JsonNode counter = json(call("GET", "/actuator/metrics/search.requests", null));
        assertTrue(counter.path("measurements").get(0).path("value").asDouble() >= 1);
        assertEquals(404, call("GET", "/actuator/env", null).statusCode());
    }


    @Test void packagedInterfaceHasReleaseAssetsAndCorrectImplementation() throws Exception {
        var home = call("GET", "/", null);
        assertEquals(200, home.statusCode());
        assertTrue(home.body().contains("data-version=\"3.1.0\""));
        assertTrue(home.body().contains("data-implementation=\"webflux\""));
        assertFalse(home.body().contains("@applicationName@"));
        var script = call("GET", "/assets/app-v3.js", null);
        assertEquals(200, script.statusCode());
        assertTrue(script.body().contains("AbortController"));
        assertEquals("webflux", json(call("GET", "/api/info", null)).path("implementation").asText());
    }

    @Test void readinessIncludesDatabase() throws Exception {
        var readiness = call("GET", "/actuator/health/readiness", null);
        assertEquals(200, readiness.statusCode());
        assertEquals("UP", json(readiness).path("status").asText());
    }

    private DocumentInput input(String title, String text, Category category, Long version) {
        return new DocumentInput(title, text, category, "https://example.org", version);
    }

    private Map<String, Object> body(String title, String text, Long version) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("title", title);
        result.put("text", text);
        result.put("category", "JAVA");
        result.put("sourceUrl", "https://example.org");
        if (version != null) result.put("version", version);
        return result;
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> call(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(12));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 500, response.body());
        return mapper.readTree(response.body());
    }
}
