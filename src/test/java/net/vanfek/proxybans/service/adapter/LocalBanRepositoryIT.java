package net.vanfek.proxybans.service.adapter;

import net.vanfek.proxybans.service.domain.model.ActiveBan;
import net.vanfek.proxybans.service.domain.model.IssueBanCommand;
import net.vanfek.proxybans.service.domain.model.LiftBanCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.model.UserIdentity;
import net.vanfek.proxybans.service.domain.port.BanRepository;
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
class LocalBanRepositoryIT {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("proxybans")
            .withUsername("proxybans")
            .withPassword("proxybans");

    @Autowired
    private BanRepository bans;

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

        this.bans.issue(command(player, Optional.empty())).block();

        ActiveBan found = this.bans.findActive(player).block();

        assertThat(found).isNotNull();
        assertThat(found.isPermanent()).isTrue();
        assertThat(found.reason()).isEqualTo("griefing");
    }

    @Test
    @DisplayName("issuing a second ban deactivates the first — the @Modifying statement really runs")
    void replacingABanDeactivatesThePreviousOne() {
        UUID player = UUID.randomUUID();

        this.bans.issue(command(player, Optional.empty())).block();
        this.bans.issue(new IssueBanCommand(player, ADMIN, "Admin", "second reason", Optional.empty())).block();

        ActiveBan found = this.bans.findActive(player).block();

        assertThat(found).isNotNull();
        assertThat(found.reason()).isEqualTo("second reason");
    }

    @Test
    @DisplayName("an expired temporary ban is not returned as active")
    void expiredBansAreNotActive() {
        UUID player = UUID.randomUUID();

        this.bans.issue(new IssueBanCommand(player, ADMIN, "Admin", "short", Optional.of(Duration.ofMillis(1))))
                .block();

        try {
            Thread.sleep(50L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        assertThat(this.bans.findActive(player).block()).isNull();
    }

    @Test
    void liftingABanRemovesItAndRecordsHistory() {
        UUID player = UUID.randomUUID();

        this.bans.issue(command(player, Optional.empty())).block();
        this.bans.lift(new LiftBanCommand(player, ADMIN, "Admin", "appeal accepted")).block();

        assertThat(this.bans.findActive(player).block()).isNull();

        List<PunishmentLogEntry> history = this.bans.history(player, 10, 0).collectList().block();

        assertThat(history).isNotNull();
        assertThat(history).extracting(PunishmentLogEntry::action)
                .containsExactly(
                        PunishmentLogEntry.PunishmentAction.UNBAN,
                        PunishmentLogEntry.PunishmentAction.PERMBAN
                );
    }

    @Test
    @DisplayName("the ban list carries the player's last known name, not just a UUID")
    void listActiveJoinsKnownPlayerNames() {
        UUID player = UUID.randomUUID();

        this.users.remember(new UserIdentity(player, "Steve_" + player.toString().substring(0, 6))).block();
        this.bans.issue(command(player, Optional.empty())).block();

        List<ActiveBan> active = this.bans.listActive(100, 0).collectList().block();

        assertThat(active).isNotNull();
        assertThat(active)
                .filteredOn(ban -> ban.playerUuid().equals(player))
                .singleElement()
                .satisfies(ban -> assertThat(ban.playerName()).isPresent());
    }

    @Test
    void resolvesNamesCaseInsensitively() {
        UUID player = UUID.randomUUID();
        String login = "Notch" + player.toString().substring(0, 6);

        this.users.remember(new UserIdentity(player, login)).block();

        assertThat(this.users.resolveByLogin(login.toUpperCase(java.util.Locale.ROOT)).block()).isNotNull();
    }

    private static IssueBanCommand command(UUID player, Optional<Duration> duration) {
        return new IssueBanCommand(player, ADMIN, "Admin", "griefing", duration);
    }

}
