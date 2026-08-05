package net.vanfek.proxybans.service.adapter.remote;

import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.BanEnvelope;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.BanListEnvelope;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.BanPayload;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.CreateBanRequest;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.HistoryEnvelope;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.HistoryPayload;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.LiftBanRequest;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.Party;
import net.vanfek.proxybans.service.config.ApplicationProperties;
import net.vanfek.proxybans.service.domain.model.ActiveBan;
import net.vanfek.proxybans.service.domain.model.BanType;
import net.vanfek.proxybans.service.domain.model.IssueBanCommand;
import net.vanfek.proxybans.service.domain.model.LiftBanCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.port.BanRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes bans through an external HTTP backend.
 *
 * <p>This is the second implementation that justifies the port: the proxy plugin cannot tell whether
 * a ban came from the local Postgres schema or from a website's own database, and switching between
 * them is one environment variable.
 *
 * <p>Endpoint paths are configuration, not constants, so a new backend usually needs a YAML change
 * rather than a new adapter.
 */
@Repository
@ConditionalOnProperty(name = "proxybans.mode", havingValue = "remote")
class RemoteBanRepository implements BanRepository {

    private static final int TYPE_TEMPORARY = 0;
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final String UNKNOWN_ACTOR = "server";

    private final WebClient client;
    private final ApplicationProperties.Remote.Paths paths;
    private final Clock clock;

    RemoteBanRepository(WebClient remoteWebClient, ApplicationProperties properties, Clock clock) {
        this.client = remoteWebClient;
        this.paths = properties.remote().paths();
        this.clock = clock;
    }

    @Override
    public Mono<ActiveBan> findActive(UUID playerUuid) {
        return this.client.get()
                .uri(this.paths.activeBan(), playerUuid)
                .retrieve()
                .bodyToMono(BanEnvelope.class)
                .mapNotNull(BanEnvelope::data)
                .filter(this::isActive)
                .map(RemoteBanRepository::toDomain)
                .onErrorResume(RemoteBanRepository::isMissing, ignored -> Mono.empty());
    }

    @Override
    public Mono<ActiveBan> issue(IssueBanCommand command) {
        Instant now = this.clock.instant();

        CreateBanRequest payload = new CreateBanRequest(
                command.playerUuid().toString(),
                command.issuedByUuid().toString(),
                command.issuedByName(),
                command.reason(),
                command.duration().map(duration -> now.plus(duration).getEpochSecond()).orElse(null)
        );

        return this.client.post()
                .uri(this.paths.issueBan())
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(BanEnvelope.class)
                .mapNotNull(BanEnvelope::data)
                .map(RemoteBanRepository::toDomain);
    }

    @Override
    public Mono<Void> lift(LiftBanCommand command) {
        LiftBanRequest payload = new LiftBanRequest(
                command.liftedByUuid().toString(),
                command.liftedByName(),
                command.reason()
        );

        return this.client.post()
                .uri(this.paths.liftBan(), command.playerUuid())
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    @Override
    public Flux<ActiveBan> listActive(int limit, int offset) {
        return this.client.get()
                .uri(builder -> builder.path(this.paths.listBans())
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .build())
                .retrieve()
                .bodyToMono(BanListEnvelope.class)
                .map(envelope -> Optional.ofNullable(envelope.data()).orElseGet(List::of))
                .flatMapMany(Flux::fromIterable)
                .filter(this::isActive)
                .map(RemoteBanRepository::toDomain)
                .onErrorResume(RemoteBanRepository::isMissing, ignored -> Flux.empty());
    }

    @Override
    public Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset) {
        return this.client.get()
                .uri(builder -> builder.path(this.paths.history())
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .build(playerUuid))
                .retrieve()
                .bodyToMono(HistoryEnvelope.class)
                .map(envelope -> Optional.ofNullable(envelope.data()).orElseGet(List::of))
                .flatMapMany(Flux::fromIterable)
                .map(RemoteBanRepository::toDomain)
                .onErrorResume(RemoteBanRepository::isMissing, ignored -> Flux.empty());
    }

    private boolean isActive(BanPayload payload) {
        if (payload == null) {
            return false;
        }

        if (isPermanent(payload)) {
            return true;
        }

        Long end = payload.timeEnd();

        return end == null || end == 0L || Instant.ofEpochSecond(end).isAfter(this.clock.instant());
    }

    private static boolean isPermanent(BanPayload payload) {
        return payload.type() != null && payload.type() != TYPE_TEMPORARY;
    }

    private static ActiveBan toDomain(BanPayload payload) {
        boolean permanent = isPermanent(payload);

        Optional<Instant> expiresAt = permanent || payload.timeEnd() == null || payload.timeEnd() == 0L
                ? Optional.empty()
                : Optional.of(Instant.ofEpochSecond(payload.timeEnd()));

        Party banned = payload.banned();
        Party punisher = payload.punisher();

        return new ActiveBan(
                payload.id() != null ? payload.id() : 0L,
                parseUuidOrNil(banned != null ? banned.uuid() : null),
                Optional.ofNullable(banned != null ? banned.login() : null),
                parseUuidOrNil(punisher != null ? punisher.uuid() : null),
                loginOrDefault(punisher),
                payload.reason() != null ? payload.reason() : "",
                Instant.ofEpochSecond(payload.timeStart() != null ? payload.timeStart() : 0L),
                expiresAt,
                BanType.from(permanent)
        );
    }

    private static PunishmentLogEntry toDomain(HistoryPayload payload) {
        Party admin = payload.admin();
        Party banned = payload.banned();

        Optional<Instant> expiresAt = payload.tempTime() != null && payload.tempTime() > 0L
                ? Optional.of(Instant.ofEpochSecond(payload.tempTime()))
                : Optional.empty();

        return new PunishmentLogEntry(
                parseUuidOrNil(banned != null ? banned.uuid() : null),
                parseUuidOrNil(admin != null ? admin.uuid() : null),
                loginOrDefault(admin),
                parseAction(payload.type()),
                payload.reason() != null ? payload.reason() : "",
                Instant.ofEpochSecond(payload.time() != null ? payload.time() : 0L),
                expiresAt
        );
    }

    private static String loginOrDefault(Party party) {
        return party != null && party.login() != null ? party.login() : UNKNOWN_ACTOR;
    }

    private static UUID parseUuidOrNil(String value) {
        if (value == null) {
            return NIL_UUID;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return NIL_UUID;
        }
    }

    private static PunishmentLogEntry.PunishmentAction parseAction(String raw) {
        if (raw == null) {
            return PunishmentLogEntry.PunishmentAction.BAN;
        }

        try {
            return PunishmentLogEntry.PunishmentAction.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return PunishmentLogEntry.PunishmentAction.BAN;
        }
    }

    private static boolean isMissing(Throwable throwable) {
        return throwable instanceof WebClientResponseException exception
                && exception.getStatusCode() == HttpStatus.NOT_FOUND;
    }

}
