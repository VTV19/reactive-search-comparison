package ru.hse.search.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Healthcheck for containers. It uses only the JDK and does not start Spring. */
public final class HttpProbe {
    private HttpProbe() {}

    public static void main(String[] args) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            if (args.length != 1) {
                throw new IllegalArgumentException("Expected a health endpoint URL");
            }
            var request = HttpRequest.newBuilder(URI.create(args[0]))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean up = response.statusCode() == 200
                    && response.body().matches("(?s).*\"status\"\\s*:\\s*\"UP\".*");
            if (!up) System.exit(1);
        } catch (Exception error) {
            System.err.println("Healthcheck: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
