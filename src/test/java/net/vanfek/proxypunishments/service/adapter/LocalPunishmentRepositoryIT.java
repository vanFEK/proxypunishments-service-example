package net.vanfek.proxypunishments.service.adapter;

import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.IssueWarningCommand;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.Alt;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;
import net.vanfek.proxypunishments.service.domain.port.PunishmentRepository;
import net.vanfek.proxypunishments.service.domain.port.UserResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "proxypunishments.mode=local",
        "proxypunishments.security.service-token=integration-test-token"
})
class LocalPunishmentRepositoryIT {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("proxypunishments")
            .withUsername("proxypunishments")
            .withPassword("proxypunishments");

    @Autowired
    private PunishmentRepository punishments;

    @Autowired
    private UserResolver users;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.url", POSTGRES::getJdbcUrl);
        registry.add("spring.liquibase.user", POSTGRES::getUsername);
        registry.add("spring.liquibase.password", POSTGRES::getPassword);
    }

    @Test
    void storesAndReadsBackAPermanentBan() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();

        ActivePunishment found = this.punishments.findActive(player, PunishmentKind.BAN).block();

        assertThat(found).isNotNull();
        assertThat(found.isPermanent()).isTrue();
        assertThat(found.kind()).isEqualTo(PunishmentKind.BAN);
        assertThat(found.reason()).isEqualTo("griefing");
    }

    @Test
    @DisplayName("issuing a second ban deactivates the first — the @Modifying statement really runs")
    void replacingABanDeactivatesThePreviousOne() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();
        this.punishments.issue(new IssuePunishmentCommand(
                player, PunishmentKind.BAN, Optional.empty(),
                ADMIN, "Admin",
                "second reason", Optional.empty()
        )).block();

        ActivePunishment found = this.punishments.findActive(player, PunishmentKind.BAN).block();

        assertThat(found).isNotNull();
        assertThat(found.reason()).isEqualTo("second reason");
    }

    @Test
    @DisplayName("an expired temporary ban is not returned as active")
    void expiredPunishmentsAreNotActive() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.of(Duration.ofMillis(1)))).block();

        try {
            Thread.sleep(50L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        assertThat(this.punishments.findActive(player, PunishmentKind.BAN).block()).isNull();
    }

    @Test
    void liftingABanRemovesItAndRecordsHistory() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();
        this.punishments.lift(lift(player, PunishmentKind.BAN)).block();

        assertThat(this.punishments.findActive(player, PunishmentKind.BAN).block()).isNull();

        List<PunishmentLogEntry> history = this.punishments.history(player, 10, 0).collectList().block();

        assertThat(history).isNotNull();
        assertThat(history).extracting(PunishmentLogEntry::action)
                .containsExactly(
                        PunishmentLogEntry.PunishmentAction.UNBAN,
                        PunishmentLogEntry.PunishmentAction.PERMBAN
                );
    }

    @Test
    @DisplayName("the unique index is on (player_uuid, kind), so a mute and a ban coexist for one player")
    void aMuteAndABanCoexistForOnePlayer() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();
        this.punishments.issue(command(player, PunishmentKind.MUTE, Optional.empty())).block();

        ActivePunishment ban = this.punishments.findActive(player, PunishmentKind.BAN).block();
        ActivePunishment mute = this.punishments.findActive(player, PunishmentKind.MUTE).block();

        assertThat(ban).isNotNull();
        assertThat(ban.kind()).isEqualTo(PunishmentKind.BAN);
        assertThat(mute).isNotNull();
        assertThat(mute.kind()).isEqualTo(PunishmentKind.MUTE);
        assertThat(mute.id()).isNotEqualTo(ban.id());
    }

    @Test
    @DisplayName("replacing a mute leaves the ban of the same player untouched")
    void replacingAMuteDoesNotTouchTheBan() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();
        this.punishments.issue(command(player, PunishmentKind.MUTE, Optional.empty())).block();
        this.punishments.issue(new IssuePunishmentCommand(
                player, PunishmentKind.MUTE, Optional.empty(),
                ADMIN, "Admin",
                "second mute", Optional.empty()
        )).block();

        ActivePunishment ban = this.punishments.findActive(player, PunishmentKind.BAN).block();
        ActivePunishment mute = this.punishments.findActive(player, PunishmentKind.MUTE).block();

        assertThat(ban).isNotNull();
        assertThat(ban.reason()).isEqualTo("griefing");
        assertThat(mute).isNotNull();
        assertThat(mute.reason()).isEqualTo("second mute");
    }

    @Test
    @DisplayName("lifting a mute records UNMUTE and leaves the ban in force")
    void liftingAMuteKeepsTheBan() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();
        this.punishments.issue(command(player, PunishmentKind.MUTE, Optional.empty())).block();
        this.punishments.lift(lift(player, PunishmentKind.MUTE)).block();

        assertThat(this.punishments.findActive(player, PunishmentKind.MUTE).block()).isNull();
        assertThat(this.punishments.findActive(player, PunishmentKind.BAN).block()).isNotNull();

        List<PunishmentLogEntry> history = this.punishments.history(player, 10, 0).collectList().block();

        assertThat(history).isNotNull();
        assertThat(history).extracting(PunishmentLogEntry::action)
                .contains(PunishmentLogEntry.PunishmentAction.UNMUTE, PunishmentLogEntry.PunishmentAction.PERMMUTE);
    }

    @Test
    @DisplayName("PERMMUTE fits the widened action column and comes back unchanged")
    void storesTheLongestActionName() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.MUTE, Optional.empty())).block();

        List<PunishmentLogEntry> history = this.punishments.history(player, 10, 0).collectList().block();

        assertThat(history).isNotNull();
        assertThat(history).extracting(PunishmentLogEntry::action)
                .containsExactly(PunishmentLogEntry.PunishmentAction.PERMMUTE);
    }

    @Test
    @DisplayName("a warning is written to the log and never becomes an active punishment")
    void warningsAreLogOnly() {
        UUID player = UUID.randomUUID();

        PunishmentLogEntry entry = this.punishments
                .warn(new IssueWarningCommand(player, ADMIN, "Admin", "spam"))
                .block();

        assertThat(entry).isNotNull();
        assertThat(entry.action()).isEqualTo(PunishmentLogEntry.PunishmentAction.WARN);

        assertThat(this.punishments.findActive(player, PunishmentKind.BAN).block()).isNull();
        assertThat(this.punishments.findActive(player, PunishmentKind.MUTE).block()).isNull();

        List<PunishmentLogEntry> history = this.punishments.history(player, 10, 0).collectList().block();

        assertThat(history).isNotNull();
        assertThat(history).extracting(PunishmentLogEntry::action)
                .containsExactly(PunishmentLogEntry.PunishmentAction.WARN);
    }

    @Test
    @DisplayName("the punishment list carries the player's last known name, not just a UUID")
    void listActiveJoinsKnownPlayerNames() {
        UUID player = UUID.randomUUID();

        this.users.remember(new UserIdentity(player, "Steve_" + player.toString().substring(0, 6)), Optional.empty()).block();
        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();

        List<ActivePunishment> active = this.punishments.listActive(PunishmentKind.BAN, 100, 0).collectList().block();

        assertThat(active).isNotNull();
        assertThat(active)
                .filteredOn(punishment -> punishment.playerUuid().equals(player))
                .singleElement()
                .satisfies(punishment -> assertThat(punishment.playerName()).isPresent());
    }

    @Test
    @DisplayName("listing one kind never returns the other")
    void listActiveFiltersByKind() {
        UUID player = UUID.randomUUID();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();
        this.punishments.issue(command(player, PunishmentKind.MUTE, Optional.empty())).block();

        List<ActivePunishment> mutes = this.punishments.listActive(PunishmentKind.MUTE, 100, 0).collectList().block();

        assertThat(mutes).isNotNull();
        assertThat(mutes).allSatisfy(punishment -> assertThat(punishment.kind()).isEqualTo(PunishmentKind.MUTE));
        assertThat(mutes)
                .filteredOn(punishment -> punishment.playerUuid().equals(player))
                .hasSize(1);
    }

    @Test
    void resolvesNamesCaseInsensitively() {
        UUID player = UUID.randomUUID();
        String login = "Notch" + player.toString().substring(0, 6);

        this.users.remember(new UserIdentity(player, login), Optional.empty()).block();

        assertThat(this.users.resolveByLogin(login.toUpperCase(java.util.Locale.ROOT)).block()).isNotNull();
    }

    @Test
    @DisplayName("an IP ban is stored against the address and read back by it")
    void storesAndReadsBackAnIpBan() {
        UUID player = UUID.randomUUID();
        IpAddress address = address();

        this.punishments.issue(ipBan(player, address, "ban evasion")).block();

        ActivePunishment found = this.punishments.findActiveByIp(address).block();

        assertThat(found).isNotNull();
        assertThat(found.kind()).isEqualTo(PunishmentKind.IP_BAN);
        assertThat(found.ipAddress()).contains(address);
        assertThat(found.reason()).isEqualTo("ban evasion");
    }

    @Test
    @DisplayName("the partial unique index is on (ip_address), so re-banning an address replaces the old row")
    void replacingAnIpBanDeactivatesThePreviousOne() {
        UUID player = UUID.randomUUID();
        IpAddress address = address();

        this.punishments.issue(ipBan(player, address, "ban evasion")).block();
        this.punishments.issue(ipBan(UUID.randomUUID(), address, "second reason")).block();

        ActivePunishment found = this.punishments.findActiveByIp(address).block();

        assertThat(found).isNotNull();
        assertThat(found.reason()).isEqualTo("second reason");
    }

    @Test
    @DisplayName("an IP ban and that player's own ban sit in separate rows and neither hides the other")
    void anIpBanAndAPlayerBanCoexist() {
        UUID player = UUID.randomUUID();
        IpAddress address = address();

        this.punishments.issue(command(player, PunishmentKind.BAN, Optional.empty())).block();
        this.punishments.issue(ipBan(player, address, "ban evasion")).block();

        ActivePunishment ban = this.punishments.findActive(player, PunishmentKind.BAN).block();
        ActivePunishment ipBan = this.punishments.findActiveByIp(address).block();

        assertThat(ban).isNotNull();
        assertThat(ban.ipAddress()).isEmpty();
        assertThat(ipBan).isNotNull();
        assertThat(ipBan.id()).isNotEqualTo(ban.id());
    }

    @Test
    @DisplayName("lifting an IP ban records UNIPBAN against the player it was raised through")
    void liftingAnIpBanRemovesItAndRecordsHistory() {
        UUID player = UUID.randomUUID();
        IpAddress address = address();

        this.punishments.issue(ipBan(player, address, "ban evasion")).block();
        this.punishments.liftIpBan(new LiftIpBanCommand(address, ADMIN, "Admin", "appeal accepted")).block();

        assertThat(this.punishments.findActiveByIp(address).block()).isNull();

        List<PunishmentLogEntry> history = this.punishments.history(player, 10, 0).collectList().block();

        assertThat(history).isNotNull();
        assertThat(history).extracting(PunishmentLogEntry::action)
                .contains(PunishmentLogEntry.PunishmentAction.UNIPBAN, PunishmentLogEntry.PunishmentAction.PERMIPBAN);
    }

    @Test
    @DisplayName("the alt query returns the other accounts from one address, newest first, never the player")
    void findsAltsFromTheSharedAddress() {
        UUID player = UUID.randomUUID();
        UUID alt = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        IpAddress shared = address();

        this.users.remember(identity(alt, "Alex"), Optional.of(shared)).block();
        this.users.remember(identity(stranger, "Herobrine"), Optional.of(address())).block();
        this.users.remember(identity(player, "Steve"), Optional.of(shared)).block();

        List<Alt> alts = this.users.findAlts(player, 50).collectList().block();

        assertThat(alts).isNotNull();
        assertThat(alts).extracting(Alt::uuid).containsExactly(alt);
        assertThat(alts).allSatisfy(entry -> assertThat(entry.sharedIp()).isTrue());
    }

    @Test
    @DisplayName("a player whose address was never reported has no alts rather than every address-less row")
    void aPlayerWithoutAnAddressHasNoAlts() {
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        this.users.remember(identity(player, "Steve"), Optional.empty()).block();
        this.users.remember(identity(other, "Alex"), Optional.empty()).block();

        assertThat(this.users.findAlts(player, 50).collectList().block()).isEmpty();
    }

    private static UserIdentity identity(UUID uuid, String prefix) {
        return new UserIdentity(uuid, prefix + "_" + uuid.toString().substring(0, 6));
    }

    // Documentation range 198.18.0.0/15, so a real host is never accidentally described as banned.
    private static IpAddress address() {
        int host = COUNTER.incrementAndGet();

        return IpAddress.parse("198.18." + (host / 256 % 256) + "." + (host % 256)).orElseThrow();
    }

    private static IssuePunishmentCommand ipBan(UUID player, IpAddress address, String reason) {
        return new IssuePunishmentCommand(
                player, PunishmentKind.IP_BAN, Optional.of(address),
                ADMIN, "Admin",
                reason, Optional.empty()
        );
    }

    private static IssuePunishmentCommand command(UUID player, PunishmentKind kind, Optional<Duration> duration) {
        return new IssuePunishmentCommand(player, kind, Optional.empty(), ADMIN, "Admin", "griefing", duration);
    }

    private static LiftPunishmentCommand lift(UUID player, PunishmentKind kind) {
        return new LiftPunishmentCommand(player, kind, ADMIN, "Admin", "appeal accepted");
    }

}
