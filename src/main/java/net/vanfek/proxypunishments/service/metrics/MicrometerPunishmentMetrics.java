package net.vanfek.proxypunishments.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import net.vanfek.proxypunishments.service.domain.model.PunishmentKind;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class MicrometerPunishmentMetrics implements PunishmentMetrics {

    public static final String PUNISHMENTS_ISSUED = "proxypunishments.punishments.issued";
    public static final String PUNISHMENTS_LIFTED = "proxypunishments.punishments.lifted";
    public static final String CACHE_LOOKUPS = "proxypunishments.cache.lookups";
    public static final String REPOSITORY_CALLS = "proxypunishments.repository.calls";
    public static final String INVALIDATIONS_PUBLISHED = "proxypunishments.invalidations.published";
    public static final String INVALIDATIONS_RECEIVED = "proxypunishments.invalidations.received";
    public static final String WEBHOOK_DELIVERIES = "proxypunishments.webhook.deliveries";

    private final MeterRegistry registry;

    public MicrometerPunishmentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void punishmentIssued(PunishmentKind kind) {
        this.registry.counter(PUNISHMENTS_ISSUED, "kind", kind.name()).increment();
    }

    @Override
    public void punishmentLifted(PunishmentKind kind) {
        this.registry.counter(PUNISHMENTS_LIFTED, "kind", kind.name()).increment();
    }

    @Override
    public void cacheLookup(String cache, boolean hit) {
        this.registry.counter(CACHE_LOOKUPS, "cache", cache, "result", hit ? "hit" : "miss").increment();
    }

    @Override
    public void invalidationPublished() {
        this.registry.counter(INVALIDATIONS_PUBLISHED).increment();
    }

    @Override
    public void invalidationReceived() {
        this.registry.counter(INVALIDATIONS_RECEIVED).increment();
    }

    @Override
    public void webhookDelivery(String outcome) {
        this.registry.counter(WEBHOOK_DELIVERIES, "outcome", outcome).increment();
    }

    @Override
    public <T> Mono<T> timed(String operation, Mono<T> call) {
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start(this.registry);

            return call.doFinally(signal -> stop(sample, operation));
        });
    }

    @Override
    public <T> Flux<T> timed(String operation, Flux<T> call) {
        return Flux.defer(() -> {
            Timer.Sample sample = Timer.start(this.registry);

            return call.doFinally(signal -> stop(sample, operation));
        });
    }

    private void stop(Timer.Sample sample, String operation) {
        sample.stop(this.registry.timer(REPOSITORY_CALLS, "operation", operation));
    }

}
