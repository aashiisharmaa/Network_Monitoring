package com.network.monitoring.shared.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Sorted map serialization for stable identities; not the general API/date codec. */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private CanonicalJson() {}

    public static String write(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static byte[] bytes(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsBytes(value);
    }
}
