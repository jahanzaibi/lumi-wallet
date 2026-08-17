package com.lumi.wallet.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.lumi.wallet.common.ErrorCode;
import com.lumi.wallet.common.WalletException;

/**
 * The standard message envelope every event uses, inbound and outbound (HELP.md section 25).
 *
 * <p>One type serves both directions on purpose: the wallet consumes order and payment events and
 * publishes its own, and having a single shape means the correlation id that arrives with an order
 * event can be carried straight through to the reward event the wallet emits in response.
 *
 * <p>The payload stays a loose map rather than a typed class per event. The wallet does not own the
 * order or payment schemas, so pinning them down here would turn any additive change upstream into
 * a deserialisation failure and, with a dead-letter queue behind it, an operational incident. The
 * accessors below extract what the wallet actually needs and complain clearly when a required field
 * is missing.
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        Integer eventVersion,
        Instant occurredAt,
        String source,
        String correlationId,
        Map<String, Object> payload) {

    public static final int CURRENT_VERSION = 1;

    public EventEnvelope {
        // Copied defensively, and null values are dropped rather than rejected: an upstream service
        // that serialises an absent field as an explicit null is being unhelpful, not malformed, and
        // Map.copyOf would turn that into a dead-lettered message.
        payload = payload == null ? Map.of() : unmodifiableWithoutNulls(payload);
    }

    private static Map<String, Object> unmodifiableWithoutNulls(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    public static EventEnvelope of(String eventId, String eventType, String source,
            String correlationId, Instant occurredAt, Map<String, Object> payload) {
        return new EventEnvelope(eventId, eventType, CURRENT_VERSION, occurredAt, source,
                correlationId, payload);
    }

    // ---------------------------------------------------------------------------------------------
    // Payload access
    // ---------------------------------------------------------------------------------------------

    /** A required string field. */
    public String requireString(String field) {
        Object value = payload.get(field);
        if (value == null || value.toString().isBlank()) {
            throw WalletException.of(ErrorCode.VALIDATION_FAILED,
                    "event %s (%s) is missing required payload field '%s'", eventId, eventType,
                    field);
        }
        return value.toString();
    }

    /** An optional string field. */
    public String optionalString(String field) {
        Object value = payload.get(field);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    /**
     * An optional decimal field.
     *
     * <p>Parsed through the value's string form so that a payload number arriving as an Integer, a
     * Double or a BigDecimal all land on the same amount. Money is never held as a double in this
     * service, and the boundary is the right place to enforce that.
     */
    public BigDecimal optionalDecimal(String field) {
        Object value = payload.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw WalletException.of(ErrorCode.VALIDATION_FAILED,
                    "event %s (%s) payload field '%s' is not a number: %s", eventId, eventType,
                    field, value);
        }
    }

    public BigDecimal requireDecimal(String field) {
        BigDecimal value = optionalDecimal(field);
        if (value == null) {
            throw WalletException.of(ErrorCode.VALIDATION_FAILED,
                    "event %s (%s) is missing required payload field '%s'", eventId, eventType,
                    field);
        }
        return value;
    }

    /** An optional list-of-strings field, for things like refunded order item ids. */
    public List<String> optionalStringList(String field) {
        Object value = payload.get(field);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of(value.toString());
    }

    /**
     * An optional list of nested objects, for an order's item breakdown. Entries that are not
     * objects are ignored rather than rejected: a partially understood payload is still worth acting
     * on, and the alternative is dead-lettering an event the wallet could mostly handle.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> optionalObjectList(String field) {
        Object value = payload.get(field);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(entry -> entry instanceof Map)
                .map(entry -> (Map<String, Object>) entry)
                .toList();
    }

    public boolean hasField(String field) {
        return payload.containsKey(field);
    }

    /** A payload builder, for assembling outgoing events readably. */
    public static Payload newPayload() {
        return new Payload();
    }

    /** Small builder so outbound payloads read as a list of facts rather than map plumbing. */
    public static final class Payload {

        private final Map<String, Object> values = new LinkedHashMap<>();

        private Payload() {
        }

        /** Adds the entry unless the value is null, keeping null fields out of the JSON entirely. */
        public Payload with(String key, Object value) {
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }

        public Map<String, Object> build() {
            return Map.copyOf(values);
        }
    }
}
