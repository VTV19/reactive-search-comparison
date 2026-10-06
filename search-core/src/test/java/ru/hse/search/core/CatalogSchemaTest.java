package ru.hse.search.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSchemaTest {
    @Test void seedHasUniqueValidatedDocuments() {
        var documents = CatalogSchema.initialDocuments();
        assertEquals(36, documents.size());
        assertEquals(36, documents.stream().map(SearchDocument::getId).distinct().count());
        assertTrue(documents.stream().allMatch(document -> document.getCategory() != null));
    }

    @Test void repeatImportOnlySetsFieldsOnInsertion() {
        var document = CatalogSchema.initialDocuments().getFirst();
        var update = CatalogSchema.insertOnly(document).getUpdateObject();
        assertTrue(update.containsKey("$setOnInsert"));
        assertFalse(update.containsKey("$set"));
        assertFalse(update.containsKey("$inc"));
    }

    @Test void requiredIndexNamesAndLanguageAreStable() {
        var text = CatalogSchema.textIndex().getIndexOptions();
        assertEquals("documents_text", text.getString("name"));
        assertEquals("none", text.getString("default_language"));
        assertEquals("category_title", CatalogSchema.categoryIndex().getIndexOptions().getString("name"));
        assertEquals(new org.bson.Document("title", 1).append("_id", 1),
                CatalogSchema.titleIndex().getIndexKeys());
    }
}
