package ru.hse.search.core;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    private DocumentInput input() {
        return new DocumentInput("Spring WebFlux", "Реактивный API", Category.SPRING,
                "https://docs.spring.io/spring-framework/reference/", null);
    }

    @Test void extractsWordsWithoutQueryOperators() {
        assertEquals(List.of("java", "21", "spring"), SearchText.words("JAVA 21 + Spring!"));
    }

    @Test void preservesPhraseWordOrderAndRepetitions() {
        assertEquals(List.of("very", "very", "good"), SearchText.words("very very good"));
    }

    @Test void normalizesUnicode() {
        assertEquals(SearchText.words("й"), SearchText.words("и\u0306"));
    }

    @Test void snippetDoesNotSplitSurrogatePair() {
        String text = "🙂".repeat(221);
        assertEquals("🙂".repeat(220) + "…", SearchText.snippet(text));
    }

    @Test void acceptsHttpsAndRejectsExecutableLinks() {
        assertDoesNotThrow(() -> SearchText.checkSourceUrl("https://example.org/guide?q=a"));
        assertThrows(IllegalArgumentException.class, () -> SearchText.checkSourceUrl("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> SearchText.checkSourceUrl("https://user@example.org"));
        assertThrows(IllegalArgumentException.class, () -> SearchText.checkSourceUrl("http://example.org"));
    }

    @Test void validatesId() {
        assertDoesNotThrow(() -> SearchText.checkId("doc-a_1"));
        assertThrows(IllegalArgumentException.class, () -> SearchText.checkId("../file"));
    }

    @Test void blankQueryMeansCatalogue() {
        SearchRequest request = new SearchRequest("  ", null, null, null, 10, 0, false);
        assertTrue(request.q().isEmpty());
        assertEquals(SearchRequest.Mode.ANY, request.mode());
    }

    @Test void rejectsInvalidSearchBounds() {
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest("java", null, null, null, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest("java", null, null, null, 10, -1, false));
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest("!", null, null, null, 10, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new SearchRequest("a ".repeat(13), null, null, null, 10, 0, false));
    }

    @Test void validatesDocumentAndStripsWhitespace() {
        DocumentInput value = new DocumentInput(" Java ", " text ", Category.JAVA, " https://example.org ", null);
        assertEquals("Java", value.title());
        assertDoesNotThrow(value::checkContent);
        assertThrows(IllegalArgumentException.class, () ->
                new DocumentInput("", "text", Category.JAVA, "https://example.org", null).checkContent());
    }

    @Test void viewDoesNotExposeSearchScore() {
        SearchDocument document = new SearchDocument("d001", input());
        document.setVersion(0L);
        document.setScore(3.5);
        assertEquals(new DocumentView("d001", 0L, input().title(), input().text(), input().category(), input().sourceUrl()),
                DocumentView.from(document));
    }

    @Test void missingReferenceKeepsOriginalHits() {
        SearchResponse result = SearchResponse.of(new SearchRequest("spring", null, null, null, 10, 0, true),
                1, List.of(new SearchDocument("d001", input())));
        SearchResponse fallback = result.withReferences(Map.of());
        assertTrue(fallback.degraded());
        assertEquals(result.items(), fallback.items());
    }

    @Test void validReferenceEnrichesAndInvalidLinkDegrades() {
        SearchResponse result = SearchResponse.of(new SearchRequest("spring", null, null, null, 10, 0, true),
                1, List.of(new SearchDocument("d001", input())));
        SearchResponse good = result.withReferences(Map.of("SPRING", new SearchResponse.Reference("Spring", "https://spring.io")));
        assertFalse(good.degraded());
        assertNotNull(good.items().getFirst().reference());
        assertTrue(result.withReferences(Map.of("SPRING",
                new SearchResponse.Reference("bad", "javascript:bad"))).degraded());
    }
}
