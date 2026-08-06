package net.vanfek.proxypunishments.service.adapter.redis;

import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationEvent;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationPublisher;
import net.vanfek.proxypunishments.service.domain.metrics.PunishmentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RedisInvalidationPublisher implements InvalidationPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(RedisInvalidationPublisher.class);

    private final InvalidationChannel channel;
    private final InvalidationCodec codec;
    private final PunishmentMetrics metrics;
    private final String origin;

    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public RedisInvalidationPublisher(
            InvalidationChannel channel, InvalidationCodec codec,
            PunishmentMetrics metrics, String origin
    ) {
        this.channel = channel;
        this.codec = codec;
        this.metrics = metrics;
        this.origin = origin;
    }

    @Override
    public void publish(InvalidationEvent event) {
        Optional<String> payload = this.codec.encode(InvalidationMessage.of(event, this.origin));

        if (payload.isEmpty()) {
            return;
        }

        try {
            this.channel.send(payload.get())
                    .doOnSuccess(unused -> onPublished())
                    .doOnError(this::onFailure)
                    .onErrorComplete()
                    .subscribe();
        } catch (RuntimeException ex) {
            onFailure(ex);
        }
    }

    private void onPublished() {
        this.failureLogged.set(false);
        this.metrics.invalidationPublished();
    }

    private void onFailure(Throwable throwable) {
        if (this.failureLogged.compareAndSet(false, true)) {
            LOG.warn("Redis invalidation could not be published — other nodes fall back to cache expiry", throwable);
        }
    }

}
