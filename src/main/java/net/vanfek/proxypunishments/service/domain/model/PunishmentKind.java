package net.vanfek.proxypunishments.service.domain.model;

import net.vanfek.proxypunishments.service.domain.model.PunishmentLogEntry.PunishmentAction;

public enum PunishmentKind {

    BAN,
    MUTE;

    public PunishmentAction issuedAction(boolean permanent) {
        if (this == MUTE) {
            return permanent ? PunishmentAction.PERMMUTE : PunishmentAction.MUTE;
        }

        return permanent ? PunishmentAction.PERMBAN : PunishmentAction.BAN;
    }

    public PunishmentAction liftedAction() {
        return this == MUTE ? PunishmentAction.UNMUTE : PunishmentAction.UNBAN;
    }

}
