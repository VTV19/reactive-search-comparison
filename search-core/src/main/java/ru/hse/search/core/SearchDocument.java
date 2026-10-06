package ru.hse.search.core;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.TextScore;

@Document(collection = "documents")
public class SearchDocument {
    @Id
    private String id;
    @Version
    private Long version;
    private String title;
    private String text;
    private Category category;
    private String sourceUrl;
    @TextScore
    private Double score;

    public SearchDocument() {}

    public SearchDocument(String id, DocumentInput input) {
        this.id = id;
        apply(input);
    }

    public void apply(DocumentInput input) {
        input.checkContent();
        title = input.title();
        text = input.text();
        category = input.category();
        sourceUrl = input.sourceUrl();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}
