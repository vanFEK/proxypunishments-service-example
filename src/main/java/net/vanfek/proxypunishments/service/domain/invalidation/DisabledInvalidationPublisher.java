package net.vanfek.proxypunishments.service.domain.invalidation;

public final class DisabledInvalidationPublisher implements InvalidationPublisher {

    @Override
    public void publish(InvalidationEvent event) {
    }

}
