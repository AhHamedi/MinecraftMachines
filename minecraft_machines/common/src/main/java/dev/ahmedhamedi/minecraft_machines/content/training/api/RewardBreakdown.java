package dev.ahmedhamedi.minecraft_machines.content.training.api;

import java.util.LinkedHashMap;
import java.util.Map;

public record RewardBreakdown(
        double total,
        Map<String, Double> components
) {
    public RewardBreakdown {
        if (!Double.isFinite(total)) {
            throw new IllegalArgumentException("total must be finite");
        }
        final Map<String, Double> copied = new LinkedHashMap<>();
        components.forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("component names must not be blank");
            }
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("component values must be finite");
            }
            copied.put(name, value);
        });
        components = Map.copyOf(copied);
    }
}
