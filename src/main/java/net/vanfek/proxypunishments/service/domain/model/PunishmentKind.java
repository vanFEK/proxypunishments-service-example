package net.vanfek.proxypunishments.service.domain.model;

import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry.PunishmentAction;

public enum PunishmentKind {

    BAN,
    MUTE,
    IP_BAN;

    public PunishmentAction issuedAction(boolean permanent) {
        return switch (this) {
            case MUTE -> permanent ? PunishmentAction.PERMMUTE : PunishmentAction.MUTE;
            case IP_BAN -> permanent ? PunishmentAction.PERMIPBAN : PunishmentAction.IPBAN;
            case BAN -> permanent ? PunishmentAction.PERMBAN : PunishmentAction.BAN;
        };
    }

    public PunishmentAction liftedAction() {
        return switch (this) {
            case MUTE -> PunishmentAction.UNMUTE;
            case IP_BAN -> PunishmentAction.UNIPBAN;
            case BAN -> PunishmentAction.UNBAN;
        };
    }

    public boolean targetsAddress() {
        return this == IP_BAN;
    }

}
