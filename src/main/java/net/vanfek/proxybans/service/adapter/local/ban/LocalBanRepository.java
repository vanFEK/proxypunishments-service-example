package net.vanfek.proxybans.service.adapter.local.ban;

import net.vanfek.proxybans.service.domain.model.ActiveBan;
import net.vanfek.proxybans.service.domain.model.BanType;
import net.vanfek.proxybans.service.domain.model.IssueBanCommand;
import net.vanfek.proxybans.service.domain.model.LiftBanCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.port.BanRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "proxybans.mode", havingValue = "local")
class LocalBanRepository implements BanRepository {

    private final BanRowRepository bans;
    private final PunishmentLogRowRepository logs;
    private final TransactionalOperator transactions;
    private final Clock clock;

    LocalBanRepository(
            BanRowRepository bans, PunishmentLogRowRepository logs,
            TransactionalOperator transactions, Clock clock
    ) {
        this.bans = bans;
        this.logs = logs;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Mono<ActiveBan> findActive(UUID playerUuid) {
        Instant now = this.clock.instant();

        return this.bans.findActiveByPlayerUuid(playerUuid)
                .filter(row -> row.permanent() || row.expiresAt() == null || row.expiresAt().isAfter(now))
                .map(LocalBanRepository::toDomain);
    }

    // The partial unique index turns a concurrent double-ban into a constraint violation, hence the retry.
    @Override
    public Mono<ActiveBan> issue(IssueBanCommand command) {
        return issueOnce(command)
                .retryWhen(Retry.max(1).filter(DataIntegrityViolationException.class::isInstance))
                .as(this.transactions::transactional);
    }

    @Override
    public Mono<Void> lift(LiftBanCommand command) {
        Instant now = this.clock.instant();

        PunishmentLogRow logEntry = new PunishmentLogRow(
                null, command.playerUuid(),
                command.liftedByUuid(), command.liftedByName(),
                PunishmentLogEntry.PunishmentAction.UNBAN.name(), command.reason(),
                now, null
        );

        return this.bans.deactivateAllFor(command.playerUuid())
                .then(this.logs.save(logEntry))
                .then()
                .as(this.transactions::transactional);
    }

    @Override
    public Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset) {
        return this.logs.historyFor(playerUuid, limit, offset).map(LocalBanRepository::toDomain);
    }

    @Override
    public Flux<ActiveBan> listActive(int limit, int offset) {
        return this.bans.listActive(this.clock.instant(), limit, offset).map(LocalBanRepository::toDomain);
    }

    private Mono<ActiveBan> issueOnce(IssueBanCommand command) {
        Instant now = this.clock.instant();
        Instant expiresAt = command.duration().map(now::plus).orElse(null);
        boolean permanent = command.isPermanent();

        BanRow row = new BanRow(
                null, command.playerUuid(),
                command.issuedByUuid(), command.issuedByName(),
                command.reason(),
                now, expiresAt,
                permanent, true
        );

        PunishmentLogRow logEntry = new PunishmentLogRow(
                null, command.playerUuid(),
                command.issuedByUuid(), command.issuedByName(),
                actionOf(permanent), command.reason(),
                now, expiresAt
        );

        return this.bans.deactivateAllFor(command.playerUuid())
                .then(this.bans.save(row))
                .flatMap(saved -> this.logs.save(logEntry).thenReturn(saved))
                .map(LocalBanRepository::toDomain);
    }

    private static String actionOf(boolean permanent) {
        return permanent
                ? PunishmentLogEntry.PunishmentAction.PERMBAN.name()
                : PunishmentLogEntry.PunishmentAction.BAN.name();
    }

    private static ActiveBan toDomain(BanRow row) {
        return new ActiveBan(
                row.id(), row.playerUuid(), Optional.empty(),
                row.issuedByUuid(), row.issuedByName(),
                row.reason(),
                row.issuedAt(), Optional.ofNullable(row.expiresAt()),
                BanType.from(row.permanent())
        );
    }

    private static ActiveBan toDomain(BanWithNameRow row) {
        return new ActiveBan(
                row.id(), row.playerUuid(), Optional.ofNullable(row.playerName()),
                row.issuedByUuid(), row.issuedByName(),
                row.reason(),
                row.issuedAt(), Optional.ofNullable(row.expiresAt()),
                BanType.from(row.permanent())
        );
    }

    private static PunishmentLogEntry toDomain(PunishmentLogRow row) {
        return new PunishmentLogEntry(
                row.playerUuid(), row.issuedByUuid(), row.issuedByName(),
                PunishmentLogEntry.PunishmentAction.valueOf(row.action()),
                row.reason(), row.happenedAt(), Optional.ofNullable(row.expiresAt())
        );
    }

}
