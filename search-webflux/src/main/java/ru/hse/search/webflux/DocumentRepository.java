package ru.hse.search.webflux;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import ru.hse.search.core.SearchDocument;

public interface DocumentRepository extends ReactiveMongoRepository<SearchDocument, String> {
}
