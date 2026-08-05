package net.vanfek.proxybans.service.config;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.vanfek.proxybans.service.domain.model.ActiveBan;
import net.vanfek.proxybans.service.domain.model.UserIdentity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;

@Configuration
class CacheConfig {

    @Bean
    AsyncCache<UUID, Optional<ActiveBan>> activeBanCache(ApplicationProperties properties) {
        ApplicationProperties.Cache cache = properties.cache();

        return Caffeine.newBuilder()
                .expireAfterWrite(cache.activeBanTtl())
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
