package net.vanfek.proxypunishments.service.domain.notify;

import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;

public final class DisabledPunishmentAnnouncer implements PunishmentAnnouncer {

    @Override
    public void announceIssued(ActivePunishment punishment) {
    }

    @Override
    public void announceLifted(LiftPunishmentCommand command) {
    }

    @Override
    public void announceIpBanLifted(LiftIpBanCommand command) {
    }

}
