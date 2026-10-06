package ru.hse.search.mvc;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import ru.hse.search.core.CatalogSchema;
import ru.hse.search.core.SearchDocument;

@Component
public class CatalogInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogInitializer.class);
    private final MongoTemplate mongo;
    private final boolean seedEnabled;

    public CatalogInitializer(MongoTemplate mongo, @Value("${catalog.seed.enabled:true}") boolean seedEnabled) {
        this.mongo = mongo;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        mongo.indexOps(SearchDocument.class).createIndex(CatalogSchema.textIndex());
        mongo.indexOps(SearchDocument.class).createIndex(CatalogSchema.categoryIndex());
        mongo.indexOps(SearchDocument.class).createIndex(CatalogSchema.titleIndex());
        if (seedEnabled) initializeData();
        log.info("MVC catalogue ready: indexes installed, documents={}", mongo.count(new Query(), SearchDocument.class));
    }

    private void initializeData() {
        Document state = mongo.findById(CatalogSchema.SEED_ID, Document.class, CatalogSchema.STATE_COLLECTION);
        if (state != null && "complete".equals(state.getString("status"))) return;

        if (state == null && mongo.count(new Query(), SearchDocument.class) > 0) {
            // An existing catalogue from an earlier release is retained without refilling deleted entries.
            mongo.save(CatalogSchema.state("complete"), CatalogSchema.STATE_COLLECTION);
            return;
        }
        mongo.save(CatalogSchema.state("initializing"), CatalogSchema.STATE_COLLECTION);
        for (SearchDocument document : CatalogSchema.initialDocuments()) {
            mongo.upsert(CatalogSchema.seedQuery(document), CatalogSchema.insertOnly(document), SearchDocument.class);
        }
        mongo.save(CatalogSchema.state("complete"), CatalogSchema.STATE_COLLECTION);
    }
}
