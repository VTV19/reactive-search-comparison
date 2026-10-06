package ru.hse.search.webflux;

import java.net.URI;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.hse.search.core.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final SearchService search;
    private final DocumentService documents;

    public ApiController(SearchService search, DocumentService documents) {
        this.search = search;
        this.documents = documents;
    }

    @GetMapping("/search")
    public Mono<SearchResponse> search(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "category", required = false) Category category,
            @RequestParam(name = "mode", defaultValue = "ANY") SearchRequest.Mode mode,
            @RequestParam(name = "sort", defaultValue = "RELEVANCE") SearchRequest.Order sort,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "enrich", defaultValue = "true") boolean enrich) {
        return search.search(new SearchRequest(q, category, mode, sort, limit, offset, enrich));
    }

    @GetMapping("/documents/{id}")
    public Mono<DocumentView> get(@PathVariable("id") String id) {
        return documents.get(id);
    }
    
    @PostMapping("/documents")
    public Mono<ResponseEntity<DocumentView>> create(@Valid @RequestBody DocumentInput input) {
        return documents.create(input).map(saved ->
                ResponseEntity.created(URI.create("/api/documents/" + saved.id())).body(saved));
    }

    @PutMapping("/documents/{id}")
    public Mono<DocumentView> update(@PathVariable("id") String id, @Valid @RequestBody DocumentInput input) {
        return documents.update(id, input);
    }

    @DeleteMapping("/documents/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("id") String id,
                                             @RequestParam("version") long version) {
        return documents.delete(id, version).thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        // Identifying the application must not depend on MongoDB or the optional HTTP service.
        return Map.of("application", "Spring WebFlux", "implementation", "webflux",
                "version", "3.1.0", "categories", Category.values());
    }
}
