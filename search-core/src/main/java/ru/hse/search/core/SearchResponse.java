package ru.hse.search.core;

import java.util.List;
import java.util.Map;

public record SearchResponse(String query, long total, int limit, int offset,
                             List<Hit> items, boolean degraded) {
    public SearchResponse { items = List.copyOf(items); }

    public record Reference(String title, String url) {}
    public record Hit(String id, String title, String snippet, Category category,
                      String sourceUrl, Double score, Reference reference) {}

    public static SearchResponse of(SearchRequest request, long total, List<SearchDocument> documents) {
        List<Hit> hits = documents.stream()
                .map(d -> new Hit(d.getId(), d.getTitle(), SearchText.snippet(d.getText()),
                        d.getCategory(), d.getSourceUrl(), d.getScore(), null)).toList();
        return new SearchResponse(request.q(), total, request.limit(), request.offset(), hits, false);
    }

    public List<Category> categories() {
        return items.stream().map(Hit::category).distinct().toList();
    }

    public SearchResponse withReferences(Map<String, Reference> references) {
        if (references == null || categories().stream().anyMatch(c -> !valid(references.get(c.name())))) {
            return unavailable();
        }
        List<Hit> enriched = items.stream().map(h -> new Hit(h.id(), h.title(), h.snippet(),
                h.category(), h.sourceUrl(), h.score(), references.get(h.category().name()))).toList();
        return new SearchResponse(query, total, limit, offset, enriched, false);
    }

    public SearchResponse unavailable() {
        return new SearchResponse(query, total, limit, offset, items, true);
    }

    private static boolean valid(Reference value) {
        if (value == null || value.title() == null || value.title().isBlank()) {
            return false;
        }
        try {
            SearchText.checkSourceUrl(value.url());
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
