package com.chtholly.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 全局配置：文档元信息与 JWT Bearer 鉴权说明。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI chthollyOpenApi() {
        final String bearerAuth = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("Chtholly Hub API")
                        .description("Chtholly Hub 后端 REST API 文档")
                        .version("1.0")
                        .contact(new Contact().name("Chtholly Hub")))
                .addSecurityItem(new SecurityRequirement().addList(bearerAuth))
                .components(new Components()
                        .addSecuritySchemes(bearerAuth, new SecurityScheme()
                                .name(bearerAuth)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Access Token（Authorization: Bearer {token}）")));
    }
}
