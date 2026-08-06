package net.vanfek.proxypunishments.service.adapter.local.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("known_players")
record KnownPlayerRow(
        @Id @Column("uuid") UUID uuid, @Column("login") String login,
        @Column("login_lower") String loginLower, @Column("seen_at") Instant seenAt
) {
}
