package net.vanfek.proxypunishments.service.adapter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vanfek.proxypunishments.service.config.ApplicationProperties;
import net.vanfek.proxypunishments.service.domain.invalidation.DisabledInvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import net.vanfek.proxypunishments.service.domain.service.PunishmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.UUID;

@Configuration
class RedisInvalidationConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RedisInvalidationConfig.class);
    private static final String PREFIX = "proxypunishments.redis";

    private final String origin = UUID.randomUUID().toString();

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    LettuceConnectionFactory invalidationConnectionFactory(ApplicationProperties properties) {
        ApplicationProperties.Redis redis = properties.redis();

        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(redis.host(), redis.port());
        standalone.setDatabase(redis.database());

        if (redis.username() != null && !redis.username().isBlank()) {
            standalone.setUsername(redis.username());
        }

        if (redis.password() != null && !redis.password().isBlank()) {
            standalone.setPassword(redis.password());
        }

        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(redis.connectTimeout())
                .build();

        LOG.info("Redis cache invalidation enabled — channel {} on {}:{}", redis.channel(), redis.host(), redis.port());

        return new LettuceConnectionFactory(standalone, client);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    RedisInvalidationChannel invalidationChannel(LettuceConnectionFactory factory, ApplicationProperties properties) {
        return new RedisInvalidationChannel(factory, properties.redis().channel());
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    InvalidationPublisher redisInvalidationPublisher(
            InvalidationChannel channel, ObjectMapper json,
            PunishmentMetrics metrics
    ) {
        return new RedisInvalidationPublisher(channel, new InvalidationCodec(json), metrics, this.origin);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "true")
    RedisInvalidationSubscriber redisInvalidationSubscriber(
            InvalidationChannel channel, ObjectMapper json,
            @Lazy PunishmentService punishments
    ) {
        return new RedisInvalidationSubscriber(channel, new InvalidationCodec(json), punishments, this.origin);
    }

    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "enabled", havingValue = "false", matchIfMissing = true)
    InvalidationPublisher disabledInvalidationPublisher() {
        return new DisabledInvalidationPublisher();
    }

}
