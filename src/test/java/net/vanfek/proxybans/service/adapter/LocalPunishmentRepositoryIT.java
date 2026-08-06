package net.vanfek.proxybans.service.adapter;

import net.vanfek.proxybans.service.domain.model.ActivePunishment;
import net.vanfek.proxybans.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxybans.service.domain.model.IssueWarningCommand;
import net.vanfek.proxybans.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentKind;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.model.UserIdentity;
import net.vanfek.proxybans.service.domain.port.PunishmentRepository;
import net.vanfek.proxybans.service.domain.port.UserResolver;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "proxybans.mode=local",
        "proxybans.security.service-token=integration-test-token"
})
class LocalPunishmentRepositoryIT {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("proxybans")
            .withUsername("proxybans")
            .withPassword("proxybans");

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
                player, PunishmentKind.BAN,
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
                player, PunishmentKind.MUTE,
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

        this.users.remember(new UserIdentity(player, "Steve_" + player.toString().substring(0, 6))).block();
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

        this.users.remember(new UserIdentity(player, login)).block();

        assertThat(this.users.resolveByLogin(login.toUpperCase(java.util.Locale.ROOT)).block()).isNotNull();
    }

    private static IssuePunishmentCommand command(UUID player, PunishmentKind kind, Optional<Duration> duration) {
        return new IssuePunishmentCommand(player, kind, ADMIN, "Admin", "griefing", duration);
    }

    private static LiftPunishmentCommand lift(UUID player, PunishmentKind kind) {
        return new LiftPunishmentCommand(player, kind, ADMIN, "Admin", "appeal accepted");
    }

}
