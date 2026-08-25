package com.network.monitoring.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LicenseFileService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final String signingSecret;

    public LicenseFileService(@Value("${app.license-signing-secret}") String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalStateException("License signing secret is required.");
        }
        this.signingSecret = signingSecret;
    }

    public byte[] createLicenseFile(Map<String, Object> payload) {
        try {
            String payloadJson = MAPPER.writeValueAsString(payload);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signature = sign(encodedPayload);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("payload", encodedPayload);
            envelope.put("signature", signature);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create license file.", ex);
        }
    }

    public Map<String, Object> verifyLicenseFile(byte[] bytes) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = MAPPER.readValue(bytes, Map.class);
            String encodedPayload = string(envelope.get("payload"));
            String signature = string(envelope.get("signature"));
            if (encodedPayload == null || signature == null) {
                throw new IllegalArgumentException("License file is invalid.");
            }
            String expected = sign(encodedPayload);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("License signature is invalid.");
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(encodedPayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = MAPPER.readValue(payloadBytes, Map.class);
            return payload;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("License file could not be read.");
        }
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String string(Object value) {
        if (value == null) {
            return null;
        }
        String parsed = String.valueOf(value).trim();
        return parsed.isEmpty() ? null : parsed;
    }
}
