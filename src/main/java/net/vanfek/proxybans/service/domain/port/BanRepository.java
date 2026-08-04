package net.vanfek.proxybans.service.domain.port;

import net.vanfek.proxybans.service.domain.model.ActiveBan;
import net.vanfek.proxybans.service.domain.model.IssueBanCommand;
import net.vanfek.proxybans.service.domain.model.LiftBanCommand;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The single seam between the domain and whatever actually stores bans — the local Postgres schema
 * or a remote HTTP backend. Swapping one for the other is a configuration change, not a code change.
 */
public interface BanRepository {

    Mono<ActiveBan> findActive(UUID playerUuid);

    Mono<ActiveBan> issue(IssueBanCommand command);

    Mono<Void> lift(LiftBanCommand command);

    Flux<PunishmentLogEntry> history(UUID playerUuid, int limit, int offset);

    Flux<ActiveBan> listActive(int limit, int offset);

}
