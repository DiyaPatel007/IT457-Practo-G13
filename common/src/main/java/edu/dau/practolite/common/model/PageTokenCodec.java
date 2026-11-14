package edu.dau.practolite.common.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.dau.practolite.common.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Converts DynamoDB's LastEvaluatedKey to an opaque "nextToken" string and back.
 * All our key attributes are strings, so the token is Base64url(JSON of name -> string value).
 * A tampered token can only move the start position inside the same Query, whose key condition
 * still limits results to the caller's partition.
 */
public final class PageTokenCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> TYPE = new TypeReference<>() {
    };

    private PageTokenCodec() {
    }

    public static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        Map<String, String> plain = new LinkedHashMap<>();
        lastEvaluatedKey.forEach((k, v) -> plain.put(k, v.s()));
        try {
            byte[] json = MAPPER.writeValueAsBytes(plain);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode page token", e);
        }
    }

    public static Map<String, AttributeValue> decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(token);
            Map<String, String> plain = MAPPER.readValue(new String(json, StandardCharsets.UTF_8), TYPE);
            Map<String, AttributeValue> key = new HashMap<>();
            plain.forEach((k, v) -> {
                if (v == null) {
                    throw new IllegalArgumentException("null value");
                }
                key.put(k, AttributeValue.fromS(v));
            });
            if (key.isEmpty() || key.size() > 4) {
                throw new IllegalArgumentException("bad size");
            }
            return key;
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_PAGE_TOKEN", "nextToken is not valid");
        }
    }
}
