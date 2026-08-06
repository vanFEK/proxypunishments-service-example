package net.vanfek.proxypunishments.service.domain;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vanfek.proxypunishments.service.domain.invalidation.DisabledInvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationEvent;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.IssueWarningCommand;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.Alt;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;
import net.vanfek.proxypunishments.service.domain.notify.DisabledPunishmentAnnouncer;
import net.vanfek.proxypunishments.service.domain.service.PunishmentService;
import net.vanfek.proxypunishments.service.metrics.MicrometerPunishmentMetrics;
import net.vanfek.proxypunishments.service.support.FakePunishmentRepository;
import net.vanfek.proxypunishments.service.support.FakeUserResolver;
import net.vanfek.proxypunishments.service.support.RecordingInvalidationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PunishmentServiceTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ALT = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID STRANGER = UUID.fromString("33333333-4444-5555-6666-777777777777");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final IpAddress ADDRESS = IpAddress.parse("203.0.113.7").orElseThrow();
    private static final IpAddress OTHER_ADDRESS = IpAddress.parse("198.51.100.9").orElseThrow();

    private FakePunishmentRepository punishments;
    private FakeUserResolver users;
    private RecordingInvalidationPublisher invalidations;
    private SimpleMeterRegistry registry;
    private PunishmentService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        this.punishments = new FakePunishmentRepository(clock);
        this.users = new FakeUserResolver(clock);
        this.invalidations = new RecordingInvalidationPublisher();
        this.registry = new SimpleMeterRegistry();
        this.service = new PunishmentService(
                this.punishments, this.users,
                punishmentCache(), ipBanCache(), lookupCache(),
                this.invalidations, new MicrometerPunishmentMetrics(this.registry),
                new DisabledPunishmentAnnouncer()
        );
    }

    @Test
    void issuesAPermanentBanWhenNoDurationIsGiven() {
        ActivePunishment ban = this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();

        assertThat(ban).isNotNull();
        assertThat(ban.isPermanent()).isTrue();
        assertThat(ban.expiresAt()).isEmpty();
    }

    @Test
    void issuesATemporaryBanWithAnExpiry() {
        ActivePunishment ban = this.service.issue(command(PunishmentKind.BAN, Optional.of(Duration.ofDays(7)))).block();

        assertThat(ban).isNotNull();
        assertThat(ban.isPermanent()).isFalse();
        assertThat(ban.expiresAt()).contains(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("repeated lookups of the same player hit the store once")
    void cachesActivePunishmentLookups() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();

        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("\"not punished\" is cached as well, so clean logins do not hit the store every time")
    void cachesNegativeAnswers() {
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("issuing a ban invalidates the cached \"not banned\" answer immediately")
    void issueInvalidatesTheCache() {
        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isEmpty();

        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();

        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();
    }

    @Test
    void liftInvalidatesTheCache() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();

        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();

        this.service.lift(lift(PunishmentKind.BAN)).block();

        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isEmpty();
    }

    @Test
    @DisplayName("a ban and a mute live side by side for the same player")
    void banAndMuteCoexistForOnePlayer() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.issue(command(PunishmentKind.MUTE, Optional.of(Duration.ofHours(1)))).block();

        Optional<ActivePunishment> ban = this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        Optional<ActivePunishment> mute = this.service.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block();

        assertThat(ban).isPresent();
        assertThat(ban.orElseThrow().kind()).isEqualTo(PunishmentKind.BAN);
        assertThat(mute).isPresent();
        assertThat(mute.orElseThrow().kind()).isEqualTo(PunishmentKind.MUTE);
        assertThat(mute.orElseThrow().expiresAt()).contains(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("a mute lookup does not answer with the player's ban")
    void muteLookupDoesNotReturnTheBan() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();

        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block()).isEmpty();
        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();
    }

    @Test
    @DisplayName("the cache is keyed by kind, so a mute lookup neither answers nor evicts the ban entry")
    void cacheKeepsBanAndMuteApart() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();

        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(2);

        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("issuing a mute leaves the cached ban answer alone")
    void issuingAMuteDoesNotInvalidateTheBanEntry() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();

        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();

        this.service.issue(command(PunishmentKind.MUTE, Optional.empty())).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("lifting a mute leaves the ban in force")
    void liftingAMuteKeepsTheBan() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.issue(command(PunishmentKind.MUTE, Optional.empty())).block();

        this.service.lift(lift(PunishmentKind.MUTE)).block();

        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block()).isEmpty();
        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();
    }

    @Test
    @DisplayName("a warning lands in the log and never becomes an active punishment")
    void warningsAreLogOnly() {
        PunishmentLogEntry entry = this.service
                .warn(new IssueWarningCommand(PLAYER, ADMIN, "Admin", "spam"))
                .block();

        assertThat(entry).isNotNull();
        assertThat(entry.action()).isEqualTo(PunishmentLogEntry.PunishmentAction.WARN);
        assertThat(entry.expiresAt()).isEmpty();

        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isEmpty();
        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block()).isEmpty();

        assertThat(this.service.history(PLAYER, 10, 0).collectList().block())
                .extracting(PunishmentLogEntry::action)
                .containsExactly(PunishmentLogEntry.PunishmentAction.WARN);
    }

    @Test
    @DisplayName("the punishment log records the kind of what was issued and lifted")
    void logRecordsMuteActions() {
        this.service.issue(command(PunishmentKind.MUTE, Optional.empty())).block();
        this.service.issue(command(PunishmentKind.MUTE, Optional.of(Duration.ofMinutes(10)))).block();
        this.service.lift(lift(PunishmentKind.MUTE)).block();

        assertThat(this.service.history(PLAYER, 10, 0).collectList().block())
                .extracting(PunishmentLogEntry::action)
                .containsExactly(
                        PunishmentLogEntry.PunishmentAction.PERMMUTE,
                        PunishmentLogEntry.PunishmentAction.MUTE,
                        PunishmentLogEntry.PunishmentAction.UNMUTE
                );
    }

    @Test
    @DisplayName("listing one kind never leaks the other")
    void listActiveFiltersByKind() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.issue(command(PunishmentKind.MUTE, Optional.empty())).block();

        assertThat(this.service.listActive(PunishmentKind.MUTE, 50, 0).collectList().block())
                .extracting(ActivePunishment::kind)
                .containsExactly(PunishmentKind.MUTE);
    }

    @Test
    @DisplayName("name lookups are case insensitive and locale independent")
    void resolvesNamesRegardlessOfCase() {
        this.users.register(new UserIdentity(PLAYER, "Individual"));

        Optional<UserIdentity> lower = this.service.resolveByLogin("individual").block();
        Optional<UserIdentity> upper = this.service.resolveByLogin("INDIVIDUAL").block();

        assertThat(lower).isPresent();
        assertThat(upper).isPresent();
        assertThat(lower.orElseThrow().uuid()).isEqualTo(PLAYER);
    }

    @Test
    void rememberingAPlayerInvalidatesTheLookupCache() {
        assertThat(this.service.resolveByLogin("Steve").block()).isEmpty();

        this.service.remember(new UserIdentity(PLAYER, "Steve"), Optional.empty()).block();

        assertThat(this.service.resolveByLogin("Steve").block()).isPresent();
    }

    @Test
    @DisplayName("an IP ban is in force for the address while the player behind it stays unbanned")
    void anIpBanIsIndependentOfThePlayerBan() {
        this.service.issue(ipBan(Optional.empty())).block();

        assertThat(this.service.activeIpBanFor(ADDRESS).block()).isPresent();
        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isEmpty();
    }

    @Test
    @DisplayName("an IP ban and a player ban coexist and are lifted independently")
    void ipBanAndPlayerBanCoexist() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.issue(ipBan(Optional.of(Duration.ofDays(1)))).block();

        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();
        assertThat(this.service.activeIpBanFor(ADDRESS).block()).isPresent();

        this.service.liftIpBan(new LiftIpBanCommand(ADDRESS, ADMIN, "Admin", "appeal accepted")).block();

        assertThat(this.service.activeIpBanFor(ADDRESS).block()).isEmpty();
        assertThat(this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();
    }

    @Test
    @DisplayName("the cache keeps addresses and players apart: neither lookup answers or evicts the other")
    void cacheKeepsAddressesAndPlayersApart() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.issue(ipBan(Optional.empty())).block();

        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activeIpBanFor(ADDRESS).block();
        this.service.activeIpBanFor(OTHER_ADDRESS).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(1);
        assertThat(this.punishments.findActiveByIpCalls()).isEqualTo(2);

        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activeIpBanFor(ADDRESS).block();
        this.service.activeIpBanFor(OTHER_ADDRESS).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(1);
        assertThat(this.punishments.findActiveByIpCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unbanned address is not answered from the banned one's cache entry")
    void anIpBanDoesNotLeakToAnotherAddress() {
        this.service.issue(ipBan(Optional.empty())).block();

        assertThat(this.service.activeIpBanFor(OTHER_ADDRESS).block()).isEmpty();
        assertThat(this.service.activeIpBanFor(ADDRESS).block()).isPresent();
    }

    @Test
    @DisplayName("the punishment log records IPBAN and UNIPBAN for address punishments")
    void logRecordsIpBanActions() {
        this.service.issue(ipBan(Optional.empty())).block();
        this.service.liftIpBan(new LiftIpBanCommand(ADDRESS, ADMIN, "Admin", "appeal accepted")).block();

        assertThat(this.service.history(PLAYER, 10, 0).collectList().block())
                .extracting(PunishmentLogEntry::action)
                .containsExactly(
                        PunishmentLogEntry.PunishmentAction.PERMIPBAN,
                        PunishmentLogEntry.PunishmentAction.UNIPBAN
                );
    }

    @Test
    @DisplayName("alts are the other accounts from the same address, most recent first")
    void altsExcludeThePlayerAndComeBackNewestFirst() {
        this.users.seen(new UserIdentity(PLAYER, "Steve"), ADDRESS, NOW);
        this.users.seen(new UserIdentity(ALT, "Alex"), ADDRESS, NOW.minus(Duration.ofHours(2)));
        this.users.seen(new UserIdentity(STRANGER, "Herobrine"), ADDRESS, NOW.minus(Duration.ofMinutes(5)));

        List<Alt> alts = this.service.alts(PLAYER, 50).collectList().block();

        assertThat(alts).isNotNull();
        assertThat(alts).extracting(Alt::login).containsExactly("Herobrine", "Alex");
        assertThat(alts).allSatisfy(alt -> assertThat(alt.sharedIp()).isTrue());
    }

    @Test
    @DisplayName("an account seen from a different address is not an alt")
    void altsIgnoreOtherAddresses() {
        this.users.seen(new UserIdentity(PLAYER, "Steve"), ADDRESS, NOW);
        this.users.seen(new UserIdentity(STRANGER, "Herobrine"), OTHER_ADDRESS, NOW);

        assertThat(this.service.alts(PLAYER, 50).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("remembering a player carries the address, so alts can be found afterwards")
    void rememberRecordsTheAddress() {
        this.service.remember(new UserIdentity(PLAYER, "Steve"), Optional.of(ADDRESS)).block();
        this.service.remember(new UserIdentity(ALT, "Alex"), Optional.of(ADDRESS)).block();

        assertThat(this.service.alts(PLAYER, 50).collectList().block())
                .extracting(Alt::uuid)
                .containsExactly(ALT);
    }

    @Test
    @DisplayName("issuing announces the player and the kind, so other nodes know exactly what to drop")
    void issuePublishesAnInvalidation() {
        this.service.issue(command(PunishmentKind.MUTE, Optional.empty())).block();

        assertThat(this.invalidations.events())
                .containsExactly(new InvalidationEvent(PLAYER.toString(), PunishmentKind.MUTE));
    }

    @Test
    @DisplayName("an IP ban announces the address rather than the player it was raised through")
    void issuingAnIpBanPublishesTheAddress() {
        this.service.issue(ipBan(Optional.empty())).block();

        assertThat(this.invalidations.events())
                .containsExactly(new InvalidationEvent(ADDRESS.asText(), PunishmentKind.IP_BAN));
    }

    @Test
    void liftingPublishesAnInvalidation() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.lift(lift(PunishmentKind.BAN)).block();

        assertThat(this.invalidations.events()).last()
                .isEqualTo(new InvalidationEvent(PLAYER.toString(), PunishmentKind.BAN));
    }

    @Test
    @DisplayName("a warning is not a cache entry anywhere, so it announces nothing")
    void warningsPublishNothing() {
        this.service.warn(new IssueWarningCommand(PLAYER, ADMIN, "Admin", "spam")).block();

        assertThat(this.invalidations.events()).isEmpty();
    }

    @Test
    @DisplayName("an event from another node drops that entry and leaves every other one alone")
    void appliedInvalidationDropsOnlyTheMatchingEntry() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.issue(command(PunishmentKind.MUTE, Optional.empty())).block();
        this.service.issue(ipBan(Optional.empty())).block();

        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block();
        this.service.activeIpBanFor(ADDRESS).block();

        int reads = this.punishments.findActiveCalls();
        int addressReads = this.punishments.findActiveByIpCalls();

        this.service.applyInvalidation(new InvalidationEvent(PLAYER.toString(), PunishmentKind.BAN));

        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.MUTE).block();
        this.service.activeIpBanFor(ADDRESS).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(reads + 1);
        assertThat(this.punishments.findActiveByIpCalls()).isEqualTo(addressReads);
    }

    @Test
    @DisplayName("an address event drops the address entry and no player entry")
    void appliedAddressInvalidationDropsOnlyTheAddress() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activeIpBanFor(ADDRESS).block();

        int reads = this.punishments.findActiveCalls();
        int addressReads = this.punishments.findActiveByIpCalls();

        this.service.applyInvalidation(new InvalidationEvent(ADDRESS.asText(), PunishmentKind.IP_BAN));

        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activeIpBanFor(ADDRESS).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(reads);
        assertThat(this.punishments.findActiveByIpCalls()).isEqualTo(addressReads + 1);
    }

    @Test
    @DisplayName("a target that is neither a UUID nor an address is ignored instead of clearing the cache")
    void appliedInvalidationIgnoresGarbageTargets() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        int reads = this.punishments.findActiveCalls();

        this.service.applyInvalidation(new InvalidationEvent("definitely-not-a-uuid", PunishmentKind.BAN));
        this.service.applyInvalidation(new InvalidationEvent("definitely-not-an-address", PunishmentKind.IP_BAN));
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(this.punishments.findActiveCalls()).isEqualTo(reads);
    }

    @Test
    @DisplayName("with Redis off nothing is published and every answer is exactly what it was before")
    void redisDisabledChangesNothing() {
        PunishmentService offline = new PunishmentService(
                this.punishments, this.users,
                punishmentCache(), ipBanCache(), lookupCache(),
                new DisabledInvalidationPublisher(), new MicrometerPunishmentMetrics(new SimpleMeterRegistry()),
                new DisabledPunishmentAnnouncer()
        );

        assertThat(offline.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isEmpty();

        offline.issue(command(PunishmentKind.BAN, Optional.empty())).block();

        assertThat(offline.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isPresent();

        offline.lift(lift(PunishmentKind.BAN)).block();

        assertThat(offline.activePunishmentFor(PLAYER, PunishmentKind.BAN).block()).isEmpty();
        assertThat(this.invalidations.events()).isEmpty();
    }

    @Test
    @DisplayName("issued and lifted counters move per kind")
    void countsIssuedAndLiftedByKind() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.issue(command(PunishmentKind.MUTE, Optional.empty())).block();
        this.service.lift(lift(PunishmentKind.MUTE)).block();

        assertThat(counter(MicrometerPunishmentMetrics.PUNISHMENTS_ISSUED, "kind", "BAN")).isEqualTo(1.0D);
        assertThat(counter(MicrometerPunishmentMetrics.PUNISHMENTS_ISSUED, "kind", "MUTE")).isEqualTo(1.0D);
        assertThat(counter(MicrometerPunishmentMetrics.PUNISHMENTS_LIFTED, "kind", "MUTE")).isEqualTo(1.0D);
        assertThat(counter(MicrometerPunishmentMetrics.PUNISHMENTS_LIFTED, "kind", "BAN")).isEqualTo(0.0D);
    }

    @Test
    @DisplayName("the first lookup counts as a miss and the repeat as a hit")
    void countsCacheHitsAndMisses() {
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(counter(MicrometerPunishmentMetrics.CACHE_LOOKUPS, "result", "miss")).isEqualTo(1.0D);
        assertThat(counter(MicrometerPunishmentMetrics.CACHE_LOOKUPS, "result", "hit")).isEqualTo(2.0D);
    }

    @Test
    @DisplayName("repository calls are timed under the operation that made them")
    void timesRepositoryCalls() {
        this.service.issue(command(PunishmentKind.BAN, Optional.empty())).block();
        this.service.activePunishmentFor(PLAYER, PunishmentKind.BAN).block();

        assertThat(this.registry.find(MicrometerPunishmentMetrics.REPOSITORY_CALLS)
                .tag("operation", "issue")
                .timer()
                .count()).isEqualTo(1L);

        assertThat(this.registry.find(MicrometerPunishmentMetrics.REPOSITORY_CALLS)
                .tag("operation", "findActive")
                .timer()
                .count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an event received from another node counts, so a silent Redis is visible in the metrics")
    void countsReceivedInvalidations() {
        this.service.applyInvalidation(new InvalidationEvent(PLAYER.toString(), PunishmentKind.BAN));

        assertThat(this.registry.counter(MicrometerPunishmentMetrics.INVALIDATIONS_RECEIVED).count()).isEqualTo(1.0D);
    }

    private double counter(String name, String tag, String value) {
        return this.registry.find(name)
                .tag(tag, value)
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private IssuePunishmentCommand command(PunishmentKind kind, Optional<Duration> duration) {
        return new IssuePunishmentCommand(PLAYER, kind, Optional.empty(), ADMIN, "Admin", "griefing", duration);
    }

    private IssuePunishmentCommand ipBan(Optional<Duration> duration) {
        return new IssuePunishmentCommand(
                PLAYER, PunishmentKind.IP_BAN, Optional.of(ADDRESS),
                ADMIN, "Admin",
                "ban evasion", duration
        );
    }

    private LiftPunishmentCommand lift(PunishmentKind kind) {
        return new LiftPunishmentCommand(PLAYER, kind, ADMIN, "Admin", "appeal accepted");
    }

    private static AsyncCache<ActivePunishmentKey, Optional<ActivePunishment>> punishmentCache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).buildAsync();
    }

    private static AsyncCache<IpAddress, Optional<ActivePunishment>> ipBanCache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).buildAsync();
    }

    private static AsyncCache<String, Optional<UserIdentity>> lookupCache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).buildAsync();
    }

}
