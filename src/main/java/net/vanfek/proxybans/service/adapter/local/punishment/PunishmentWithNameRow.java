package net.vanfek.proxybans.service.adapter.local.punishment;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;
import java.util.UUID;

record PunishmentWithNameRow(
        @Id Long id, @Column("player_uuid") UUID playerUuid, @Column("player_name") String playerName,
        @Column("kind") String kind,
        @Column("issued_by_uuid") UUID issuedByUuid, @Column("issued_by_name") String issuedByName,
        @Column("reason") String reason,
        @Column("issued_at") Instant issuedAt, @Column("expires_at") Instant expiresAt,
        @Column("permanent") boolean permanent
) {
}
