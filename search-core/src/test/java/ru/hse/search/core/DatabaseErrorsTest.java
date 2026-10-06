package ru.hse.search.core;

import com.mongodb.MongoException;
import com.mongodb.MongoOperationTimeoutException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.MongoExceptionTranslator;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseErrorsTest {
    @Test void recognizesSpringResourceAndQueryTimeouts() {
        assertTrue(DatabaseErrors.isUnavailable(new DataAccessResourceFailureException("offline")));
        assertTrue(DatabaseErrors.isUnavailable(new QueryTimeoutException("expired")));
    }

    @Test void recognizesDriverTimeoutAndSocketFailure() {
        assertTrue(DatabaseErrors.isUnavailable(new MongoTimeoutException("offline")));
        assertTrue(DatabaseErrors.isUnavailable(new MongoSocketReadException(
                "disconnected", new ServerAddress("localhost", 27017))));
    }

    @Test void recognizesOperationTimeoutAfterSpringDataTranslation() {
        var translated = new MongoExceptionTranslator().translateExceptionIfPossible(
                new MongoOperationTimeoutException("operation deadline exceeded"));
        assertNotNull(translated);
        assertTrue(DatabaseErrors.isUnavailable(translated));
    }

    @Test void recognizesTimeoutInsideAnUncategorizedException() {
        var error = new UncategorizedMongoDbException("wrapped",
                new MongoOperationTimeoutException("operation deadline exceeded"));
        assertTrue(DatabaseErrors.isUnavailable(new RuntimeException("adapter", error)));
    }

    @Test void doesNotHideUnknownDatabaseErrors() {
        assertFalse(DatabaseErrors.isUnavailable(new UncategorizedMongoDbException(
                "unknown database error", new MongoException("unknown"))));
    }

    @Test void doesNotHideInvalidQueriesOrConflictingData() {
        assertFalse(DatabaseErrors.isUnavailable(new InvalidDataAccessApiUsageException("invalid query")));
        assertFalse(DatabaseErrors.isUnavailable(new DuplicateKeyException("duplicate")));
        assertFalse(DatabaseErrors.isUnavailable(new IllegalStateException("bug")));
    }

    @Test void handlesCyclicCauseChains() {
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);
        assertFalse(DatabaseErrors.isUnavailable(first));
    }

    @Test void handlesAbsentException() {
        assertFalse(DatabaseErrors.isUnavailable(null));
    }
}
