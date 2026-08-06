package net.vanfek.proxypunishments.service.adapter.local.punishment;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

interface PunishmentRowRepository extends ReactiveCrudRepository<PunishmentRow, Long> {

    @Query("""
            SELECT p.*, k.login AS player_name
            FROM proxypunishments.punishments p
            LEFT JOIN proxypunishments.known_players k ON k.uuid = p.player_uuid
            WHERE p.player_uuid = :uuid AND p.kind = :kind AND p.active = true
            LIMIT 1
            """)
    Mono<PunishmentWithNameRow> findActiveByPlayerUuidAndKind(UUID uuid, String kind);

    // Without @Modifying, Spring Data R2DBC runs this as a query and the update silently does nothing.
    @Modifying
    @Query("""
            UPDATE proxypunishments.punishments
            SET active = false
            WHERE player_uuid = :uuid AND kind = :kind AND active = true
            """)
    Mono<Long> deactivateAllFor(UUID uuid, String kind);

    // Without @Modifying, Spring Data R2DBC runs this as a query and the update silently does nothing.
    @Modifying
    @Query("""
            UPDATE proxypunishments.punishments
            SET active = false
            WHERE active = true AND permanent = false AND expires_at IS NOT NULL AND expires_at <= :now
            """)
    Mono<Long> deactivateExpired(Instant now);

    @Query("""
            SELECT p.*, k.login AS player_name
            FROM proxypunishments.punishments p
            LEFT JOIN proxypunishments.known_players k ON k.uuid = p.player_uuid
            WHERE p.kind = :kind AND p.active = true AND (p.permanent = true OR p.expires_at IS NULL OR p.expires_at > :now)
            ORDER BY p.issued_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<PunishmentWithNameRow> listActive(String kind, Instant now, int limit, int offset);

}
