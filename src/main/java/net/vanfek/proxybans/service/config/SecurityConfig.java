package net.vanfek.proxybans.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Configuration
class SecurityConfig {

    static final String TOKEN_HEADER = "X-Service-Token";

    // Only the health probes are public; /actuator/env, metrics and prometheus still need the token.
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    );

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter serviceTokenFilter(ApplicationProperties properties) {
        byte[] expected = properties.security().serviceToken().getBytes(StandardCharsets.UTF_8);

        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            if (PUBLIC_PATHS.contains(request.getPath().value())) {
                return chain.filter(exchange);
            }

            String provided = request.getHeaders().getFirst(TOKEN_HEADER);

            if (provided == null || !matches(provided, expected)) {
                return reject(exchange);
            }

            return chain.filter(exchange);
        };
    }

    // Constant-time comparison, so the token cannot be recovered one byte at a time.
    private static boolean matches(String provided, byte[] expected) {
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected);
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);

        return response.setComplete();
    }

}
