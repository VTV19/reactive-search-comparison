package ru.hse.search.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Index definitions and the versioned initial catalogue shared by both applications. */
public final class CatalogSchema {
    public static final String STATE_COLLECTION = "_catalog_state";
    public static final String SEED_ID = "catalog-v1";

    private CatalogSchema() {}

    public static TextIndexDefinition textIndex() {
        return new TextIndexDefinition.TextIndexDefinitionBuilder()
                .named("documents_text").onField("title", 3F).onField("text", 1F)
                .withDefaultLanguage("none").build();
    }

    public static Index categoryIndex() {
        return new Index().on("category", Sort.Direction.ASC)
                .on("title", Sort.Direction.ASC).on("_id", Sort.Direction.ASC)
                .named("category_title");
    }

    public static Index titleIndex() {
        // The category-first index cannot supply global catalogue ordering.
        return new Index().on("title", Sort.Direction.ASC).on("_id", Sort.Direction.ASC)
                .named("title_id");
    }

    public static List<SearchDocument> initialDocuments() {
        try (InputStream input = CatalogSchema.class.getResourceAsStream("/documents.json")) {
            if (input == null) throw new IllegalStateException("Missing documents.json");
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<Document> rows = Document.parse("{\"items\":" + json + "}").getList("items", Document.class);
            List<SearchDocument> result = new ArrayList<>();
            var ids = new HashSet<String>();
            for (Document row : rows) {
                String id = row.getString("id");
                SearchText.checkId(id);
                if (!ids.add(id)) throw new IllegalStateException("Duplicate seed id: " + id);
                var value = new DocumentInput(row.getString("title"), row.getString("text"),
                        Category.valueOf(row.getString("category")), row.getString("sourceUrl"), null);
                result.add(new SearchDocument(id, value));
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read initial catalogue", error);
        }
    }

    public static Query seedQuery(SearchDocument document) {
        return Query.query(Criteria.where("_id").is(document.getId()));
    }

    public static Update insertOnly(SearchDocument document) {
        // Retrying an interrupted import must never overwrite a document edited by a user.
        return new Update().setOnInsert("title", document.getTitle())
                .setOnInsert("text", document.getText())
                .setOnInsert("category", document.getCategory())
                .setOnInsert("sourceUrl", document.getSourceUrl())
                .setOnInsert("version", 0L);
    }

    public static Document state(String status) {
        return new Document("_id", SEED_ID).append("status", status);
    }
}
