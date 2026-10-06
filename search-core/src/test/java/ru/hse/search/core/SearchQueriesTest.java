package ru.hse.search.core;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import static org.junit.jupiter.api.Assertions.*;

class SearchQueriesTest {
    @Test void countHasNoPageLimitOrScoreProjection() {
        Query query = SearchQueries.count(new SearchRequest("java", Category.JAVA, null, null, 5, 10, false));
        assertEquals(0, query.getLimit());
        assertEquals(0L, query.getSkip());
        assertTrue(query.getFieldsObject().isEmpty());
        assertEquals(Category.JAVA, query.getQueryObject().get("category"));
        assertTrue(query.getQueryObject().containsKey("$text"));
    }

    @Test void pageIncludesTextScoreAndStableSecondaryOrder() {
        Query query = SearchQueries.page(new SearchRequest("java", null, null, null, 5, 10, false));
        assertEquals(5, query.getLimit());
        assertEquals(10L, query.getSkip());
        assertTrue(query.getSortObject().containsKey("score"));
        assertTrue(query.getSortObject().containsKey("id"));
    }

    @Test void emptyQueryDoesNotSendEmptyTextSearch() {
        Query query = SearchQueries.page(new SearchRequest("", null, null, null, 10, 0, false));
        assertFalse(query.getQueryObject().containsKey("$text"));
        assertEquals(1, query.getSortObject().get("title"));
    }

    @Test void phraseUsesQuotedSearchTerms() {
        Query query = SearchQueries.count(new SearchRequest("java 21", null, SearchRequest.Mode.PHRASE,
                null, 10, 0, false));
        org.bson.Document text = (org.bson.Document) query.getQueryObject().get("$text");
        assertEquals("\"java 21\"", text.getString("$search"));
        assertEquals("none", text.getString("$language"));
    }
}
