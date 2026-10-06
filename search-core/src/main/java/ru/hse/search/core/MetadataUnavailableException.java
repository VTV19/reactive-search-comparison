package ru.hse.search.core;

public class MetadataUnavailableException extends RuntimeException {
    public MetadataUnavailableException(Throwable cause) {
        super("Справочник дополнительных материалов недоступен", cause);
    }
}
