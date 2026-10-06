package ru.hse.search.mvc;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiControllerTest {
    private SearchService search;
    private DocumentService documents;
    private MockMvc client;

    @BeforeEach void setUp() {
        search = mock(SearchService.class);
        documents = mock(DocumentService.class);
        client = MockMvcBuilders.standaloneSetup(new ApiController(search, documents))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void informationDoesNotQueryTheDatabaseOrMetadata() throws Exception {
        client.perform(get("/api/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.implementation").value("mvc"))
                .andExpect(jsonPath("$.version").value("3.1.0"));
        verifyNoInteractions(search, documents);
    }

    @Test void invalidSearchDoesNotReachTheService() throws Exception {
        client.perform(get("/api/search").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(search);
    }

    @Test void invalidDocumentIsRejectedByBeanValidation() throws Exception {
        client.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON).content("""
                {"title":"","text":"","category":"JAVA","sourceUrl":"https://example.org"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fields.title").exists());
        verifyNoInteractions(documents);
    }

    @Test void directDatabaseTimeoutReturns503() throws Exception {
        assertSearchError(new MongoTimeoutException("offline"), 503, "DATABASE_UNAVAILABLE");
    }

    @Test void translatedDriverTimeoutReturns503() throws Exception {
        assertSearchError(new MongoExceptionTranslator().translateExceptionIfPossible(
                new MongoOperationTimeoutException("expired")), 503, "DATABASE_UNAVAILABLE");
    }

    @Test void wrappedDatabaseTimeoutOnDocumentReadReturns503() throws Exception {
        when(documents.get("d001")).thenThrow(new UncategorizedMongoDbException(
                "wrapped", new MongoOperationTimeoutException("expired")));
        client.perform(get("/api/documents/d001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }

    @Test void versionConflictRemains409() throws Exception {
        doThrow(new OptimisticLockingFailureException("conflict")).when(documents).delete("d001", 0);
        client.perform(delete("/api/documents/d001").param("version", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test void unrelatedDatabaseErrorRemains500() throws Exception {
        assertSearchError(new UncategorizedMongoDbException("unknown", new MongoException("unknown")),
                500, "INTERNAL_ERROR");
    }

    @Test void programmingErrorRemains500() throws Exception {
        assertSearchError(new IllegalStateException("bug"), 500, "INTERNAL_ERROR");
    }

    private void assertSearchError(RuntimeException error, int statusCode, String code) throws Exception {
        when(search.search(any(SearchRequest.class))).thenThrow(error);
        client.perform(get("/api/search"))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code));
    }
}
