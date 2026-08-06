package net.vanfek.proxypunishments.service.support;

import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.ActivePunishmentKey;
import net.vanfek.proxypunishments.service.domain.model.BanType;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.IssueWarningCommand;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;
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

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final Map<ActivePunishmentKey, ActivePunishment> punishments = new HashMap<>();
    private final Map<IpAddress, ActivePunishment> ipBans = new HashMap<>();
    private final List<PunishmentLogEntry> log = new ArrayList<>();
    private final AtomicInteger findActiveCalls = new AtomicInteger();
    private final AtomicInteger findActiveByIpCalls = new AtomicInteger();
    private final Clock clock;

    private long nextId = 1L;

    public FakePunishmentRepository(Clock clock) {
        this.clock = clock;
    }

    public int findActiveCalls() {
        return this.findActiveCalls.get();
    }

    public int findActiveByIpCalls() {
        return this.findActiveByIpCalls.get();
    }

    @Override
    public Mono<ActivePunishment> findActive(UUID playerUuid, PunishmentKind kind) {
        this.findActiveCalls.incrementAndGet();

        return Mono.justOrEmpty(this.punishments.get(new ActivePunishmentKey(playerUuid, kind)))
                .filter(punishment -> !punishment.isExpired(this.clock.instant()));
    }

    @Override
    public Mono<ActivePunishment> findActiveByIp(IpAddress ipAddress) {
        this.findActiveByIpCalls.incrementAndGet();

        return Mono.justOrEmpty(this.ipBans.get(ipAddress))
                .filter(punishment -> !punishment.isExpired(this.clock.instant()));
    }

    @Override
    public Mono<ActivePunishment> issue(IssuePunishmentCommand command) {
        Instant now = this.clock.instant();
        Optional<Instant> expiresAt = command.duration().map(now::plus);

        ActivePunishment punishment = new ActivePunishment(
                this.nextId++, command.playerUuid(), Optional.empty(),
                command.kind(), command.ipAddress(),
                command.issuedByUuid(), command.issuedByName(),
                command.reason(),
                now, expiresAt,
                BanType.from(command.isPermanent())
        );

        if (command.kind().targetsAddress()) {
            command.ipAddress().ifPresent(address -> this.ipBans.put(address, punishment));
        } else {
            this.punishments.put(command.key(), punishment);
        }

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
    public Mono<Void> liftIpBan(LiftIpBanCommand command) {
        ActivePunishment lifted = this.ipBans.remove(command.ipAddress());
        UUID playerUuid = lifted == null ? NIL_UUID : lifted.playerUuid();

        this.log.add(new PunishmentLogEntry(
                playerUuid, command.liftedByUuid(), command.liftedByName(),
                PunishmentLogEntry.PunishmentAction.UNIPBAN, command.reason(),
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
        List<ActivePunishment> all = new ArrayList<>(this.punishments.values());
        all.addAll(this.ipBans.values());

        return Flux.fromIterable(all)
                .filter(punishment -> punishment.kind() == kind)
                .filter(punishment -> !punishment.isExpired(this.clock.instant()))
                .skip(offset)
                .take(limit);
    }

}
