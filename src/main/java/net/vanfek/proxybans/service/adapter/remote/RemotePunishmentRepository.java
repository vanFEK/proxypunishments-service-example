package net.vanfek.proxybans.service.adapter.remote;

import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.CreatePunishmentRequest;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.HistoryEnvelope;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.HistoryPayload;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.LiftPunishmentRequest;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.Party;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.PunishmentEnvelope;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.PunishmentListEnvelope;
import net.vanfek.proxybans.service.adapter.remote.RemotePayloads.PunishmentPayload;
import net.vanfek.proxybans.service.config.ApplicationProperties;
import net.vanfek.proxybans.service.domain.model.ActivePunishment;
import net.vanfek.proxybans.service.domain.model.BanType;
import net.vanfek.proxybans.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxybans.service.domain.model.IssueWarningCommand;
import net.vanfek.proxybans.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentKind;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.port.PunishmentRepository;
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

@Repository
@ConditionalOnProperty(name = "proxybans.mode", havingValue = "remote")
class RemotePunishmentRepository implements PunishmentRepository {

    private static final int TYPE_TEMPORARY = 0;
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final String UNKNOWN_ACTOR = "server";

    private final WebClient client;
    private final ApplicationProperties.Remote.Paths paths;
    private final Clock clock;

    RemotePunishmentRepository(WebClient remoteWebClient, ApplicationProperties properties, Clock clock) {
        this.client = remoteWebClient;
        this.paths = properties.remote().paths();
        this.clock = clock;
    }

    @Override
    public Mono<ActivePunishment> findActive(UUID playerUuid, PunishmentKind kind) {
        Optional<String> path = pathFor(kind, this.paths.activeBan(), this.paths.activeMute());

        if (path.isEmpty()) {
            return Mono.empty();
        }

        return this.client.get()
                .uri(path.get(), playerUuid)
                .retrieve()
                .bodyToMono(PunishmentEnvelope.class)
                .mapNotNull(PunishmentEnvelope::data)
                .filter(this::isActive)
                .map(payload -> toDomain(payload, kind))
                .onErrorResume(RemotePunishmentRepository::isMissing, ignored -> Mono.empty());
    }

    @Override
    public Mono<ActivePunishment> issue(IssuePunishmentCommand command) {
        Optional<String> path = pathFor(command.kind(), this.paths.issueBan(), this.paths.issueMute());

        if (path.isEmpty()) {
            return Mono.empty();
        }

        Instant now = this.clock.instant();

        CreatePunishmentRequest payload = new CreatePunishmentRequest(
                command.playerUuid().toString(),
                command.issuedByUuid().toString(), command.issuedByName(),
                command.reason(),
                command.duration().map(duration -> now.plus(duration).getEpochSecond()).orElse(null)
        );

        return this.client.post()
                .uri(path.get())
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(PunishmentEnvelope.class)
                .mapNotNull(PunishmentEnvelope::data)
                .map(body -> toDomain(body, command.kind()));
    }

    @Override
    public Mono<Void> lift(LiftPunishmentCommand command) {
        Optional<String> path = pathFor(command.kind(), this.paths.liftBan(), this.paths.liftMute());

        if (path.isEmpty()) {
            return Mono.empty();
        }

        LiftPunishmentRequest payload = new LiftPunishmentRequest(
                command.liftedByUuid().toString(), command.liftedByName(), command.reason()
        );

        return this.client.post()
                .uri(path.get(), command.playerUuid())
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .then();
    }

    @Override
    public Mono<PunishmentLogEntry> warn(IssueWarningCommand command) {
        return Mono.empty();
    }

    @Override
    public Flux<ActivePunishment> listActive(PunishmentKind kind, int limit, int offset) {
        Optional<String> path = pathFor(kind, this.paths.listBans(), this.paths.listMutes());

        if (path.isEmpty()) {
            return Flux.empty();
        }

        return this.client.get()
                .uri(builder -> builder.path(path.get())
                        .queryParam("limit", limit)
                        .queryParam("offset", offset)
                        .build())
                .retrieve()
                .bodyToMono(PunishmentListEnvelope.class)
                .map(envelope -> Optional.ofNullable(envelope.data()).orElseGet(List::of))
                .flatMapMany(Flux::fromIterable)
                .filter(this::isActive)
                .map(payload -> toDomain(payload, kind))
                .onErrorResume(RemotePunishmentRepository::isMissing, ignored -> Flux.empty());
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
                .map(RemotePunishmentRepository::toDomain)
                .onErrorResume(RemotePunishmentRepository::isMissing, ignored -> Flux.empty());
    }

    // An unconfigured path is how a ban-only backend says "no such thing here"; callers then see nothing at all.
    private Optional<String> pathFor(PunishmentKind kind, String banPath, String mutePath) {
        String path = kind == PunishmentKind.MUTE ? mutePath : banPath;

        return path == null || path.isBlank() ? Optional.empty() : Optional.of(path);
    }

    private boolean isActive(PunishmentPayload payload) {
        if (payload == null) {
            return false;
        }

        if (isPermanent(payload)) {
            return true;
        }

        Long end = payload.timeEnd();

        return end == null || end == 0L || Instant.ofEpochSecond(end).isAfter(this.clock.instant());
    }

    private static boolean isPermanent(PunishmentPayload payload) {
        return payload.type() != null && payload.type() != TYPE_TEMPORARY;
    }

    private static ActivePunishment toDomain(PunishmentPayload payload, PunishmentKind kind) {
        boolean permanent = isPermanent(payload);

        Optional<Instant> expiresAt = permanent || payload.timeEnd() == null || payload.timeEnd() == 0L
                ? Optional.empty()
                : Optional.of(Instant.ofEpochSecond(payload.timeEnd()));

        Party banned = payload.banned();
        Party punisher = payload.punisher();

        return new ActivePunishment(
                payload.id() != null ? payload.id() : 0L,
                parseUuidOrNil(banned != null ? banned.uuid() : null), Optional.ofNullable(banned != null ? banned.login() : null),
                kind,
                parseUuidOrNil(punisher != null ? punisher.uuid() : null), loginOrDefault(punisher),
                payload.reason() != null ? payload.reason() : "",
                Instant.ofEpochSecond(payload.timeStart() != null ? payload.timeStart() : 0L), expiresAt,
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
                parseUuidOrNil(admin != null ? admin.uuid() : null), loginOrDefault(admin),
                parseAction(payload.type()), payload.reason() != null ? payload.reason() : "",
                Instant.ofEpochSecond(payload.time() != null ? payload.time() : 0L), expiresAt
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
