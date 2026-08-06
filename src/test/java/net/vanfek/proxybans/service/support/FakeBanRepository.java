package net.vanfek.proxybans.service.support;

import net.vanfek.proxybans.service.domain.model.ActiveBan;
import net.vanfek.proxybans.service.domain.model.BanType;
import net.vanfek.proxybans.service.domain.model.IssueBanCommand;
import net.vanfek.proxybans.service.domain.model.LiftBanCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.port.BanRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeBanRepository implements BanRepository {

    private final Map<UUID, ActiveBan> bans = new HashMap<>();
    private final List<PunishmentLogEntry> log = new java.util.ArrayList<>();
    private final AtomicInteger findActiveCalls = new AtomicInteger();
    private final Clock clock;

    private long nextId = 1L;

    public FakeBanRepository(Clock clock) {
        this.clock = clock;
    }

    public int findActiveCalls() {
        return this.findActiveCalls.get();
    }

    @Override
    public Mono<ActiveBan> findActive(UUID playerUuid) {
        this.findActiveCalls.incrementAndGet();

        return Mono.justOrEmpty(this.bans.get(playerUuid))
                .filter(ban -> !ban.isExpired(this.clock.instant()));
    }

    @Override
    public Mono<ActiveBan> issue(IssueBanCommand command) {
        Instant now = this.clock.instant();
        Optional<Instant> expiresAt = command.duration().map(now::plus);

        ActiveBan ban = new ActiveBan(
                this.nextId++, command.playerUuid(), Optional.empty(),
                command.issuedByUuid(), command.issuedByName(),
                command.reason(),
                now, expiresAt,
                BanType.from(command.isPermanent())
        );

        this.bans.put(command.playerUuid(), ban);
        this.log.add(new PunishmentLogEntry(
                command.playerUuid(), command.issuedByUuid(), command.issuedByName(),
                actionOf(command.isPermanent()), command.reason(),
                now, expiresAt
        ));

        return Mono.just(ban);
    }

    @Override
    public Mono<Void> lift(LiftBanCommand command) {
        this.bans.remove(command.playerUuid());
        this.log.add(new PunishmentLogEntry(
                command.playerUuid(), command.liftedByUuid(), command.liftedByName(),
                PunishmentLogEntry.PunishmentAction.UNBAN, command.reason(),
                this.clock.instant(), Optional.empty()
        ));

        return Mono.empty();
    }

    @Override
    public Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset) {
        return Flux.fromIterable(this.log)
                .filter(entry -> entry.playerUuid().equals(playerUuid))
                .skip(offset)
                .take(limit);
    }

    @Override
    public Flux<ActiveBan> listActive(int limit, int offset) {
        return Flux.fromIterable(this.bans.values())
                .filter(ban -> !ban.isExpired(this.clock.instant()))
                .skip(offset)
                .take(limit);
    }

    private static PunishmentLogEntry.PunishmentAction actionOf(boolean permanent) {
        return permanent
                ? PunishmentLogEntry.PunishmentAction.PERMBAN
                : PunishmentLogEntry.PunishmentAction.BAN;
    }

}
