package net.vanfek.proxybans.service.domain;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.vanfek.proxybans.service.domain.model.ActiveBan;
import net.vanfek.proxybans.service.domain.model.IssueBanCommand;
import net.vanfek.proxybans.service.domain.model.LiftBanCommand;
import net.vanfek.proxybans.service.domain.model.UserIdentity;
import net.vanfek.proxybans.service.domain.service.BanService;
import net.vanfek.proxybans.service.support.FakeBanRepository;
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

class BanServiceTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private FakeBanRepository bans;
    private FakeUserResolver users;
    private BanService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        this.bans = new FakeBanRepository(clock);
        this.users = new FakeUserResolver();
        this.service = new BanService(this.bans, this.users, banCache(), lookupCache());
    }

    @Test
    void issuesAPermanentBanWhenNoDurationIsGiven() {
        ActiveBan ban = this.service.issue(command(Optional.empty())).block();

        assertThat(ban).isNotNull();
        assertThat(ban.isPermanent()).isTrue();
        assertThat(ban.expiresAt()).isEmpty();
    }

    @Test
    void issuesATemporaryBanWithAnExpiry() {
        ActiveBan ban = this.service.issue(command(Optional.of(Duration.ofDays(7)))).block();

        assertThat(ban).isNotNull();
        assertThat(ban.isPermanent()).isFalse();
        assertThat(ban.expiresAt()).contains(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("repeated lookups of the same player hit the store once")
    void cachesActiveBanLookups() {
        this.service.issue(command(Optional.empty())).block();

        this.service.activeBanFor(PLAYER).block();
        this.service.activeBanFor(PLAYER).block();
        this.service.activeBanFor(PLAYER).block();

        assertThat(this.bans.findActiveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("\"not banned\" is cached as well, so clean logins do not hit the store every time")
    void cachesNegativeAnswers() {
        this.service.activeBanFor(PLAYER).block();
        this.service.activeBanFor(PLAYER).block();

        assertThat(this.bans.findActiveCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("issuing a ban invalidates the cached \"not banned\" answer immediately")
    void issueInvalidatesTheCache() {
        assertThat(this.service.activeBanFor(PLAYER).block()).isEmpty();

        this.service.issue(command(Optional.empty())).block();

        assertThat(this.service.activeBanFor(PLAYER).block()).isPresent();
    }

    @Test
    void liftInvalidatesTheCache() {
        this.service.issue(command(Optional.empty())).block();

        assertThat(this.service.activeBanFor(PLAYER).block()).isPresent();

        this.service.lift(new LiftBanCommand(PLAYER, ADMIN, "Admin", "appeal accepted")).block();

        assertThat(this.service.activeBanFor(PLAYER).block()).isEmpty();
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

    private IssueBanCommand command(Optional<Duration> duration) {
        return new IssueBanCommand(PLAYER, ADMIN, "Admin", "griefing", duration);
    }

    private static AsyncCache<UUID, Optional<ActiveBan>> banCache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).buildAsync();
    }

    private static AsyncCache<String, Optional<UserIdentity>> lookupCache() {
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).buildAsync();
    }

}
