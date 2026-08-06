package net.vanfek.proxypunishments.service.config;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
class CacheConfig {

    @Bean
    AsyncCache<ActivePunishmentKey, Optional<ActivePunishment>> activePunishmentCache(ApplicationProperties properties) {
        ApplicationProperties.Cache cache = properties.cache();

        return Caffeine.newBuilder()
                .expireAfterWrite(cache.activePunishmentTtl())
                .maximumSize(cache.maxSize())
                .buildAsync();
    }

    @Bean
    AsyncCache<IpAddress, Optional<ActivePunishment>> ipBanCache(ApplicationProperties properties) {
        ApplicationProperties.Cache cache = properties.cache();

        return Caffeine.newBuilder()
                .expireAfterWrite(cache.activePunishmentTtl())
                .maximumSize(cache.maxSize())
                .buildAsync();
    }

    @Bean
    AsyncCache<String, Optional<UserIdentity>> lookupCache(ApplicationProperties properties) {
        ApplicationProperties.Cache cache = properties.cache();

        return Caffeine.newBuilder()
                .expireAfterWrite(cache.lookupTtl())
                .maximumSize(cache.maxSize())
                .buildAsync();
    }

}
