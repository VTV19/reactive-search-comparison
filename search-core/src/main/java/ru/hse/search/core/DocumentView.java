package ru.hse.search.core;

public record DocumentView(String id, Long version, String title, String text,
                           Category category, String sourceUrl) {
    public static DocumentView from(SearchDocument document) {
        return new DocumentView(document.getId(), document.getVersion(), document.getTitle(),
                document.getText(), document.getCategory(), document.getSourceUrl());
    }
}
