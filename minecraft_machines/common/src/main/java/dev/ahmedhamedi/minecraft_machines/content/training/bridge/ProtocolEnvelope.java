package dev.ahmedhamedi.minecraft_machines.content.training.bridge;

import com.google.gson.JsonObject;

public record ProtocolEnvelope(
        BridgeMessageType type,
        String requestId,
        JsonObject payload
) {
    public ProtocolEnvelope {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        payload = payload == null ? new JsonObject() : payload.deepCopy();
    }

    @Override
    public JsonObject payload() {
        return this.payload.deepCopy();
    }
}
