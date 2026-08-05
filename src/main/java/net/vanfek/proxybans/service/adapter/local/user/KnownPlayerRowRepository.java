package net.vanfek.proxybans.service.adapter.local.user;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

interface KnownPlayerRowRepository extends ReactiveCrudRepository<KnownPlayerRow, UUID> {

    @Query("SELECT * FROM proxybans.known_players WHERE login_lower = :login LIMIT 1")
    Mono<KnownPlayerRow> findByLoginLower(String login);

    @Modifying
    @Query("""
            INSERT INTO proxybans.known_players (uuid, login, login_lower, seen_at)
            VALUES (:uuid, :login, :loginLower, :seenAt)
            ON CONFLICT (uuid) DO UPDATE
            SET login = EXCLUDED.login, login_lower = EXCLUDED.login_lower, seen_at = EXCLUDED.seen_at
            """)
    Mono<Integer> upsert(UUID uuid, String login, String loginLower, Instant seenAt);

}
