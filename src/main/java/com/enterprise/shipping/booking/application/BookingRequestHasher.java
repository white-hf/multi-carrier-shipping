package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.application.BookingCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic intent fingerprint; serialized bytes are never persisted in the booking table. */
public final class BookingRequestHasher {
    private final ObjectMapper mapper;
    public BookingRequestHasher(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.setConfig(this.mapper.getSerializationConfig().with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
        this.mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(BookingCommand command) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(command)));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot fingerprint booking intent", error);
        }
    }
}
