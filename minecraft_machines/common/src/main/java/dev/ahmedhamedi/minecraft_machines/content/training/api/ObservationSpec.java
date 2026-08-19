package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record ObservationSpec(
        String schemaId,
        int schemaVersion,
        List<VectorFieldSpec> fields
) {
    public ObservationSpec {
        schemaId = requireText("schemaId", schemaId);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("fields must not be empty");
        }
    }

    public int size() {
        return this.fields.size();
    }

    public String compatibilityHash() {
        return compatibilityHash("observation", this.schemaId, this.schemaVersion, this.fields);
    }

    static String compatibilityHash(final String kind, final String schemaId, final int schemaVersion, final List<VectorFieldSpec> fields) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, kind);
            update(digest, schemaId);
            update(digest, Integer.toString(schemaVersion));
            update(digest, Integer.toString(fields.size()));
            for (final VectorFieldSpec field : fields) {
                update(digest, field.compatibilityToken());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void update(final MessageDigest digest, final String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String requireText(final String name, final String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
