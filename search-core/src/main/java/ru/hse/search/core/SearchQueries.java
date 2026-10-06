package ru.hse.search.core;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;

public final class SearchQueries {
    private SearchQueries() {}

    public static Query count(SearchRequest request) {
        Query query = new Query();
        if (!request.q().isEmpty()) {
            query.addCriteria(text(request));
        }
        return category(query, request);
    }

    public static Query page(SearchRequest request) {
        Query query;
        if (request.q().isEmpty()) {
            query = new Query().with(Sort.by("title"));
        } else {
            TextQuery textQuery = TextQuery.queryText(text(request)).includeScore();
            query = request.sort() == SearchRequest.Order.RELEVANCE
                    ? textQuery.sortByScore()
                    : textQuery.with(Sort.by("title"));
        }
        // Равная релевантность не должна приводить к случайному порядку между страницами.
        query.with(Sort.by("id")).skip(request.offset()).limit(request.limit());
        return category(query, request);
    }

    private static TextCriteria text(SearchRequest request) {
        TextCriteria criteria = TextCriteria.forLanguage("none");
        return request.mode() == SearchRequest.Mode.PHRASE
                ? criteria.matchingPhrase(String.join(" ", request.terms()))
                : criteria.matchingAny(request.terms().stream().distinct().toArray(String[]::new));
    }

    private static Query category(Query query, SearchRequest request) {
        if (request.category() != null) {
            query.addCriteria(Criteria.where("category").is(request.category()));
        }
        query.maxTimeMsec(3000);
        return query;
    }
}
