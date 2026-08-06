package net.vanfek.proxypunishments.service.domain.port;

import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.IssuePunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.IssueWarningCommand;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PunishmentRepository {

    Mono<ActivePunishment> findActive(UUID playerUuid, PunishmentKind kind);

    Mono<ActivePunishment> findActiveByIp(IpAddress ipAddress);

    Mono<ActivePunishment> issue(IssuePunishmentCommand command);

    Mono<Void> lift(LiftPunishmentCommand command);

    Mono<Void> liftIpBan(LiftIpBanCommand command);

    Mono<PunishmentLogEntry> warn(IssueWarningCommand command);

    Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset);

    Flux<ActivePunishment> listActive(PunishmentKind kind, int limit, int offset);

}
