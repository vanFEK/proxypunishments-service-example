package net.vanfek.proxypunishments.service.adapter.discord;

import net.vanfek.proxypunishments.service.config.ApplicationProperties;
import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import net.vanfek.proxypunishments.service.domain.notify.DisabledPunishmentAnnouncer;
import net.vanfek.proxypunishments.service.domain.notify.PunishmentAnnouncer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Clock;

@Configuration
class DiscordWebhookConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DiscordWebhookConfig.class);
    private static final String PREFIX = "proxypunishments.discord";

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    PunishmentAnnouncer discordWebhookAnnouncer(
            ApplicationProperties properties, PunishmentMetrics metrics,
            Clock clock
    ) {
        ApplicationProperties.Discord discord = properties.discord();

        if (discord.webhookUrl() == null || discord.webhookUrl().isBlank()) {
            LOG.error("proxypunishments.discord.enabled is true but no webhook URL is set — announcements stay off");

            return new DisabledPunishmentAnnouncer();
        }

        HttpClient httpClient = HttpClient.create().responseTimeout(discord.timeout());

        WebClient http = WebClient.builder()
                .baseUrl(discord.webhookUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();

        LOG.info("Discord announcements enabled — issued {}, lifted {}", discord.announceIssued(), discord.announceLifted());

        return new DiscordWebhookAnnouncer(http, metrics, clock, discord.announceIssued(), discord.announceLifted());
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "false", matchIfMissing = true)
    PunishmentAnnouncer disabledPunishmentAnnouncer() {
        return new DisabledPunishmentAnnouncer();
    }

}
