package net.vanfek.proxybans.service.adapter.local.ban;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

// Exists to keep the partial unique index on active rows meaningful, not for read correctness.
@Component
@ConditionalOnProperty(name = "proxybans.mode", havingValue = "local")
class ExpiredBanSweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpiredBanSweeper.class);
    private static final long INTERVAL_MS = 300_000L;

    private final BanRowRepository bans;
    private final Clock clock;

    ExpiredBanSweeper(BanRowRepository bans, Clock clock) {
        this.bans = bans;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = INTERVAL_MS, initialDelay = INTERVAL_MS)
    void sweep() {
        this.bans.deactivateExpired(this.clock.instant())
                .subscribe(
                        deactivated -> {
                            if (deactivated > 0) {
                                LOGGER.info("Deactivated {} expired ban(s)", deactivated);
                            }
                        },
                        error -> LOGGER.warn("Could not sweep expired bans: {}", error.getMessage(), error)
                );
    }

}
