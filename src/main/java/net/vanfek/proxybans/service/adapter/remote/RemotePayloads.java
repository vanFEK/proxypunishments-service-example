package net.vanfek.proxybans.service.adapter.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

final class RemotePayloads {

    private RemotePayloads() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PunishmentEnvelope(@JsonProperty("data") PunishmentPayload data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PunishmentListEnvelope(@JsonProperty("data") List<PunishmentPayload> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryEnvelope(@JsonProperty("data") List<HistoryPayload> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PunishmentPayload(
            @JsonProperty("id") Long id, @JsonProperty("reason") String reason,
            @JsonProperty("time_start") Long timeStart, @JsonProperty("time_end") Long timeEnd,
            @JsonProperty("type") Integer type,
            @JsonProperty("punisher") Party punisher, @JsonProperty("banned") Party banned
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryPayload(
            @JsonProperty("type") String type, @JsonProperty("reason") String reason,
            @JsonProperty("time") Long time, @JsonProperty("temp_time") Long tempTime,
            @JsonProperty("admin") Party admin, @JsonProperty("banned") Party banned
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Party(@JsonProperty("login") String login, @JsonProperty("uuid") String uuid) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IdentityPayload(@JsonProperty("uuid") String uuid, @JsonProperty("login") String login) {
    }

    record CreatePunishmentRequest(
            @JsonProperty("banned_uuid") String bannedUuid,
            @JsonProperty("punisher_uuid") String punisherUuid, @JsonProperty("punisher_login") String punisherLogin,
            @JsonProperty("reason") String reason, @JsonProperty("expired_at") Long expiredAt
    ) {
    }

    record LiftPunishmentRequest(
            @JsonProperty("lifted_by_uuid") String liftedByUuid, @JsonProperty("lifted_by_login") String liftedByLogin,
            @JsonProperty("reason") String reason
    ) {
    }

}
