package net.vanfek.proxypunishments.service.support;

import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxypunishments.service.domain.model.BanType;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.IssueWarningCommand;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxypunishments.service.domain.port.PunishmentRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakePunishmentRepository implements PunishmentRepository {

    private final Map<ActivePunishmentKey, ActivePunishment> punishments = new HashMap<>();
    private final List<PunishmentLogEntry> log = new ArrayList<>();
    private final AtomicInteger findActiveCalls = new AtomicInteger();
    private final Clock clock;

    private long nextId = 1L;

    public FakePunishmentRepository(Clock clock) {
        this.clock = clock;
    }

    public int findActiveCalls() {
        return this.findActiveCalls.get();
    }

    @Override
    public Mono<ActivePunishment> findActive(UUID playerUuid, PunishmentKind kind) {
        this.findActiveCalls.incrementAndGet();

        return Mono.justOrEmpty(this.punishments.get(new ActivePunishmentKey(playerUuid, kind)))
                .filter(punishment -> !punishment.isExpired(this.clock.instant()));
    }

    @Override
    public Mono<ActivePunishment> issue(IssuePunishmentCommand command) {
        Instant now = this.clock.instant();
        Optional<Instant> expiresAt = command.duration().map(now::plus);

        ActivePunishment punishment = new ActivePunishment(
                this.nextId++, command.playerUuid(), Optional.empty(),
                command.kind(),
                command.issuedByUuid(), command.issuedByName(),
                command.reason(),
                now, expiresAt,
                BanType.from(command.isPermanent())
        );

        this.punishments.put(command.key(), punishment);
        this.log.add(new PunishmentLogEntry(
                command.playerUuid(), command.issuedByUuid(), command.issuedByName(),
                command.kind().issuedAction(command.isPermanent()), command.reason(),
                now, expiresAt
        ));

        return Mono.just(punishment);
    }

    @Override
    public Mono<Void> lift(LiftPunishmentCommand command) {
        this.punishments.remove(command.key());
        this.log.add(new PunishmentLogEntry(
                command.playerUuid(), command.liftedByUuid(), command.liftedByName(),
                command.kind().liftedAction(), command.reason(),
                this.clock.instant(), Optional.empty()
        ));

        return Mono.empty();
    }

    @Override
    public Mono<PunishmentLogEntry> warn(IssueWarningCommand command) {
        PunishmentLogEntry entry = new PunishmentLogEntry(
                command.playerUuid(), command.issuedByUuid(), command.issuedByName(),
                PunishmentLogEntry.PunishmentAction.WARN, command.reason(),
                this.clock.instant(), Optional.empty()
        );

        this.log.add(entry);

        return Mono.just(entry);
    }

    @Override
    public Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset) {
        return Flux.fromIterable(this.log)
                .filter(entry -> entry.playerUuid().equals(playerUuid))
                .skip(offset)
                .take(limit);
    }

    @Override
    public Flux<ActivePunishment> listActive(PunishmentKind kind, int limit, int offset) {
        return Flux.fromIterable(this.punishments.values())
                .filter(punishment -> punishment.kind() == kind)
                .filter(punishment -> !punishment.isExpired(this.clock.instant()))
                .skip(offset)
                .take(limit);
    }

}
