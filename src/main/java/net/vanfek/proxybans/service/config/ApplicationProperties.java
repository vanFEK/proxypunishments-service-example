package net.vanfek.proxybans.service.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * All tunables in one place.
 *
 * <p>Validated at startup: a blank service token used to mean "accept every request that also sends
 * a blank header", so the application now refuses to start instead.
 */
@Validated
@ConfigurationProperties(prefix = "proxybans")
public record ApplicationProperties(
        @NotNull Mode mode,
        @NotNull Security security,
        @NotNull Cache cache,
        Remote remote
) {

    public boolean isLocal() {
        return this.mode == Mode.LOCAL;
    }

    public enum Mode {

        /** Bans live in this service's own Postgres schema. */
        LOCAL,

        /** Bans live behind someone else's HTTP API; this service is a translating proxy. */
        REMOTE

    }

    public record Security(@NotBlank String serviceToken) {
    }

    public record Cache(@NotNull Duration activeBanTtl, @NotNull Duration lookupTtl, long maxSize) {
    }

    public record Remote(
            String baseUrl,
            String apiToken,
            String tokenHeader,
            Duration connectTimeout,
            Duration readTimeout,
            Paths paths
    ) {

        public record Paths(
                String activeBan,
                String issueBan,
                String liftBan,
                String listBans,
                String history,
                String lookup
        ) {
        }

    }

}
