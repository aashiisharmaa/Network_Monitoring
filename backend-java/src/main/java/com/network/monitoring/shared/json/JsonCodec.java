package com.network.monitoring.shared.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;

/** Shared JSON operations without exposing a mutable ObjectMapper to other modules. */
public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonCodec() {}

    public static <T> T convertValue(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static <T> T convertValue(Object value, TypeReference<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static <T> T readValue(String value, TypeReference<T> type) throws JsonProcessingException {
        return MAPPER.readValue(value, type);
    }

    public static String writeValueAsString(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static com.fasterxml.jackson.core.JsonGenerator createGenerator(java.io.OutputStream output)
            throws java.io.IOException {
        return MAPPER.getFactory().createGenerator(output);
    }

    public static Map<String, Object> objectMap(Object value) {
        return value == null ? Map.of() : MAPPER.convertValue(value, new TypeReference<>() {});
    }
}
