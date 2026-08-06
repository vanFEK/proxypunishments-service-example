package net.vanfek.proxybans.service.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private static final String TOKEN = "s3cret-token";

    private WebFilter filter;
    private AtomicBoolean chainCalled;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties(
                ApplicationProperties.Mode.LOCAL, new ApplicationProperties.Security(TOKEN),
                new ApplicationProperties.Cache(Duration.ofSeconds(30), Duration.ofMinutes(10), 1000L), null
        );

        this.filter = new SecurityConfig().serviceTokenFilter(properties);
        this.chainCalled = new AtomicBoolean(false);
        this.chain = exchange -> {
            this.chainCalled.set(true);

            return Mono.empty();
        };
    }

    @Test
    void passesRequestsCarryingTheCorrectToken() {
        MockServerWebExchange exchange = exchange("/api/v1/bans", TOKEN);

        this.filter.filter(exchange, this.chain).block();

        assertThat(this.chainCalled).isTrue();
    }

    @Test
    void rejectsRequestsWithoutAToken() {
        MockServerWebExchange exchange = exchange("/api/v1/bans", null);

        this.filter.filter(exchange, this.chain).block();

        assertThat(this.chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an empty header does not match a configured token")
    void rejectsAnEmptyToken() {
        MockServerWebExchange exchange = exchange("/api/v1/bans", "");

        this.filter.filter(exchange, this.chain).block();

        assertThat(this.chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsAWrongToken() {
        MockServerWebExchange exchange = exchange("/api/v1/bans", "not-the-token");

        this.filter.filter(exchange, this.chain).block();

        assertThat(this.chainCalled).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    @DisplayName("health probes stay public so orchestrators can reach them")
    void allowsHealthProbesWithoutAToken(String path) {
        this.filter.filter(exchange(path, null), this.chain).block();

        assertThat(this.chainCalled).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/metrics", "/actuator/prometheus", "/actuator/health/../env"})
    @DisplayName("every other actuator endpoint needs the token — they describe the deployment")
    void protectsTheRemainingActuatorEndpoints(String path) {
        MockServerWebExchange exchange = exchange(path, null);

        this.filter.filter(exchange, this.chain).block();

        assertThat(this.chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static MockServerWebExchange exchange(String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);

        if (token != null) {
            request = request.header(SecurityConfig.TOKEN_HEADER, token);
        }

        return MockServerWebExchange.from(request.build());
    }

}
