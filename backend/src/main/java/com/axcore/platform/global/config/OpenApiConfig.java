package com.axcore.platform.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI(/swagger-ui.html)와 OpenAPI 문서(/v3/api-docs)의 제목. API 계약의 원본은 Notion API 명세서다. */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI platformOpenApi() {
        return new OpenAPI().info(new Info().title("AXCore Platform API").version("v1"));
    }
}
