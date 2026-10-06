package ru.hse.search.webflux;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
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

    public Mono<DocumentView> get(String id) {
        return find(id).map(DocumentView::from);
    }

    public Mono<DocumentView> create(DocumentInput input) {
        return Mono.defer(() -> {
            input.checkContent();
            if (input.version() != null) {
                return Mono.error(new IllegalArgumentException("При создании документа версия не передаётся"));
            }
            return repository.insert(new SearchDocument("doc-" + UUID.randomUUID(), input))
                    .doOnNext(saved -> log.info("Документ создан: id={}", saved.getId()))
                    .map(DocumentView::from);
        });
    }

    public Mono<DocumentView> update(String id, DocumentInput input) {
        return Mono.defer(() -> {
            input.checkContent();
            return find(id);
        }).flatMap(document -> {
            checkVersion(document, input.version());
            document.apply(input);
            // Реактивный драйвер делает запись без блокирующего ожидания потока запроса.
            return repository.save(document);
        }).doOnNext(saved -> log.info("Документ изменён: id={}, version={}", saved.getId(), saved.getVersion()))
          .map(DocumentView::from);
    }

    public Mono<Void> delete(String id, long version) {
        return find(id).flatMap(document -> {
            checkVersion(document, version);
            return repository.delete(document);
        }).doOnSuccess(ignored -> log.info("Документ удалён: id={}", id));
    }

    private Mono<SearchDocument> find(String id) {
        return Mono.defer(() -> {
            SearchText.checkId(id);
            return repository.findById(id).switchIfEmpty(Mono.error(
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Документ не найден")));
        });
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
