package net.vanfek.proxypunishments.service.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vanfek.proxypunishments.service.adapter.redis.InvalidationCodec;
import net.vanfek.proxypunishments.service.adapter.redis.RedisInvalidationChannel;
import net.vanfek.proxypunishments.service.adapter.redis.RedisInvalidationPublisher;
import net.vanfek.proxypunishments.service.adapter.redis.RedisInvalidationSubscriber;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.notify.DisabledPunishmentAnnouncer;
import net.vanfek.proxypunishments.service.domain.service.PunishmentService;
import net.vanfek.proxypunishments.service.metrics.MicrometerPunishmentMetrics;
import net.vanfek.proxypunishments.service.support.FakePunishmentRepository;
import net.vanfek.proxypunishments.service.support.FakeUserResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisInvalidationIT {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CHANNEL = "proxypunishments.invalidations.it";
    private static final int REDIS_PORT = 6379;
    private static final int ATTEMPTS = 100;
    private static final Duration ATTEMPT_DELAY = Duration.ofMillis(100L);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(REDIS_PORT);

    private LettuceConnectionFactory connections;
    private RedisInvalidationChannel channel;
    private SimpleMeterRegistry registry;

    private FakePunishmentRepository issuingStore;
    private PunishmentService issuingNode;

    private FakePunishmentRepository receivingStore;
    private PunishmentService receivingNode;
    private RedisInvalidationSubscriber subscriber;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        this.connections = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(REDIS_PORT))
        );

        this.connections.afterPropertiesSet();

        this.channel = new RedisInvalidationChannel(this.connections, CHANNEL);

        this.registry = new SimpleMeterRegistry();

        this.issuingStore = new FakePunishmentRepository(clock);
        this.issuingNode = node(this.issuingStore, clock, publisher("node-a"));

        this.receivingStore = new FakePunishmentRepository(clock);
        this.receivingNode = node(this.receivingStore, clock, publisher("node-b"));

        this.subscriber = new RedisInvalidationSubscriber(this.channel, codec(), this.receivingNode, "node-b");

        this.subscriber.start();
    }

    @AfterEach
    void tearDown() {
        this.subscriber.close();
        this.channel.close();
        this.connections.destroy();
    }

    @Test
    @DisplayName("a ban issued against one node reaches the other one's cache through a real Redis")
    void invalidationTravelsThroughRedis() throws Exception {
        this.receivingStore.issue(command()).block();
        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        int reads = this.receivingStore.findActiveCalls();

        for (int attempt = 0; attempt < ATTEMPTS && received() == 0.0D; attempt++) {
            this.issuingNode.issue(command()).block();

            Thread.sleep(ATTEMPT_DELAY.toMillis());
        }

        assertThat(received()).isGreaterThan(0.0D);

        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(this.receivingStore.findActiveCalls()).isEqualTo(reads + 1);
    }

    private double received() {
        return this.registry.counter(MicrometerPunishmentMetrics.INVALIDATIONS_RECEIVED).count();
    }

    private PunishmentService node(FakePunishmentRepository store, Clock clock, InvalidationPublisher publisher) {
        AsyncCache<ActivePunishmentKey, Optional<ActivePunishment>> punishmentCache = shortLived();
        AsyncCache<IpAddress, Optional<ActivePunishment>> ipBanCache = shortLived();
        AsyncCache<String, Optional<UserIdentity>> lookupCache = shortLived();

        return new PunishmentService(
                store, new FakeUserResolver(clock),
                punishmentCache, ipBanCache, lookupCache,
                publisher, metrics(),
                new DisabledPunishmentAnnouncer()
        );
    }

    private static <K, V> AsyncCache<K, V> shortLived() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).buildAsync();
    }

    private InvalidationPublisher publisher(String origin) {
        return new RedisInvalidationPublisher(this.channel, codec(), metrics(), origin);
    }

    private PunishmentMetrics metrics() {
        return new MicrometerPunishmentMetrics(this.registry);
    }

    private static InvalidationCodec codec() {
        return new InvalidationCodec(new ObjectMapper());
    }

    private static IssuePunishmentCommand command() {
        return new IssuePunishmentCommand(
                PLAYER, PunishmentKind.BAN, Optional.empty(),
                ADMIN, "Admin",
                "griefing", Optional.empty()
        );
    }

}
