package net.vanfek.proxypunishments.service.api.v1;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vanfek.proxypunishments.service.domain.invalidation.DisabledInvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxypunishments.service.domain.model.UserIdentity;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.notify.DisabledPunishmentAnnouncer;
import net.vanfek.proxypunishments.service.domain.service.PunishmentService;
import net.vanfek.proxypunishments.service.metrics.MicrometerPunishmentMetrics;
import net.vanfek.proxypunishments.service.support.FakePunishmentRepository;
import net.vanfek.proxypunishments.service.support.FakeUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

class PunishmentsControllerTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private WebTestClient client;
    private FakePunishmentRepository punishments;
    private FakeUserResolver users;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        this.punishments = new FakePunishmentRepository(clock);
        this.users = new FakeUserResolver(clock);

        PunishmentService service = new PunishmentService(
                this.punishments, this.users,
                punishmentCache(), ipBanCache(), lookupCache(),
                new DisabledInvalidationPublisher(), new MicrometerPunishmentMetrics(new SimpleMeterRegistry()),
                new DisabledPunishmentAnnouncer()
        );

        this.client = WebTestClient
                .bindToController(new PunishmentsController(service), new LookupController(service))
                .controllerAdvice(new ApiExceptionHandler(clock))
                .build();
    }

    @Test
    void issuesAndReadsBackAPermanentBan() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "griefing"
                }
                """)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("BAN")
                .jsonPath("$.type").isEqualTo("PERMANENT")
                .jsonPath("$.reason").isEqualTo("griefing");

        this.client.get().uri("/api/v1/punishments/{uuid}/active", PLAYER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.playerUuid").isEqualTo(PLAYER.toString());
    }

    @Test
    void answers404WhenThePlayerIsNotBanned() {
        this.client.get().uri("/api/v1/punishments/{uuid}/active", PLAYER)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("an oversized limit is rejected instead of turning into a full-table read")
    void rejectsAnOversizedLimit() {
        this.client.get().uri("/api/v1/punishments?limit=1000000")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("a negative offset is rejected instead of reaching the database")
    void rejectsANegativeOffset() {
        this.client.get().uri("/api/v1/punishments?offset=-1")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void acceptsPagingInsideTheAllowedRange() {
        this.client.get().uri("/api/v1/punishments?limit=50&offset=0")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data").isArray();
    }

    @Test
    void rejectsAPunishmentRequestWithoutAReason() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": ""
                }
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("reason");
    }

    @Test
    @DisplayName("a negative duration is rejected rather than stored as an already-expired punishment")
    void rejectsANegativeDuration() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "griefing",
                  "durationSeconds": -60
                }
                """)
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("a request without a kind is rejected — the contract makes it mandatory")
    void rejectsAPunishmentRequestWithoutAKind() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "griefing"
                }
                """)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("kind");
    }

    @Test
    void liftingABanReturnsNoContentAndClearsTheBan() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "griefing"
                }
                """).expectStatus().isCreated();

        lift("/api/v1/punishments/" + PLAYER).expectStatus().isNoContent();

        this.client.get().uri("/api/v1/punishments/{uuid}/active", PLAYER)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("a mute and a ban coexist, and each kind is read back on its own")
    void issuesAMuteAlongsideABan() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "griefing"
                }
                """).expectStatus().isCreated();

        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "MUTE",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "chat abuse",
                  "durationSeconds": 3600
                }
                """)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("MUTE")
                .jsonPath("$.type").isEqualTo("TEMPORARY");

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=MUTE", PLAYER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("MUTE")
                .jsonPath("$.reason").isEqualTo("chat abuse");

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=BAN", PLAYER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("BAN")
                .jsonPath("$.reason").isEqualTo("griefing");
    }

    @Test
    @DisplayName("asking for a mute does not hand back the player's ban")
    void muteLookupDoesNotReturnTheBan() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "griefing"
                }
                """).expectStatus().isCreated();

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=MUTE", PLAYER)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("lifting the mute leaves the ban standing")
    void liftingAMuteKeepsTheBan() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "griefing"
                }
                """).expectStatus().isCreated();

        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "MUTE",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "chat abuse"
                }
                """).expectStatus().isCreated();

        lift("/api/v1/punishments/" + PLAYER + "?kind=MUTE").expectStatus().isNoContent();

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=MUTE", PLAYER)
                .exchange()
                .expectStatus().isNotFound();

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=BAN", PLAYER)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the active mute list carries only mutes")
    void listsActiveMutesSeparately() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "MUTE",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "chat abuse"
                }
                """).expectStatus().isCreated();

        this.client.get().uri("/api/v1/punishments?kind=MUTE")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].kind").isEqualTo("MUTE");

        this.client.get().uri("/api/v1/punishments?kind=BAN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("an unknown kind is rejected rather than silently treated as a ban")
    void rejectsAnUnknownKind() {
        this.client.get().uri("/api/v1/punishments?kind=KICK")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("a warning is created in the log and leaves the player unpunished")
    void warningsLandInTheLogWithoutBecomingActive() {
        this.client.post().uri("/api/v1/warnings")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "playerUuid": "11111111-2222-3333-4444-555555555555",
                          "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                          "issuedByName": "Admin",
                          "reason": "spam"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.action").isEqualTo("WARN")
                .jsonPath("$.reason").isEqualTo("spam");

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=BAN", PLAYER)
                .exchange()
                .expectStatus().isNotFound();

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=MUTE", PLAYER)
                .exchange()
                .expectStatus().isNotFound();

        this.client.get().uri("/api/v1/punishments/{uuid}/history", PLAYER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].action").isEqualTo("WARN");
    }

    @Test
    void rejectsAWarningWithoutAReason() {
        this.client.post().uri("/api/v1/warnings")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "playerUuid": "11111111-2222-3333-4444-555555555555",
                          "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                          "issuedByName": "Admin",
                          "reason": ""
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].field").isEqualTo("reason");
    }

    @Test
    void resolvesAKnownPlayerName() {
        this.users.register(new UserIdentity(PLAYER, "Steve"));

        this.client.get().uri("/api/v1/lookup?name=steve")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.uuid").isEqualTo(PLAYER.toString());
    }

    @Test
    void answers404ForAnUnknownName() {
        this.client.get().uri("/api/v1/lookup?name=nobody")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("an IP ban is read back by address and reports the address it covers")
    void issuesAndReadsBackAnIpBan() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "IP_BAN",
                  "ipAddress": "203.0.113.7",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "ban evasion"
                }
                """)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("IP_BAN")
                .jsonPath("$.ipAddress").isEqualTo("203.0.113.7");

        this.client.get().uri("/api/v1/punishments/ip/{address}/active", "203.0.113.7")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ipAddress").isEqualTo("203.0.113.7")
                .jsonPath("$.reason").isEqualTo("ban evasion");
    }

    @Test
    @DisplayName("an IP ban denies nothing to a player who is not banned themselves")
    void anIpBanLeavesThePlayerBanUntouched() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "IP_BAN",
                  "ipAddress": "203.0.113.7",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "ban evasion"
                }
                """).expectStatus().isCreated();

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=BAN", PLAYER)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("an IP ban and a player ban coexist and are read back on their own terms")
    void anIpBanAndAPlayerBanCoexist() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "griefing"
                }
                """).expectStatus().isCreated();

        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "IP_BAN",
                  "ipAddress": "203.0.113.7",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "ban evasion"
                }
                """).expectStatus().isCreated();

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=BAN", PLAYER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reason").isEqualTo("griefing");

        lift("/api/v1/punishments/ip/203.0.113.7").expectStatus().isNoContent();

        this.client.get().uri("/api/v1/punishments/ip/{address}/active", "203.0.113.7")
                .exchange()
                .expectStatus().isNotFound();

        this.client.get().uri("/api/v1/punishments/{uuid}/active?kind=BAN", PLAYER)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("an IP ban without an address is rejected rather than stored against nothing")
    void rejectsAnIpBanWithoutAnAddress() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "IP_BAN",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "ban evasion"
                }
                """).expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("garbage in ipAddress is rejected instead of reaching the database")
    void rejectsAGarbageAddressInTheBody() {
        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "IP_BAN",
                  "ipAddress": "not-an-address",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "ban evasion"
                }
                """).expectStatus().isBadRequest();

        issue("""
                {
                  "playerUuid": "11111111-2222-3333-4444-555555555555",
                  "kind": "IP_BAN",
                  "ipAddress": "999.999.999.999",
                  "issuedByUuid": "99999999-8888-7777-6666-555555555555",
                  "issuedByName": "Admin",
                  "reason": "ban evasion"
                }
                """).expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("a garbage address in the path is rejected too")
    void rejectsAGarbageAddressInThePath() {
        this.client.get().uri("/api/v1/punishments/ip/{address}/active", "definitely-not-an-ip")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("a known player is recorded with their address and shows up as an alt of their neighbour")
    void remembersAnAddressAndListsAlts() {
        remember("11111111-2222-3333-4444-555555555555", "Steve", "203.0.113.7").expectStatus().isNoContent();
        remember("22222222-3333-4444-5555-666666666666", "Alex", "203.0.113.7").expectStatus().isNoContent();

        this.client.get().uri("/api/v1/players/{uuid}/alts", PLAYER)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].login").isEqualTo("Alex")
                .jsonPath("$.data[0].sharedIp").isEqualTo(true);
    }

    @Test
    @DisplayName("a nonsense address on the known-players endpoint is rejected, not stored")
    void rejectsAGarbageAddressWhenRememberingAPlayer() {
        remember("11111111-2222-3333-4444-555555555555", "Steve", "127.0.0.1; DROP TABLE punishments")
                .expectStatus().isBadRequest();

        remember("11111111-2222-3333-4444-555555555555", "Steve", "localhost")
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("the address stays optional: a proxy that does not report one is still accepted")
    void remembersAPlayerWithoutAnAddress() {
        this.client.post().uri("/api/v1/known-players")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "uuid": "11111111-2222-3333-4444-555555555555",
                          "login": "Steve"
                        }
                        """)
                .exchange()
                .expectStatus().isNoContent();

        this.client.get().uri("/api/v1/lookup?name=steve")
                .exchange()
                .expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec issue(String body) {
        return this.client.post().uri("/api/v1/punishments")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.ResponseSpec remember(String uuid, String login, String ip) {
        return this.client.post().uri("/api/v1/known-players")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "uuid": "%s",
                          "login": "%s",
                          "ip": "%s"
                        }
                        """.formatted(uuid, login, ip))
                .exchange();
    }

    private WebTestClient.ResponseSpec lift(String uri) {
        return this.client.method(HttpMethod.DELETE)
                .uri(uri)
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {
                          "liftedByUuid": "99999999-8888-7777-6666-555555555555",
                          "liftedByName": "Admin",
                          "reason": "appeal accepted"
                        }
                        """)
                .exchange();
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

    @SuppressWarnings("unused")
    private UUID adminUuid() {
        return ADMIN;
    }

}
