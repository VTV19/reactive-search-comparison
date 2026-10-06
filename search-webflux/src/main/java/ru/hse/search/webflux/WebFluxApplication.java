package ru.hse.search.webflux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ru.hse.search.core.SearchProperties;
import ru.hse.search.core.MetadataProperties;

@SpringBootApplication
@EnableConfigurationProperties({SearchProperties.class, MetadataProperties.class})
public class WebFluxApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WebFluxApplication.class);
        application.setWebApplicationType(WebApplicationType.REACTIVE);
        application.run(args);
    }
}
