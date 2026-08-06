package net.vanfek.proxypunishments.service.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    private static final String TOKEN_SCHEME = "serviceToken";

    @Bean
    OpenAPI proxyPunishmentsOpenApi() {
        SecurityScheme scheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(SecurityConfig.TOKEN_HEADER)
                .description("Shared secret between the proxy plugin and this service");

        Info info = new Info()
                .title("ProxyPunishments Service API")
                .version("v1")
                .description("Punishment storage and lookup for Minecraft proxies. Every endpoint except "
                        + "/actuator/health requires the X-Service-Token header.");

        return new OpenAPI()
                .info(info)
                .components(new Components().addSecuritySchemes(TOKEN_SCHEME, scheme))
                .addSecurityItem(new SecurityRequirement().addList(TOKEN_SCHEME));
    }

}
