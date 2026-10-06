package ru.hse.search.core;

import java.util.List;

public record SearchRequest(String q, Category category, Mode mode, Order sort,
                            int limit, int offset, boolean enrich) {
    public enum Mode { ANY, PHRASE }
    public enum Order { RELEVANCE, TITLE }

    public SearchRequest {
        q = q == null ? "" : q.strip();
        mode = mode == null ? Mode.ANY : mode;
        sort = sort == null ? Order.RELEVANCE : sort;
        if (q.length() > 200 || limit < 1 || limit > 50 || offset < 0 || offset > 10000) {
            throw new IllegalArgumentException("Запрос: до 200 символов; limit: 1–50; offset: 0–10000");
        }
        List<String> words = SearchText.words(q);
        if (!q.isEmpty() && (words.isEmpty() || words.size() > 12)) {
            throw new IllegalArgumentException("Поисковый запрос должен содержать от 1 до 12 слов");
        }
    }

    public List<String> terms() {
        return SearchText.words(q);
    }
}
