package ru.hse.search.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SearchProperties.class, MetadataProperties.class})
    static class PropertiesConfiguration {}

    @Test void bindsDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SearchProperties.class).maxConcurrent()).isEqualTo(64);
            assertThat(context.getBean(MetadataProperties.class).url()).isEqualTo("http://localhost:9080");
            assertThat(context.getBean(MetadataProperties.class).timeoutMs()).isEqualTo(1000);
        });
    }

    @Test void bindsExternalValues() {
        runner.withPropertyValues("search.max-concurrent=12", "metadata.timeout-ms=750",
                "metadata.url=http://metadata:9080").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SearchProperties.class).maxConcurrent()).isEqualTo(12);
            assertThat(context.getBean(MetadataProperties.class).timeoutMs()).isEqualTo(750);
            assertThat(context.getBean(MetadataProperties.class).url()).isEqualTo("http://metadata:9080");
        });
    }

    @Test void rejectsZeroConcurrency() {
        runner.withPropertyValues("search.max-concurrent=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void rejectsNegativeTimeout() {
        runner.withPropertyValues("metadata.timeout-ms=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void rejectsUnsupportedUrlScheme() {
        runner.withPropertyValues("metadata.url=ftp://localhost")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void rejectsBlankUrl() {
        runner.withPropertyValues("metadata.url=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void rejectsMalformedBaseUrlsBeforeServingRequests() {
        for (String value : new String[]{"http://#fragment", "http://user@metadata:9080",
                "http://metadata:9080?mode=slow", "http://metadata:99999", "http://metadata:0"}) {
            runner.withPropertyValues("metadata.url=" + value)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
