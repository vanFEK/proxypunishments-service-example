package net.vanfek.proxypunishments.service.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "proxypunishments")
public record ApplicationProperties(
        @NotNull Mode mode, @NotNull Security security,
        @NotNull Cache cache, Remote remote,
        Redis redis, Discord discord
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

    public record Cache(@NotNull Duration activePunishmentTtl, @NotNull Duration lookupTtl, long maxSize) {
    }

    public record Redis(
            boolean enabled, String host, int port,
            String username, String password, int database,
            String channel, Duration connectTimeout
    ) {
    }

    public record Discord(
            boolean enabled, String webhookUrl,
            boolean announceIssued, boolean announceLifted,
            Duration timeout
    ) {
    }

    public record Remote(
            String baseUrl, String apiToken, String tokenHeader,
            Duration connectTimeout, Duration readTimeout,
            Paths paths
    ) {

        public record Paths(
                String activeBan, String issueBan,
                String liftBan, String listBans,
                String activeMute, String issueMute,
                String liftMute, String listMutes,
                String activeIpBan, String issueIpBan,
                String liftIpBan, String listIpBans,
                String history, String lookup
        ) {
        }

    }

}
