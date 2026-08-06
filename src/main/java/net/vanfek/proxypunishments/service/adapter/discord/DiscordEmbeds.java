package net.vanfek.proxypunishments.service.adapter.discord;

import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import net.vanfek.proxypunishments.service.domain.model.ip.IpAddress;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DiscordEmbeds {

    private static final int ISSUED_COLOR = 0xE52E71;
    private static final int LIFTED_COLOR = 0x2ECC71;
    private static final int MAX_VALUE_LENGTH = 1024;

    private DiscordEmbeds() {
    }

    static Map<String, Object> issued(ActivePunishment punishment) {
        List<Map<String, Object>> fields = new ArrayList<>();

        fields.add(field("Player", punishment.playerName().orElseGet(() -> punishment.playerUuid().toString())));
        punishment.ipAddress().map(IpAddress::asText).ifPresent(address -> fields.add(field("Address", address)));

        fields.add(field("Moderator", punishment.issuedByName()));
        fields.add(field("Duration", punishment.expiresAt().map(Object::toString).orElse("permanent")));
        fields.add(field("Reason", punishment.reason()));

        return embed(title(punishment.kind(), "issued"), ISSUED_COLOR, fields);
    }

    static Map<String, Object> lifted(LiftPunishmentCommand command) {
        List<Map<String, Object>> fields = List.of(
                field("Player", command.playerUuid().toString()),
                field("Moderator", command.liftedByName()),
                field("Reason", command.reason())
        );

        return embed(title(command.kind(), "lifted"), LIFTED_COLOR, fields);
    }

    static Map<String, Object> ipBanLifted(LiftIpBanCommand command) {
        List<Map<String, Object>> fields = List.of(
                field("Address", command.ipAddress().asText()),
                field("Moderator", command.liftedByName()),
                field("Reason", command.reason())
        );

        return embed(title(PunishmentKind.IP_BAN, "lifted"), LIFTED_COLOR, fields);
    }

    private static Map<String, Object> embed(String title, int color, List<Map<String, Object>> fields) {
        Map<String, Object> embed = Map.of("title", title, "color", color, "fields", fields);

        return Map.of("embeds", List.of(embed));
    }

    private static String title(PunishmentKind kind, String verb) {
        return switch (kind) {
            case MUTE -> "Mute " + verb;
            case IP_BAN -> "IP ban " + verb;
            case BAN -> "Ban " + verb;
        };
    }

    private static Map<String, Object> field(String name, String value) {
        return Map.of("name", name, "value", trim(value), "inline", true);
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }

        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH);
    }

}
