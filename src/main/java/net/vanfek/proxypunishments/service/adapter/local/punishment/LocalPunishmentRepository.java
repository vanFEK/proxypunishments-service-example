package net.vanfek.proxypunishments.service.adapter.local.punishment;

import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.BanType;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.IssueWarningCommand;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;
import net.vanfek.proxypunishments.service.domain.port.PunishmentRepository;
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
@ConditionalOnProperty(name = "proxypunishments.mode", havingValue = "local")
class LocalPunishmentRepository implements PunishmentRepository {

    private static final UUID NIL_UUID = new UUID(0L, 0L);

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
                .filter(row -> isStillInForce(row, now))
                .map(LocalPunishmentRepository::toDomain);
    }

    @Override
    public Mono<ActivePunishment> findActiveByIp(IpAddress ipAddress) {
        Instant now = this.clock.instant();

        return this.punishments.findActiveIpBan(ipAddress.asText())
                .filter(row -> isStillInForce(row, now))
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
        PunishmentLogRow logEntry = new PunishmentLogRow(
                null, command.playerUuid(),
                command.liftedByUuid(), command.liftedByName(),
                command.kind().liftedAction().name(), command.reason(),
                this.clock.instant(), null
        );

        return this.punishments.deactivateAllFor(command.playerUuid(), command.kind().name())
                .then(this.logs.save(logEntry))
                .then()
                .as(this.transactions::transactional);
    }

    // The log entry is filed against whoever the address was banned through, so the lift shows up in their history.
    @Override
    public Mono<Void> liftIpBan(LiftIpBanCommand command) {
        String address = command.ipAddress().asText();

        return this.punishments.findActiveIpBan(address)
                .map(PunishmentWithNameRow::playerUuid)
                .defaultIfEmpty(NIL_UUID)
                .flatMap(playerUuid -> this.punishments.deactivateAllForIp(address)
                        .then(this.logs.save(liftLog(command, playerUuid))))
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

        return deactivatePrevious(command)
                .then(this.punishments.save(row))
                .flatMap(saved -> attachIpAddress(saved, command))
                .flatMap(saved -> this.logs.save(logEntry).thenReturn(saved))
                .map(saved -> toDomain(saved, command.ipAddress()));
    }

    private Mono<Long> deactivatePrevious(IssuePunishmentCommand command) {
        Optional<IpAddress> address = command.ipAddress();

        if (command.kind().targetsAddress() && address.isPresent()) {
            return this.punishments.deactivateAllForIp(address.get().asText());
        }

        return this.punishments.deactivateAllFor(command.playerUuid(), command.kind().name());
    }

    private Mono<PunishmentRow> attachIpAddress(PunishmentRow saved, IssuePunishmentCommand command) {
        Optional<IpAddress> address = command.ipAddress();

        if (address.isEmpty()) {
            return Mono.just(saved);
        }

        return this.punishments.attachIpAddress(saved.id(), address.get().asText()).thenReturn(saved);
    }

    private PunishmentLogRow liftLog(LiftIpBanCommand command, UUID playerUuid) {
        return new PunishmentLogRow(
                null, playerUuid,
                command.liftedByUuid(), command.liftedByName(),
                PunishmentLogEntry.PunishmentAction.UNIPBAN.name(), command.reason(),
                this.clock.instant(), null
        );
    }

    private static boolean isStillInForce(PunishmentWithNameRow row, Instant now) {
        return row.permanent() || row.expiresAt() == null || row.expiresAt().isAfter(now);
    }

    private static ActivePunishment toDomain(PunishmentRow row, Optional<IpAddress> ipAddress) {
        return new ActivePunishment(
                row.id(), row.playerUuid(), Optional.empty(),
                PunishmentKind.valueOf(row.kind()), ipAddress,
                row.issuedByUuid(), row.issuedByName(),
                row.reason(),
                row.issuedAt(), Optional.ofNullable(row.expiresAt()),
                BanType.from(row.permanent())
        );
    }

    private static ActivePunishment toDomain(PunishmentWithNameRow row) {
        return new ActivePunishment(
                row.id(), row.playerUuid(), Optional.ofNullable(row.playerName()),
                PunishmentKind.valueOf(row.kind()), IpAddress.parse(row.ipAddress()),
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
