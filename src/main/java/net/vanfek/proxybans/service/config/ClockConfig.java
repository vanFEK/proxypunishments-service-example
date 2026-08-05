package net.vanfek.proxybans.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class ClockConfig {

    /**
     * Injected everywhere instead of calling {@code Instant.now()} directly, so expiry logic can be
     * tested at an exact instant rather than around a sleep.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

}
