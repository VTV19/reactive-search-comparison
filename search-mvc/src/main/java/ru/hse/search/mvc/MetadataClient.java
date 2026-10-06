package ru.hse.search.mvc;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.annotation.PreDestroy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.hse.search.core.Category;
import ru.hse.search.core.MetadataUnavailableException;
import ru.hse.search.core.MetadataProperties;
import ru.hse.search.core.SearchResponse.Reference;

@Component
public class MetadataClient {
    private final HttpClient httpClient;
    private final RestClient client;

    public MetadataClient(RestClient.Builder builder,
                          MetadataProperties properties) {
        long timeoutMs = properties.timeoutMs();
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("Тайм-аут должен быть положительным");
        }
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        client = builder.baseUrl(properties.url()).requestFactory(factory).build();
    }

    public Map<String, Reference> fetch(List<Category> categories) {
        String values = categories.stream().map(Enum::name).collect(Collectors.joining(","));
        try {
            Map<String, Reference> result = client.get()
                    .uri(uri -> uri.path("/api/metadata").queryParam("categories", values).build())
                    .retrieve().body(new ParameterizedTypeReference<Map<String, Reference>>() {});
            if (result == null) {
                throw new RestClientException("Пустой ответ справочника");
            }
            return result;
        } catch (RestClientException error) {
            throw new MetadataUnavailableException(error);
        }
    }

    @PreDestroy
    public void close() {
        httpClient.close();
    }
}
