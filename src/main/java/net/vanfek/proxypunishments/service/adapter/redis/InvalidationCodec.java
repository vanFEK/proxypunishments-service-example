package net.vanfek.proxypunishments.service.adapter.redis;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

public final class InvalidationCodec {

    private final ObjectMapper json;

    public InvalidationCodec(ObjectMapper json) {
        this.json = json;
    }

    public Optional<String> encode(InvalidationMessage message) {
        try {
            return Optional.of(this.json.writeValueAsString(message));
        } catch (JacksonException ex) {
            return Optional.empty();
        }
    }

    public Optional<InvalidationMessage> decode(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(this.json.readValue(payload, InvalidationMessage.class));
        } catch (JacksonException ex) {
            return Optional.empty();
        }
    }

}
