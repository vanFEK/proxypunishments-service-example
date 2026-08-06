package net.vanfek.proxypunishments.service.adapter.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vanfek.proxypunishments.service.config.ApplicationProperties;
import net.vanfek.proxypunishments.service.domain.invalidation.DisabledInvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationEvent;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.service.PunishmentService;
import net.vanfek.proxypunishments.service.metrics.MicrometerPunishmentMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class RedisInvalidationConfigTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final int UNREACHABLE_PORT = 6399;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RedisInvalidationConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(PunishmentMetrics.class, () -> new MicrometerPunishmentMetrics(new SimpleMeterRegistry()))
            .withBean(PunishmentService.class, () -> mock(PunishmentService.class))
            .withBean(ApplicationProperties.class, RedisInvalidationConfigTest::properties);

    @Test
    @DisplayName("with Redis off the only publisher is the one that does nothing")
    void disabledWiresTheNoOpPublisher() {
        this.runner.withPropertyValues("proxypunishments.redis.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(InvalidationPublisher.class);
            assertThat(context.getBean(InvalidationPublisher.class)).isInstanceOf(DisabledInvalidationPublisher.class);
            assertThat(context).doesNotHaveBean(RedisInvalidationSubscriber.class);
            assertThat(context).doesNotHaveBean(RedisInvalidationChannel.class);
        });
    }

    @Test
    @DisplayName("an unset property is the same as off, so an untouched deployment gains nothing to fail on")
    void missingPropertyWiresTheNoOpPublisher() {
        this.runner.run(context -> {
            assertThat(context.getBean(InvalidationPublisher.class)).isInstanceOf(DisabledInvalidationPublisher.class);
            assertThat(context).doesNotHaveBean(RedisInvalidationSubscriber.class);
        });
    }

    @Test
    @DisplayName("with Redis on but unreachable the context still starts — the subscriber retries in the background")
    void enabledStartsEvenWhenRedisIsDown() {
        this.runner.withPropertyValues("proxypunishments.redis.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InvalidationPublisher.class)).isInstanceOf(RedisInvalidationPublisher.class);
            assertThat(context).hasSingleBean(RedisInvalidationSubscriber.class);
        });
    }

    @Test
    @DisplayName("publishing against a Redis that is not there is swallowed instead of thrown")
    void publishingWithoutRedisIsSwallowed() {
        this.runner.withPropertyValues("proxypunishments.redis.enabled=true").run(context -> {
            InvalidationPublisher publisher = context.getBean(InvalidationPublisher.class);

            assertThatCode(() -> publisher.publish(InvalidationEvent.forPlayer(PLAYER, PunishmentKind.BAN)))
                    .doesNotThrowAnyException();
        });
    }

    private static ApplicationProperties properties() {
        return new ApplicationProperties(
                ApplicationProperties.Mode.LOCAL, new ApplicationProperties.Security("token"),
                new ApplicationProperties.Cache(Duration.ofSeconds(30), Duration.ofMinutes(10), 1000L), null,
                redis(), null
        );
    }

    private static ApplicationProperties.Redis redis() {
        return new ApplicationProperties.Redis(
                true, "127.0.0.1", UNREACHABLE_PORT,
                "", "", 0,
                "proxypunishments.invalidations", Duration.ofMillis(200L)
        );
    }

}
