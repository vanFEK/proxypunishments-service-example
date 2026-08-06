package net.vanfek.proxypunishments.service.domain.notify;

import net.vanfek.proxypunishments.service.domain.model.ActivePunishment;
import net.vanfek.proxypunishments.service.domain.model.LiftPunishmentCommand;
import net.vanfek.proxypunishments.service.domain.model.ip.LiftIpBanCommand;

/** Implementations swallow their own failures: a moderation action must never fail because Discord did. */
public interface PunishmentAnnouncer {

    void announceIssued(ActivePunishment punishment);

    void announceLifted(LiftPunishmentCommand command);

    void announceIpBanLifted(LiftIpBanCommand command);

}
