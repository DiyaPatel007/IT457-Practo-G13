package edu.dau.practolite.common.model;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Small helpers for building and reading DynamoDB AttributeValues with the low-level client. */
public final class Attr {

    private Attr() {
    }

    public static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    public static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    public static AttributeValue bool(boolean value) {
        return AttributeValue.fromBool(value);
    }

    public static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }

    public static Long num(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null || v.n() == null ? null : Long.parseLong(v.n());
    }

    public static boolean bool(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v != null && Boolean.TRUE.equals(v.bool());
    }
}
