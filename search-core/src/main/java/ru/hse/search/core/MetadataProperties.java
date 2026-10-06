package ru.hse.search.core;

import java.net.URI;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "metadata")
public record MetadataProperties(
        @DefaultValue("http://localhost:9080") @NotBlank
        @Pattern(regexp = "https?://[^\\s]+", message = "Адрес справочника должен начинаться с http:// или https://")
        String url,
        @DefaultValue("1000") @Min(value = 1, message = "Тайм-аут должен быть положительным")
        long timeoutMs) {
    @AssertTrue(message = "Нужен HTTP(S)-адрес справочника с хостом, без логина, query и fragment")
    public boolean isValidBaseUrl() {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || (uri.getPort() >= 1 && uri.getPort() <= 65535));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
