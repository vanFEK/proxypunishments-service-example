package net.vanfek.proxybans.service.adapter.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire shape expected from a remote ban backend.
 *
 * <p>Deliberately forgiving: unknown fields are ignored and every field is nullable, because the
 * point of this adapter is to sit in front of someone else's API — typically a website that already
 * owns the bans — without that API having to change. The mapping into the domain fills in defaults
 * instead of failing.
 */
final class RemotePayloads {

    private RemotePayloads() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BanEnvelope(@JsonProperty("data") BanPayload data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BanListEnvelope(@JsonProperty("data") List<BanPayload> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryEnvelope(@JsonProperty("data") List<HistoryPayload> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BanPayload(
            @JsonProperty("id") Long id,
            @JsonProperty("reason") String reason,
            @JsonProperty("time_start") Long timeStart,
            @JsonProperty("time_end") Long timeEnd,
            @JsonProperty("type") Integer type,
            @JsonProperty("punisher") Party punisher,
            @JsonProperty("banned") Party banned
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryPayload(
            @JsonProperty("type") String type,
            @JsonProperty("reason") String reason,
            @JsonProperty("time") Long time,
            @JsonProperty("temp_time") Long tempTime,
            @JsonProperty("admin") Party admin,
            @JsonProperty("banned") Party banned
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Party(@JsonProperty("login") String login, @JsonProperty("uuid") String uuid) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IdentityPayload(@JsonProperty("uuid") String uuid, @JsonProperty("login") String login) {
    }

    record CreateBanRequest(
            @JsonProperty("banned_uuid") String bannedUuid,
            @JsonProperty("punisher_uuid") String punisherUuid,
            @JsonProperty("punisher_login") String punisherLogin,
            @JsonProperty("reason") String reason,
            @JsonProperty("expired_at") Long expiredAt
    ) {
    }

    record LiftBanRequest(
            @JsonProperty("lifted_by_uuid") String liftedByUuid,
            @JsonProperty("lifted_by_login") String liftedByLogin,
            @JsonProperty("reason") String reason
    ) {
    }

}
