package ru.hse.search.metadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.hse.search.core.Category;
import ru.hse.search.core.SearchResponse.Reference;

@RestController
public class MetadataController {
    private static final Logger log = LoggerFactory.getLogger(MetadataController.class);
    private final Map<Category, Reference> references = Map.of(
            Category.JAVA, new Reference("Java 21: документация", "https://docs.oracle.com/en/java/javase/21/"),
            Category.SPRING, new Reference("Spring Framework", "https://docs.spring.io/spring-framework/reference/"),
            Category.REACTOR, new Reference("Project Reactor", "https://projectreactor.io/docs/core/release/reference/"),
            Category.MONGODB, new Reference("Spring Data MongoDB", "https://docs.spring.io/spring-data/mongodb/reference/"),
            Category.DOCKER, new Reference("Docker: руководство", "https://docs.docker.com/"),
            Category.TESTING, new Reference("Spring: тестирование", "https://docs.spring.io/spring-framework/reference/testing.html"));
    private volatile String mode = "normal";

    @GetMapping("/api/metadata")
    public ResponseEntity<?> metadata(@RequestParam("categories") List<Category> categories) {
        if (categories.isEmpty() || categories.size() > Category.values().length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужен список от 1 до 6 категорий");
        }
        String current = mode;
        if (current.equals("error")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Демонстрационный отказ");
        }
        if (current.equals("invalid")) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{broken");
        }
        if (current.equals("slow") || current.equals("delayed")) {
            // Только стендовый режим: моделирует задержку необязательной внешней службы.
            try {
                Thread.sleep(current.equals("slow") ? 3000 : 200);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Запрос прерван");
            }
        }
        Map<String, Reference> result = new LinkedHashMap<>();
        categories.forEach(category -> result.put(category.name(), references.get(category)));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/admin/mode")
    public Map<String, String> setMode(@RequestParam("mode") String mode) {
        if (!List.of("normal", "delayed", "error", "slow", "invalid").contains(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Режим: normal, delayed, error, slow, invalid");
        }
        this.mode = mode;
        log.info("Режим справочника изменён: {}", mode);
        return Map.of("mode", mode);
    }

    @GetMapping("/admin/mode")
    public Map<String, String> mode() {
        return Map.of("mode", mode);
    }
}
