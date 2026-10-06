package ru.hse.search.mvc;

import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import ru.hse.search.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchServiceTest {
    private MongoTemplate mongo;
    private MetadataClient metadata;
    private SearchService service;
    private SimpleMeterRegistry meters;
    private final SearchRequest request = new SearchRequest("java", null, null, null, 10, 0, true);

    @BeforeEach void setUp() {
        mongo = mock(MongoTemplate.class);
        metadata = mock(MetadataClient.class);
        meters = new SimpleMeterRegistry();
        service = new SearchService(mongo, metadata, meters, new SearchProperties(1));
        SearchDocument document = new SearchDocument("d1", new DocumentInput(
                "java", "text", Category.JAVA, "https://example.org", null));
        when(mongo.count(any(Query.class), eq(SearchDocument.class))).thenReturn(1L);
        when(mongo.find(any(Query.class), eq(SearchDocument.class))).thenReturn(List.of(document));
    }

    @Test void searchReturnsEnrichedPage() {
        when(metadata.fetch(anyList())).thenReturn(Map.of("JAVA", new SearchResponse.Reference("Java", "https://example.org")));
        assertFalse(service.search(request).degraded());
        assertEquals(0, meters.get("search.active").gauge().value());
    }

    @Test void optionalFailureProducesDegradedResponse() {
        when(metadata.fetch(anyList())).thenThrow(new MetadataUnavailableException(new RuntimeException()));
        assertTrue(service.search(request).degraded());
        assertEquals(1, meters.get("search.degraded").counter().count());
    }

    @Test void disabledEnrichmentDoesNotCallDependency() {
        assertFalse(service.search(new SearchRequest("java", null, null, null, 10, 0, false)).degraded());
        verifyNoInteractions(metadata);
    }

    @Test void databaseFailureIsNotSuccessfulEmptySearchAndReleasesPermit() {
        when(mongo.count(any(Query.class), eq(SearchDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("offline")).thenReturn(1L);
        assertThrows(DataAccessResourceFailureException.class, () -> service.search(request));
        verifyNoInteractions(metadata);
        assertEquals(0, meters.get("search.active").gauge().value());
        assertEquals(1, service.search(new SearchRequest("", null, null, null, 10, 0, false)).total());
    }

    @Test void emptyPageDoesNotCallDependency() {
        when(mongo.find(any(Query.class), eq(SearchDocument.class))).thenReturn(List.of());
        assertTrue(service.search(request).items().isEmpty());
        verifyNoInteractions(metadata);
    }
}
