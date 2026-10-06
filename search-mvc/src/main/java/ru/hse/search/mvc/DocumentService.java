package ru.hse.search.mvc;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.hse.search.core.DocumentInput;
import ru.hse.search.core.DocumentView;
import ru.hse.search.core.SearchDocument;
import ru.hse.search.core.SearchText;

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private final DocumentRepository repository;

    public DocumentService(DocumentRepository repository) {
        this.repository = repository;
    }

    public DocumentView get(String id) {
        return DocumentView.from(find(id));
    }

    public DocumentView create(DocumentInput input) {
        input.checkContent();
        if (input.version() != null) {
            throw new IllegalArgumentException("При создании документа версия не передаётся");
        }
        SearchDocument saved = repository.insert(new SearchDocument("doc-" + UUID.randomUUID(), input));
        log.info("Документ создан: id={}", saved.getId());
        return DocumentView.from(saved);
    }

    public DocumentView update(String id, DocumentInput input) {
        input.checkContent();
        SearchDocument document = find(id);
        checkVersion(document, input.version());
        document.apply(input);
        // @Version проверяется в самой операции записи, а не только этим Java-кодом.
        SearchDocument saved = repository.save(document);
        log.info("Документ изменён: id={}, version={}", saved.getId(), saved.getVersion());
        return DocumentView.from(saved);
    }

    public void delete(String id, long version) {
        SearchDocument document = find(id);
        checkVersion(document, version);
        // delete(entity), в отличие от deleteById, учитывает @Version.
        repository.delete(document);
        log.info("Документ удалён: id={}", id);
    }

    private SearchDocument find(String id) {
        SearchText.checkId(id);
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Документ не найден"));
    }

    private void checkVersion(SearchDocument document, Long expected) {
        if (expected == null || expected < 0) {
            throw new IllegalArgumentException("Для изменения или удаления нужна текущая версия документа");
        }
        if (!Objects.equals(document.getVersion(), expected)) {
            throw new OptimisticLockingFailureException("Документ уже изменён. Откройте его заново");
        }
    }
}
