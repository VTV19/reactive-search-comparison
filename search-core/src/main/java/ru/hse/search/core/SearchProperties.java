package ru.hse.search.core;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "search")
public record SearchProperties(
        @DefaultValue("64") @Min(value = 1, message = "Лимит поисков должен быть положительным")
        int maxConcurrent) {
}
