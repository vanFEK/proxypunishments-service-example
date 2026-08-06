package net.vanfek.proxybans.service.api.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import net.vanfek.proxybans.service.domain.model.ActivePunishment;
import net.vanfek.proxybans.service.domain.model.PunishmentKind;
import net.vanfek.proxybans.service.domain.model.PunishmentLogEntry;
import net.vanfek.proxybans.service.domain.model.UserIdentity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class PunishmentDtos {

    private PunishmentDtos() {
    }

    record IssuePunishmentRequest(
            @NotNull UUID playerUuid, @NotNull PunishmentKind kind,
            @NotNull UUID issuedByUuid, @NotBlank @Size(max = 64) String issuedByName,
            @NotBlank @Size(max = 255) String reason, @Positive Long durationSeconds
    ) {
    }

    record LiftPunishmentRequest(
            @NotNull UUID liftedByUuid, @NotBlank @Size(max = 64) String liftedByName,
            @Size(max = 255) String reason
    ) {
    }

    record IssueWarningRequest(
            @NotNull UUID playerUuid,
            @NotNull UUID issuedByUuid, @NotBlank @Size(max = 64) String issuedByName,
            @NotBlank @Size(max = 255) String reason
    ) {
    }

    record RememberRequest(@NotNull UUID uuid, @NotBlank @Size(max = 32) String login) {

        UserIdentity toIdentity() {
            return new UserIdentity(this.uuid, this.login);
        }

    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ActivePunishmentResponse(
            long id, UUID playerUuid, String playerName,
            String kind,
            UUID issuedByUuid, String issuedByName,
            String reason,
            Instant issuedAt, Instant expiresAt,
            String type
    ) {

        static ActivePunishmentResponse from(ActivePunishment punishment) {
            return new ActivePunishmentResponse(
                    punishment.id(), punishment.playerUuid(), punishment.playerName().orElse(null),
                    punishment.kind().name(),
                    punishment.issuedByUuid(), punishment.issuedByName(),
                    punishment.reason(),
                    punishment.issuedAt(), punishment.expiresAt().orElse(null),
                    punishment.type().name()
            );
        }

    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record HistoryEntryResponse(
            UUID playerUuid, UUID issuedByUuid, String issuedByName,
            String action, String reason,
            Instant happenedAt, Instant expiresAt
    ) {

        static HistoryEntryResponse from(PunishmentLogEntry entry) {
            return new HistoryEntryResponse(
                    entry.playerUuid(), entry.issuedByUuid(), entry.issuedByName(),
                    entry.action().name(), entry.reason(),
                    entry.happenedAt(), entry.expiresAt().orElse(null)
            );
        }

    }

    record HistoryResponse(List<HistoryEntryResponse> data) {
    }

    record ActivePunishmentsListResponse(List<ActivePunishmentResponse> data) {
    }

    record LookupResponse(UUID uuid, String login) {

        static LookupResponse from(UserIdentity identity) {
            return new LookupResponse(identity.uuid(), identity.login());
        }

    }

}
