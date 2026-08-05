package net.vanfek.proxybans.service.adapter.local.ban;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Marks expired temporary bans inactive.
 *
 * <p>Reads filter expired bans out anyway, so this is not about correctness of lookups. It is about
 * the {@code bans} table: the partial unique index only covers active rows, so leaving every expired
 * ban flagged active means the index grows without bound and the "one active ban per player"
 * invariant is enforced against rows that no longer mean anything.
 */
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
