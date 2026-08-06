package net.vanfek.proxypunishments.service.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vanfek.proxypunishments.service.adapter.redis.InvalidationCodec;
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
import net.vanfek.proxypunishments.service.support.FakeInvalidationChannel;
import net.vanfek.proxypunishments.service.support.FakePunishmentRepository;
import net.vanfek.proxypunishments.service.support.FakeUserResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RedisInvalidationTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final IpAddress ADDRESS = IpAddress.parse("203.0.113.7").orElseThrow();
    private static final String ISSUING_NODE = "node-a";
    private static final String RECEIVING_NODE = "node-b";

    private FakeInvalidationChannel channel;
    private SimpleMeterRegistry registry;

    private FakePunishmentRepository issuingStore;
    private PunishmentService issuingNode;

    private FakePunishmentRepository receivingStore;
    private PunishmentService receivingNode;
    private RedisInvalidationSubscriber subscriber;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        this.channel = new FakeInvalidationChannel();
        this.registry = new SimpleMeterRegistry();

        this.issuingStore = new FakePunishmentRepository(clock);
        this.issuingNode = node(this.issuingStore, clock, publisher(ISSUING_NODE));

        this.receivingStore = new FakePunishmentRepository(clock);
        this.receivingNode = node(this.receivingStore, clock, publisher(RECEIVING_NODE));

        this.subscriber = new RedisInvalidationSubscriber(
                this.channel, codec(),
                this.receivingNode, RECEIVING_NODE
        );

        this.subscriber.start();
    }

    @AfterEach
    void tearDown() {
        this.subscriber.close();
    }

    @Test
    @DisplayName("a ban issued on one node drops that entry on the other one, and only that entry")
    void anIssuedBanDropsTheMatchingEntryElsewhere() {
        this.receivingStore.issue(issue(PunishmentKind.BAN)).block();
        this.receivingStore.issue(issue(PunishmentKind.MUTE)).block();

        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block();

        int reads = this.receivingStore.findActiveCalls();

        this.issuingNode.issue(issue(PunishmentKind.BAN)).block();

        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block();

        assertThat(this.receivingStore.findActiveCalls()).isEqualTo(reads);

        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(this.receivingStore.findActiveCalls()).isEqualTo(reads + 1);
    }

    @Test
    @DisplayName("an IP ban issued elsewhere drops the address entry, not the player's own ban")
    void anIssuedIpBanDropsTheAddressEntry() {
        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.receivingNode.activeIpBanFor(ADDRESS).block();

        int reads = this.receivingStore.findActiveCalls();
        int addressReads = this.receivingStore.findActiveByIpCalls();

        this.issuingNode.issue(ipBan()).block();

        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.receivingNode.activeIpBanFor(ADDRESS).block();

        assertThat(this.receivingStore.findActiveCalls()).isEqualTo(reads);
        assertThat(this.receivingStore.findActiveByIpCalls()).isEqualTo(addressReads + 1);
    }

    @Test
    @DisplayName("a node ignores the events it published itself")
    void ownEventsAreIgnored() {
        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.receivingNode.issue(issue(PunishmentKind.BAN)).block();
        this.receivingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(this.registry.counter(MicrometerPunishmentMetrics.INVALIDATIONS_RECEIVED).count()).isEqualTo(0.0D);
    }

    @Test
    @DisplayName("a payload that is not an invalidation is dropped instead of killing the subscription")
    void malformedPayloadsAreIgnored() {
        this.channel.deliver("not json at all");
        this.channel.deliver("{\"target\":\"" + PLAYER + "\",\"kind\":\"NONSENSE\",\"origin\":\"node-a\"}");

        assertThat(this.registry.counter(MicrometerPunishmentMetrics.INVALIDATIONS_RECEIVED).count()).isEqualTo(0.0D);

        this.issuingNode.issue(issue(PunishmentKind.BAN)).block();

        assertThat(this.registry.counter(MicrometerPunishmentMetrics.INVALIDATIONS_RECEIVED).count()).isEqualTo(1.0D);
    }

    @Test
    @DisplayName("Redis refusing the publish does not reach the moderator who issued the ban")
    void aFailingPublishDoesNotPropagate() {
        this.channel.failSendWith(new IllegalStateException("connection refused"));

        ActivePunishment punishment = this.issuingNode.issue(issue(PunishmentKind.BAN)).block();

        assertThat(punishment).isNotNull();
        assertThat(this.channel.published()).isEmpty();
        assertThat(this.issuingNode.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();
    }

    @Test
    @DisplayName("a publisher that throws on the calling thread is swallowed just the same")
    void anEagerlyThrowingPublishIsSwallowed() {
        this.channel.throwOnSend(new IllegalStateException("pool exhausted"));

        assertThatCode(() -> this.issuingNode.issue(issue(PunishmentKind.BAN)).block()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("nothing is counted as published while Redis is refusing writes")
    void failedPublishesAreNotCounted() {
        this.channel.failSendWith(new IllegalStateException("connection refused"));

        this.issuingNode.issue(issue(PunishmentKind.BAN)).block();

        assertThat(this.registry.counter(MicrometerPunishmentMetrics.INVALIDATIONS_PUBLISHED).count()).isEqualTo(0.0D);
    }

    @Test
    void publishedEventsAreCounted() {
        this.issuingNode.issue(issue(PunishmentKind.BAN)).block();
        this.issuingNode.issue(issue(PunishmentKind.MUTE)).block();

        assertThat(this.registry.counter(MicrometerPunishmentMetrics.INVALIDATIONS_PUBLISHED).count()).isEqualTo(2.0D);
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

    private static IssuePunishmentCommand issue(PunishmentKind kind) {
        return new IssuePunishmentCommand(PLAYER, kind, Optional.empty(), ADMIN, "Admin", "griefing", Optional.empty());
    }

    private static IssuePunishmentCommand ipBan() {
        return new IssuePunishmentCommand(
                PLAYER, PunishmentKind.IP_BAN, Optional.of(ADDRESS),
                ADMIN, "Admin",
                "ban evasion", Optional.empty()
        );
    }

}
