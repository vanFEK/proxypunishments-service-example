package net.vanfek.proxypunishments.service.adapter.discord;

import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;
import net.vanfek.proxypunishments.service.domain.notify.PunishmentAnnouncer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A 429 silences the webhook for as long as Discord asks and drops what it would have said — retrying a moderation log is worse. */
public final class DiscordWebhookAnnouncer implements PunishmentAnnouncer {

    private static final Logger LOG = LoggerFactory.getLogger(DiscordWebhookAnnouncer.class);
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int FIRST_ERROR_STATUS = 400;
    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

    private final WebClient http;
    private final PunishmentMetrics metrics;
    private final Clock clock;
    private final boolean announceIssued;
    private final boolean announceLifted;

    private final AtomicReference<Instant> silentUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public DiscordWebhookAnnouncer(
            WebClient http, PunishmentMetrics metrics, Clock clock,
            boolean announceIssued, boolean announceLifted
    ) {
        this.http = http;
        this.metrics = metrics;
        this.clock = clock;
        this.announceIssued = announceIssued;
        this.announceLifted = announceLifted;
    }

    @Override
    public void announceIssued(ActivePunishment punishment) {
        if (!this.announceIssued) {
            return;
        }

        post(DiscordEmbeds.issued(punishment));
    }

    @Override
    public void announceLifted(LiftPunishmentCommand command) {
        if (!this.announceLifted) {
            return;
        }

        post(DiscordEmbeds.lifted(command));
    }

    @Override
    public void announceIpBanLifted(LiftIpBanCommand command) {
        if (!this.announceLifted) {
            return;
        }

        post(DiscordEmbeds.ipBanLifted(command));
    }

    private void post(Map<String, Object> payload) {
        if (this.clock.instant().isBefore(this.silentUntil.get())) {
            this.metrics.webhookDelivery(PunishmentMetrics.WEBHOOK_SUPPRESSED);

            return;
        }

        try {
            this.http.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchangeToMono(this::handle)
                    .doOnError(this::onFailure)
                    .onErrorComplete()
                    .subscribe();
        } catch (RuntimeException ex) {
            onFailure(ex);
        }
    }

    private Mono<Void> handle(ClientResponse response) {
        int status = response.statusCode().value();

        if (status == TOO_MANY_REQUESTS) {
            silence(retryAfter(response));
            this.metrics.webhookDelivery(PunishmentMetrics.WEBHOOK_RATE_LIMITED);

            return response.releaseBody();
        }

        if (status >= FIRST_ERROR_STATUS) {
            logOnce("Discord webhook answered HTTP {} — the announcement was dropped", status);
            this.metrics.webhookDelivery(PunishmentMetrics.WEBHOOK_FAILED);

            return response.releaseBody();
        }

        this.failureLogged.set(false);
        this.metrics.webhookDelivery(PunishmentMetrics.WEBHOOK_SENT);

        return response.releaseBody();
    }

    private void silence(Duration backoff) {
        Instant until = this.clock.instant().plus(backoff);

        this.silentUntil.set(until);

        logOnce("Discord rate-limited the webhook — announcements are dropped until {}", until);
    }

    private static Duration retryAfter(ClientResponse response) {
        String header = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);

        if (header == null || header.isBlank()) {
            return DEFAULT_BACKOFF;
        }

        try {
            Duration requested = Duration.ofMillis(Math.round(Double.parseDouble(header.trim()) * 1000.0D));

            return requested.isNegative() || requested.isZero() ? DEFAULT_BACKOFF : min(requested, MAX_BACKOFF);
        } catch (NumberFormatException ex) {
            return DEFAULT_BACKOFF;
        }
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private void onFailure(Throwable throwable) {
        this.metrics.webhookDelivery(PunishmentMetrics.WEBHOOK_FAILED);

        if (this.failureLogged.compareAndSet(false, true)) {
            LOG.warn("Discord webhook could not be reached — announcements are dropped until it recovers", throwable);
        }
    }

    private void logOnce(String message, Object argument) {
        if (this.failureLogged.compareAndSet(false, true)) {
            LOG.warn(message, argument);
        }
    }

}
