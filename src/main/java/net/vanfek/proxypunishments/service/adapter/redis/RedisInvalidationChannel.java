package net.vanfek.proxypunishments.service.adapter.redis;

import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/** The listener container is built per subscription attempt: building one eagerly fails startup whenever Redis is down. */
public final class RedisInvalidationChannel implements InvalidationChannel, AutoCloseable {

    private final ReactiveRedisConnectionFactory connections;
    private final ReactiveStringRedisTemplate redis;
    private final ChannelTopic topic;

    private final AtomicReference<ReactiveRedisMessageListenerContainer> listeners = new AtomicReference<>();

    public RedisInvalidationChannel(ReactiveRedisConnectionFactory connections, String channel) {
        this.connections = connections;
        this.redis = new ReactiveStringRedisTemplate(connections);
        this.topic = ChannelTopic.of(channel);
    }

    @Override
    public Mono<Long> send(String payload) {
        return this.redis.convertAndSend(this.topic.getTopic(), payload);
    }

    @Override
    public Flux<String> receive() {
        return Flux.defer(() -> {
            ReactiveRedisMessageListenerContainer container = new ReactiveRedisMessageListenerContainer(this.connections);

            destroyQuietly(this.listeners.getAndSet(container));

            return container.receive(this.topic).map(message -> message.getMessage());
        });
    }

    @Override
    public void close() {
        destroyQuietly(this.listeners.getAndSet(null));
    }

    private static void destroyQuietly(ReactiveRedisMessageListenerContainer container) {
        if (container == null) {
            return;
        }

        try {
            container.destroy();
        } catch (RuntimeException ex) {
            container.destroyLater().subscribe();
        }
    }

}
