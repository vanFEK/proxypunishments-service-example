package net.vanfek.proxypunishments.service.support;

import net.vanfek.proxypunishments.service.adapter.redis.InvalidationChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory stand-in for the Redis channel: publisher and subscriber meet here instead of on a socket. */
public final class FakeInvalidationChannel implements InvalidationChannel {

    private final Sinks.Many<String> messages = Sinks.many().multicast().onBackpressureBuffer();
    private final List<String> published = new CopyOnWriteArrayList<>();

    private volatile RuntimeException sendFailure;
    private volatile boolean failBeforeSubscribe;

    public void failSendWith(RuntimeException failure) {
        this.sendFailure = failure;
        this.failBeforeSubscribe = false;
    }

    public void throwOnSend(RuntimeException failure) {
        this.sendFailure = failure;
        this.failBeforeSubscribe = true;
    }

    public List<String> published() {
        return List.copyOf(this.published);
    }

    public void deliver(String payload) {
        this.messages.tryEmitNext(payload);
    }

    @Override
    public Mono<Long> send(String payload) {
        RuntimeException failure = this.sendFailure;

        if (failure != null) {
            if (this.failBeforeSubscribe) {
                throw failure;
            }

            return Mono.error(failure);
        }

        this.published.add(payload);
        this.messages.tryEmitNext(payload);

        return Mono.just(1L);
    }

    @Override
    public Flux<String> receive() {
        return this.messages.asFlux();
    }

}
