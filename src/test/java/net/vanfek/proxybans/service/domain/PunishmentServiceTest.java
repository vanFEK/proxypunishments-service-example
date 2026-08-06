package net.vanfek.proxybans.service.domain;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.vanfek.proxybans.service.domain.model.ActivePunishment;
import net.vanfek.proxybans.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxybans.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxybans.service.domain.model.IssueWarningCommand;
import net.vanfek.proxybans.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentKind;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.model.UserIdentity;
import net.vanfek.proxybans.service.domain.service.PunishmentService;
import net.vanfek.proxybans.service.support.FakePunishmentRepository;
import net.vanfek.proxybans.service.support.FakeUserResolver;
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

class PunishmentServiceTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private FakePunishmentRepository punishments;
    private FakeUserResolver users;
    private PunishmentService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        this.punishments = new FakePunishmentRepository(clock);
        this.users = new FakeUserResolver();
        this.service = new PunishmentService(this.punishments, this.users, punishmentCache(), lookupCache());
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

        this.service.remember(new UserIdentity(PLAYER, "Steve")).block();

        assertThat(this.service.resolveByLogin("Steve").block()).isPresent();
    }

    private IssuePunishmentCommand command(PunishmentKind kind, Optional<Duration> duration) {
        return new IssuePunishmentCommand(PLAYER, kind, ADMIN, "Admin", "griefing", duration);
    }

    private LiftPunishmentCommand lift(PunishmentKind kind) {
        return new LiftPunishmentCommand(PLAYER, kind, ADMIN, "Admin", "appeal accepted");
    }

    private static AsyncCache<ActivePunishmentKey, Optional<ActivePunishment>> punishmentCache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).buildAsync();
    }

    private static AsyncCache<String, Optional<UserIdentity>> lookupCache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).buildAsync();
    }

}
