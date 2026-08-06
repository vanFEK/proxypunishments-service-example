package net.vanfek.proxybans.service.adapter.local.ban;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

interface BanRowRepository extends ReactiveCrudRepository<BanRow, Long> {

    @Query("""
            SELECT b.*, k.login AS player_name
            FROM proxybans.bans b
            LEFT JOIN proxybans.known_players k ON k.uuid = b.player_uuid
            WHERE b.player_uuid = :uuid AND b.active = true
            LIMIT 1
            """)
    Mono<BanWithNameRow> findActiveByPlayerUuid(UUID uuid);

    // Without @Modifying, Spring Data R2DBC runs this as a query and the update silently does nothing.
    @Modifying
    @Query("UPDATE proxybans.bans SET active = false WHERE player_uuid = :uuid AND active = true")
    Mono<Long> deactivateAllFor(UUID uuid);

    // Without @Modifying, Spring Data R2DBC runs this as a query and the update silently does nothing.
    @Modifying
    @Query("""
            UPDATE proxybans.bans
            SET active = false
            WHERE active = true AND permanent = false AND expires_at IS NOT NULL AND expires_at <= :now
            """)
    Mono<Long> deactivateExpired(Instant now);

    @Query("""
            SELECT b.*, k.login AS player_name
            FROM proxybans.bans b
            LEFT JOIN proxybans.known_players k ON k.uuid = b.player_uuid
            WHERE b.active = true AND (b.permanent = true OR b.expires_at IS NULL OR b.expires_at > :now)
            ORDER BY b.issued_at DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<BanWithNameRow> listActive(Instant now, int limit, int offset);

}
