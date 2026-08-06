package net.vanfek.proxypunishments.service.adapter.local.user;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

import java.time.Instant;
import java.util.UUID;

record AltRow(@Id @Column("uuid") UUID uuid, @Column("login") String login, @Column("seen_at") Instant seenAt) {
}
