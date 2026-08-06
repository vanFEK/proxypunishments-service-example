package net.vanfek.proxybans.service.adapter.local.punishment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

// Exists to keep the partial unique index on active rows meaningful, not for read correctness.
@Component
@ConditionalOnProperty(name = "proxybans.mode", havingValue = "local")
class ExpiredPunishmentSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpiredPunishmentSweeper.class);
    private static final long INTERVAL_MS = 300_000L;

    private final PunishmentRowRepository punishments;
    private final Clock clock;

    ExpiredPunishmentSweeper(PunishmentRowRepository punishments, Clock clock) {
        this.punishments = punishments;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INTERVAL_MS)
    void sweep() {
        this.punishments.deactivateExpired(this.clock.instant())
                .subscribe(
                        deactivated -> {
                            if (deactivated > 0) {
                                LOGGER.info("Deactivated {} expired punishment(s)", deactivated);
                            }
                        },
                        error -> LOGGER.warn("Could not sweep expired punishments: {}", error.getMessage(), error)
                );
    }

}
