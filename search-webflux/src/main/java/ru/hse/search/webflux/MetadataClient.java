package ru.hse.search.webflux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import io.netty.channel.ChannelOption;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import ru.hse.search.core.Category;
import ru.hse.search.core.MetadataUnavailableException;
import ru.hse.search.core.MetadataProperties;
import ru.hse.search.core.SearchResponse.Reference;

@Component
public class MetadataClient {
    private final WebClient client;
    private final Duration timeout;

    public MetadataClient(WebClient.Builder builder,
                          MetadataProperties properties) {
        long timeoutMs = properties.timeoutMs();
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("Тайм-аут должен быть положительным");
        }
        timeout = Duration.ofMillis(timeoutMs);
        HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(timeout);
        client = builder.baseUrl(properties.url())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024)).build();
    }

    public Mono<Map<String, Reference>> fetch(List<Category> categories) {
        String values = categories.stream().map(Enum::name).collect(Collectors.joining(","));
        return client.get().uri(uri -> uri.path("/api/metadata").queryParam("categories", values).build())
                .retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Reference>>() {})
                .switchIfEmpty(Mono.error(new IllegalStateException("Пустой ответ справочника")))
                .timeout(timeout)
                .onErrorMap(error -> new MetadataUnavailableException(error));
    }
}
