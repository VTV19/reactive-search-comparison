package ru.hse.search.mvc;

import org.springframework.data.mongodb.repository.MongoRepository;
import ru.hse.search.core.SearchDocument;

public interface DocumentRepository extends MongoRepository<SearchDocument, String> {
}
