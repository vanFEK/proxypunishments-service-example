package net.vanfek.proxypunishments.service.adapter.local.punishment;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("ban_history")
record PunishmentLogRow(
        @Id Long id, @Column("player_uuid") UUID playerUuid,
        @Column("issued_by_uuid") UUID issuedByUuid, @Column("issued_by_name") String issuedByName,
        @Column("action") String action, @Column("reason") String reason,
        @Column("happened_at") Instant happenedAt, @Column("expires_at") Instant expiresAt
) {
}
