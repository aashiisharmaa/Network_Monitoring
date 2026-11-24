package com.network.monitoring.alarm.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;

/** Operational counts/IDs must not silently truncate decimals or coerce numeric strings. */
public final class StrictNumbers {
    private StrictNumbers() {}
    public static class IntegerValue extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) throw JsonMappingException.from(parser,"Supply an integer or null, not a decimal/string count.");
            return parser.getIntValue();
        }
    }
    public static class LongValue extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) throw JsonMappingException.from(parser,"Supply an integer identifier.");
            return parser.getLongValue();
        }
    }
}
