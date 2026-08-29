package com.migrationsentinel.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI(@Value("${app.version:dev}") String version) {
        return new OpenAPI().info(new Info()
                .title("Migration Sentinel API")
                .description("Agentic Flyway migration safety reviewer — micro1 Agentic Workflows Hackathon")
                .version(version)
                .contact(new Contact().name("Migration Sentinel"))
                .license(new License().name("MIT")));
    }
}
