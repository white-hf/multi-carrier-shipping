package com.enterprise.shipping.booking.application;

import com.enterprise.shipping.booking.domain.BookingCommand;
import com.enterprise.shipping.booking.domain.BookingResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Centralizes canonical intent serialization and persisted response snapshots. */
public final class BookingJson {
    private final ObjectMapper mapper;

    public BookingJson(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.setConfig(this.mapper.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
        this.mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize booking data", error);
        }
    }

    public BookingResponse readResponse(String json) {
        try {
            return mapper.readValue(json, BookingResponse.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid persisted booking response", error);
        }
    }

    public String hash(BookingCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(command).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
