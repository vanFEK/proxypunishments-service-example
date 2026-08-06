package net.vanfek.proxypunishments.service.support;

import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationEvent;
import net.vanfek.proxypunishments.service.domain.invalidation.InvalidationPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordingInvalidationPublisher implements InvalidationPublisher {

    private final List<InvalidationEvent> events = new CopyOnWriteArrayList<>();

    public List<InvalidationEvent> events() {
        return List.copyOf(this.events);
    }

    @Override
    public void publish(InvalidationEvent event) {
        this.events.add(event);
    }

}
