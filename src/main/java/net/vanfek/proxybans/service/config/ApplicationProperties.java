package net.vanfek.proxybans.service.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "proxybans")
public record ApplicationProperties(
        @NotNull Mode mode, @NotNull Security security,
        @NotNull Cache cache, Remote remote
) {

    public boolean isLocal() {
        return this.mode == Mode.LOCAL;
    }

    public enum Mode {

        LOCAL,
        REMOTE

    }

    // @NotBlank, not a default: a blank token must fail startup instead of authenticating blank headers.
    public record Security(@NotBlank String serviceToken) {
    }

    public record Cache(@NotNull Duration activeBanTtl, @NotNull Duration lookupTtl, long maxSize) {
    }

    public record Remote(
            String baseUrl, String apiToken, String tokenHeader,
            Duration connectTimeout, Duration readTimeout,
            Paths paths
    ) {

        public record Paths(
                String activeBan, String issueBan, String liftBan,
                String listBans, String history, String lookup
        ) {
        }

    }

}
