package net.vanfek.proxypunishments.service.adapter.local.punishment;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

interface PunishmentLogRowRepository extends ReactiveCrudRepository<PunishmentLogRow, Long> {

    @Query("""
            SELECT * FROM proxypunishments.ban_history
            WHERE player_uuid = :uuid
            ORDER BY happened_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<PunishmentLogRow> historyFor(UUID uuid, int limit, int offset);

}
