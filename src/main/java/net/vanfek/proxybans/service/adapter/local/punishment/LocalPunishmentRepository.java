package net.vanfek.proxybans.service.adapter.local.punishment;

import net.vanfek.proxybans.service.domain.model.ActivePunishment;
import net.vanfek.proxybans.service.domain.model.BanType;
import net.vanfek.proxybans.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxybans.service.domain.model.IssueWarningCommand;
import net.vanfek.proxybans.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentKind;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.port.PunishmentRepository;
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
class LocalPunishmentRepository implements PunishmentRepository {

    private final PunishmentRowRepository punishments;
    private final PunishmentLogRowRepository logs;
    private final TransactionalOperator transactions;
    private final Clock clock;

    LocalPunishmentRepository(
            PunishmentRowRepository punishments, PunishmentLogRowRepository logs,
            TransactionalOperator transactions, Clock clock
    ) {
        this.punishments = punishments;
        this.logs = logs;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public Mono<ActivePunishment> findActive(UUID playerUuid, PunishmentKind kind) {
        Instant now = this.clock.instant();

        return this.punishments.findActiveByPlayerUuidAndKind(playerUuid, kind.name())
                .filter(row -> row.permanent() || row.expiresAt() == null || row.expiresAt().isAfter(now))
                .map(LocalPunishmentRepository::toDomain);
    }

    // The partial unique index turns a concurrent double-punishment into a constraint violation, hence the retry.
    @Override
    public Mono<ActivePunishment> issue(IssuePunishmentCommand command) {
        return issueOnce(command)
                .retryWhen(Retry.max(1).filter(DataIntegrityViolationException.class::isInstance))
                .as(this.transactions::transactional);
    }

    @Override
    public Mono<Void> lift(LiftPunishmentCommand command) {
        Instant now = this.clock.instant();

        PunishmentLogRow logEntry = new PunishmentLogRow(
                null, command.playerUuid(),
                command.liftedByUuid(), command.liftedByName(),
                command.kind().liftedAction().name(), command.reason(),
                now, null
        );

        return this.punishments.deactivateAllFor(command.playerUuid(), command.kind().name())
                .then(this.logs.save(logEntry))
                .then()
                .as(this.transactions::transactional);
    }

    @Override
    public Mono<PunishmentLogEntry> warn(IssueWarningCommand command) {
        PunishmentLogRow logEntry = new PunishmentLogRow(
                null, command.playerUuid(),
                command.issuedByUuid(), command.issuedByName(),
                PunishmentLogEntry.PunishmentAction.WARN.name(), command.reason(),
                this.clock.instant(), null
        );

        return this.logs.save(logEntry).map(LocalPunishmentRepository::toDomain);
    }

    @Override
    public Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset) {
        return this.logs.historyFor(playerUuid, limit, offset).map(LocalPunishmentRepository::toDomain);
    }

    @Override
    public Flux<ActivePunishment> listActive(PunishmentKind kind, int limit, int offset) {
        return this.punishments.listActive(kind.name(), this.clock.instant(), limit, offset)
                .map(LocalPunishmentRepository::toDomain);
    }

    private Mono<ActivePunishment> issueOnce(IssuePunishmentCommand command) {
        Instant now = this.clock.instant();
        Instant expiresAt = command.duration().map(now::plus).orElse(null);
        boolean permanent = command.isPermanent();

        PunishmentRow row = new PunishmentRow(
                null, command.playerUuid(), command.kind().name(),
                command.issuedByUuid(), command.issuedByName(),
                command.reason(),
                now, expiresAt,
                permanent, true
        );

        PunishmentLogRow logEntry = new PunishmentLogRow(
                null, command.playerUuid(),
                command.issuedByUuid(), command.issuedByName(),
                command.kind().issuedAction(permanent).name(), command.reason(),
                now, expiresAt
        );

        return this.punishments.deactivateAllFor(command.playerUuid(), command.kind().name())
                .then(this.punishments.save(row))
                .flatMap(saved -> this.logs.save(logEntry).thenReturn(saved))
                .map(LocalPunishmentRepository::toDomain);
    }

    private static ActivePunishment toDomain(PunishmentRow row) {
        return new ActivePunishment(
                row.id(), row.playerUuid(), Optional.empty(),
                PunishmentKind.valueOf(row.kind()),
                row.issuedByUuid(), row.issuedByName(),
                row.reason(),
                row.issuedAt(), Optional.ofNullable(row.expiresAt()),
                BanType.from(row.permanent())
        );
    }

    private static ActivePunishment toDomain(PunishmentWithNameRow row) {
        return new ActivePunishment(
                row.id(), row.playerUuid(), Optional.ofNullable(row.playerName()),
                PunishmentKind.valueOf(row.kind()),
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
