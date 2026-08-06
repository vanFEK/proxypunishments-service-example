package net.vanfek.proxypunishments.service.adapter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vanfek.proxypunishments.service.adapter.discord.DiscordWebhookAnnouncer;
import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.BanType;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.metrics.MicrometerPunishmentMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DiscordWebhookAnnouncerTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private SimpleMeterRegistry registry;
    private AtomicInteger requests;

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        this.requests = new AtomicInteger();
    }

    @Test
    @DisplayName("Discord being unreachable never reaches the moderation action")
    void swallowsTransportFailures() {
        DiscordWebhookAnnouncer announcer = announcer(request -> {
            this.requests.incrementAndGet();

            return Mono.error(new IOException("connection refused"));
        });

        assertThatCode(() -> announcer.announceIssued(punishment())).doesNotThrowAnyException();

        assertThat(this.requests.get()).isEqualTo(1);
        assertThat(counter(PunishmentMetrics.WEBHOOK_FAILED)).isEqualTo(1.0D);
    }

    @Test
    @DisplayName("an error status is logged and swallowed rather than retried")
    void swallowsErrorStatuses() {
        DiscordWebhookAnnouncer announcer = announcer(request -> {
            this.requests.incrementAndGet();

            return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
        });

        announcer.announceIssued(punishment());

        assertThat(this.requests.get()).isEqualTo(1);
        assertThat(counter(PunishmentMetrics.WEBHOOK_FAILED)).isEqualTo(1.0D);
    }

    @Test
    @DisplayName("a 429 silences the webhook for the window Discord asks for instead of retrying into it")
    void aRateLimitStopsFurtherRequests() {
        DiscordWebhookAnnouncer announcer = announcer(request -> {
            this.requests.incrementAndGet();

            return Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "30")
                    .build());
        });

        announcer.announceIssued(punishment());
        announcer.announceIssued(punishment());
        announcer.announceLifted(lift());

        assertThat(this.requests.get()).isEqualTo(1);
        assertThat(counter(PunishmentMetrics.WEBHOOK_RATE_LIMITED)).isEqualTo(1.0D);
        assertThat(counter(PunishmentMetrics.WEBHOOK_SUPPRESSED)).isEqualTo(2.0D);
    }

    @Test
    void countsSuccessfulDeliveries() {
        DiscordWebhookAnnouncer announcer = announcer(request -> {
            this.requests.incrementAndGet();

            return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
        });

        announcer.announceIssued(punishment());
        announcer.announceLifted(lift());

        assertThat(this.requests.get()).isEqualTo(2);
        assertThat(counter(PunishmentMetrics.WEBHOOK_SENT)).isEqualTo(2.0D);
    }

    @Test
    @DisplayName("the announced events are configurable, and a disabled one sends nothing at all")
    void respectsTheConfiguredEvents() {
        DiscordWebhookAnnouncer announcer = new DiscordWebhookAnnouncer(
                client(request -> {
                    this.requests.incrementAndGet();

                    return Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build());
                }),
                new MicrometerPunishmentMetrics(this.registry), Clock.fixed(NOW, ZoneOffset.UTC),
                true, false
        );

        announcer.announceIssued(punishment());
        announcer.announceLifted(lift());

        assertThat(this.requests.get()).isEqualTo(1);
    }

    private DiscordWebhookAnnouncer announcer(ExchangeFunction exchange) {
        return new DiscordWebhookAnnouncer(
                client(exchange), new MicrometerPunishmentMetrics(this.registry),
                Clock.fixed(NOW, ZoneOffset.UTC), true, true
        );
    }

    private static WebClient client(ExchangeFunction exchange) {
        return WebClient.builder().baseUrl("https://discord.invalid/webhook").exchangeFunction(exchange).build();
    }

    private double counter(String outcome) {
        return this.registry.find(MicrometerPunishmentMetrics.WEBHOOK_DELIVERIES)
                .tag("outcome", outcome)
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private static ActivePunishment punishment() {
        return new ActivePunishment(
                1L, PLAYER, Optional.of("Steve"),
                PunishmentKind.BAN, Optional.empty(),
                ADMIN, "Admin",
                "griefing",
                NOW, Optional.empty(),
                BanType.PERMANENT
        );
    }

    private static LiftPunishmentCommand lift() {
        return new LiftPunishmentCommand(PLAYER, PunishmentKind.BAN, ADMIN, "Admin", "appeal accepted");
    }

}
