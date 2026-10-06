package ru.hse.search.webflux;

import java.time.Duration;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.hse.search.core.CatalogSchema;
import ru.hse.search.core.SearchDocument;

@Component
public class CatalogInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogInitializer.class);
    private final ReactiveMongoTemplate mongo;
    private final boolean seedEnabled;

    public CatalogInitializer(ReactiveMongoTemplate mongo, @Value("${catalog.seed.enabled:true}") boolean seedEnabled) {
        this.mongo = mongo;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Startup runs on the main thread. Readiness is withheld until indexes and seed are ready.
        // This is the only blocking boundary in this module; HTTP request processing remains reactive.
        mongo.indexOps(SearchDocument.class).createIndex(CatalogSchema.textIndex())
                .then(mongo.indexOps(SearchDocument.class).createIndex(CatalogSchema.categoryIndex()))
                .then(mongo.indexOps(SearchDocument.class).createIndex(CatalogSchema.titleIndex()))
                .then(seedEnabled ? initializeData() : Mono.empty())
                .then(mongo.count(new Query(), SearchDocument.class))
                .doOnNext(count -> log.info("WebFlux catalogue ready: indexes installed, documents={}", count))
                .block(Duration.ofSeconds(45));
    }

    private Mono<Void> initializeData() {
        return mongo.findById(CatalogSchema.SEED_ID, Document.class, CatalogSchema.STATE_COLLECTION)
                .defaultIfEmpty(new Document())
                .flatMap(state -> {
                    if ("complete".equals(state.getString("status"))) return Mono.empty();
                    if (!state.isEmpty()) return importData();
                    return mongo.count(new Query(), SearchDocument.class).flatMap(count ->
                            count > 0 ? complete() : importData());
                });
    }

    private Mono<Void> importData() {
        return mongo.save(CatalogSchema.state("initializing"), CatalogSchema.STATE_COLLECTION)
                .thenMany(Flux.defer(() -> Flux.fromIterable(CatalogSchema.initialDocuments())))
                .concatMap(document -> mongo.upsert(CatalogSchema.seedQuery(document),
                        CatalogSchema.insertOnly(document), SearchDocument.class))
                .then(complete());
    }

    private Mono<Void> complete() {
        return mongo.save(CatalogSchema.state("complete"), CatalogSchema.STATE_COLLECTION).then();
    }
}
