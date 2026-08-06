package net.vanfek.proxypunishments.service.adapter.redis;

import net.vanfek.proxypunishments.service.domain.service.PunishmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RedisInvalidationSubscriber implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RedisInvalidationSubscriber.class);
    private static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final InvalidationChannel channel;
    private final InvalidationCodec codec;
    private final PunishmentService punishments;
    private final String origin;

    private final AtomicBoolean failureLogged = new AtomicBoolean();

    private Disposable subscription;

    public RedisInvalidationSubscriber(
            InvalidationChannel channel, InvalidationCodec codec,
            PunishmentService punishments, String origin
    ) {
        this.channel = channel;
        this.codec = codec;
        this.punishments = punishments;
        this.origin = origin;
    }

    public void start() {
        this.subscription = this.channel.receive()
                .doOnNext(this::accept)
                .doOnError(this::onFailure)
                .retryWhen(Retry.backoff(Long.MAX_VALUE, MIN_BACKOFF).maxBackoff(MAX_BACKOFF))
                .onErrorComplete()
                .subscribe();
    }

    @Override
    public void close() {
        if (this.subscription == null) {
            return;
        }

        this.subscription.dispose();
    }

    private void accept(String payload) {
        try {
            this.codec.decode(payload)
                    .filter(message -> !this.origin.equals(message.origin()))
                    .flatMap(InvalidationMessage::toEvent)
                    .ifPresent(this.punishments::applyInvalidation);

            this.failureLogged.set(false);
        } catch (RuntimeException ex) {
            onFailure(ex);
        }
    }

    private void onFailure(Throwable throwable) {
        if (this.failureLogged.compareAndSet(false, true)) {
            LOG.warn("Redis invalidation stream failed — retrying in the background, cache expiry still applies", throwable);
        }
    }

}
