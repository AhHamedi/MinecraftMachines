package dev.ahmedhamedi.minecraft_machines.content.training.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class TrainingBridgeProtocol {
    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private TrainingBridgeProtocol() {
    }

    public static String encodeEnvelope(final ProtocolEnvelope envelope) {
        return GSON.toJson(envelope);
    }

    public static ProtocolEnvelope decodeEnvelope(final String json) {
        final JsonElement root = JsonParser.parseString(json);
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("protocol envelope must be a JSON object");
        }
        final JsonObject object = root.getAsJsonObject();
        final BridgeMessageType type = parseType(requireString(object, "type"));
        final String requestId = requireString(object, "requestId");
        final JsonObject payload;
        if (object.has("payload") && !object.get("payload").isJsonNull()) {
            final JsonElement payloadElement = object.get("payload");
            if (!payloadElement.isJsonObject()) {
                throw new IllegalArgumentException("payload must be a JSON object");
            }
            payload = payloadElement.getAsJsonObject();
        } else {
            payload = new JsonObject();
        }
        return new ProtocolEnvelope(type, requestId, payload);
    }

    public static JsonObject toJsonTree(final Object value) {
        return GSON.toJsonTree(value).getAsJsonObject();
    }

    public static <T> T fromJsonTree(final JsonObject object, final Class<T> type) {
        return GSON.fromJson(object, type);
    }

    public static byte[] encodeLengthPrefixed(final String json, final int maximumMessageBytes) {
        final byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        if (payload.length > maximumMessageBytes) {
            throw new IllegalArgumentException("message exceeds maximum size");
        }
        final ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + payload.length);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    public static String decodeLengthPrefixed(final byte[] message, final int maximumMessageBytes) {
        if (message.length < Integer.BYTES) {
            throw new IllegalArgumentException("message is shorter than length prefix");
        }
        final ByteBuffer buffer = ByteBuffer.wrap(message);
        final int length = buffer.getInt();
        if (length < 0 || length > maximumMessageBytes || length != buffer.remaining()) {
            throw new IllegalArgumentException("invalid message length");
        }
        final byte[] payload = new byte[length];
        buffer.get(payload);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static String requireString(final JsonObject object, final String name) {
        final JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        final String value = element.getAsString();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static BridgeMessageType parseType(final String typeName) {
        try {
            return BridgeMessageType.valueOf(typeName);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported bridge message type " + typeName, e);
        }
    }
}
