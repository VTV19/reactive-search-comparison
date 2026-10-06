package ru.hse.search.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

/** Recognizes availability failures without hiding invalid queries and programming errors. */
public final class DatabaseErrors {
    private DatabaseErrors() {}

    public static boolean isUnavailable(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
            // Spring Data can wrap a driver timeout in UncategorizedMongoDbException.
            // Inspect causes rather than classifying every DataAccessException as an outage.
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof QueryTimeoutException
                    || cause instanceof MongoSocketException
                    || cause instanceof MongoTimeoutException
                    || cause instanceof MongoExecutionTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
