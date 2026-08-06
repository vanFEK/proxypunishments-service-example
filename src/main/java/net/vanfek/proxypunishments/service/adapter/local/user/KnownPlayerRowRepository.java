package net.vanfek.proxypunishments.service.adapter.local.user;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

interface KnownPlayerRowRepository extends ReactiveCrudRepository<KnownPlayerRow, UUID> {

    @Query("""
            SELECT uuid, login, login_lower, seen_at
            FROM proxypunishments.known_players
            WHERE login_lower = :login
            LIMIT 1
            """)
    Mono<KnownPlayerRow> findByLoginLower(String login);

    // first_seen_at is only ever written by the insert branch, so it keeps recording the first sighting.
    @Modifying
    @Query("""
            INSERT INTO proxypunishments.known_players (uuid, login, login_lower, seen_at, last_ip, first_seen_at)
            VALUES (:uuid, :login, :loginLower, :seenAt, CAST(:lastIp AS inet), :seenAt)
            ON CONFLICT (uuid) DO UPDATE
            SET login = EXCLUDED.login, login_lower = EXCLUDED.login_lower, seen_at = EXCLUDED.seen_at,
                last_ip = COALESCE(EXCLUDED.last_ip, known_players.last_ip)
            """)
    Mono<Integer> upsert(UUID uuid, String login, String loginLower, Instant seenAt, String lastIp);

    @Query("""
            SELECT k.uuid, k.login, k.seen_at
            FROM proxypunishments.known_players k
            WHERE k.uuid <> :uuid
              AND k.last_ip IS NOT NULL
              AND k.last_ip = (SELECT t.last_ip FROM proxypunishments.known_players t WHERE t.uuid = :uuid)
            ORDER BY k.seen_at DESC
            LIMIT :limit
            """)
    Flux<AltRow> findAlts(UUID uuid, int limit);

}
