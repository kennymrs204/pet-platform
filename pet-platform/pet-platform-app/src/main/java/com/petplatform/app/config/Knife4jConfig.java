package com.petplatform.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / OpenAPI 文档装配。声明 Bearer JWT 安全方案与 X-Store-Id 门店上下文头，
 * 便于在 doc.html 中携带令牌联调受保护端点。
 */
@Configuration
public class Knife4jConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI petPlatformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("宠物服务/托管平台 API")
                        .version("v1")
                        .description("模块化单体，统一 Result 响应；受保护端点需携带 Bearer 令牌，门店操作附带 X-Store-Id 头。"))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes("storeContext", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Store-Id")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER).addList("storeContext"));
    }
}
