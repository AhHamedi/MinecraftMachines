package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record DuopodEvaluationManifest(
        String id,
        int version,
        List<DuopodEvaluationScenario> scenarios
) {
    public DuopodEvaluationManifest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("scenarios must not be empty");
        }
        final Set<String> seen = new HashSet<>();
        for (final DuopodEvaluationScenario scenario : scenarios) {
            if (!seen.add(scenario.id())) {
                throw new IllegalArgumentException("duplicate scenario id " + scenario.id());
            }
        }
    }

    public String compatibilityHash() {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, this.id);
            update(digest, Integer.toString(this.version));
            for (final DuopodEvaluationScenario scenario : this.scenarios) {
                update(digest, scenario.id());
                update(digest, format(scenario.targetBearingDegrees()));
                update(digest, format(scenario.targetDistanceBlocks()));
                update(digest, format(scenario.initialYawDegrees()));
                update(digest, scenario.terrainStage());
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

    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
