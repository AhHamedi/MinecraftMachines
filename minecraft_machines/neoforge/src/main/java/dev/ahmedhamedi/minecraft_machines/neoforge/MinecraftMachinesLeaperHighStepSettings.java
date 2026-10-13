package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.*;

/** Bind qualified packaged settings, or an explicitly enabled development candidate. */
final class MinecraftMachinesLeaperHighStepSettings {
    private static JsonObject cached;

    static JsonObject read() {
        if (cached != null) return cached.deepCopy();
        try {
            var path =
                    Path.of(
                            "leaper_learning",
                            MinecraftMachinesLeaperRuntime.reportName("predator-highstep-settings")
                                    + ".json");
            cached = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            return cached.deepCopy();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read explicit high-step candidate settings", e);
        }
    }

    static void applyPreview(MinecraftMachinesLeaperPredatorControl c) {
        if (c.policy.packagedHighStepSettings != null)
            apply(c, c.policy.packagedHighStepSettings);
        if (!Boolean.getBoolean("minecraft_machines.leaperHighStepCandidate")) return;
        if (MinecraftMachinesLeaperRuntime.mode().equals("packaged"))
            throw new IllegalStateException(
                    "High-step candidate needs an explicit evaluation or preview mode");
        apply(c, read());
    }

    static java.util.Map<String, Object> effective(MinecraftMachinesLeaperPredatorControl c) {
        var r = new java.util.LinkedHashMap<String, Object>();
        r.put("extra_lift", c.extraLift);
        r.put("shoulder_minimum", c.shoulderMinimum);
        r.put("shoulder_maximum", c.shoulderReach);
        r.put("knee_maximum", c.kneeReach);
        r.put("terrain_aware_sweep", c.terrainAwareSweep);
        r.put("adaptive_lift", c.adaptiveLift);
        r.put("world_swing", c.worldSwing);
        r.put("post_clearance_lift", c.postClearanceLift);
        r.put("pace_lift", c.paceLift);
        r.put("slow_damping", c.slowDamping);
        r.put("fresh_support", c.freshSupport);
        r.put("flat_cadence", c.flatCadence);
        r.put("clear_ground_gain", c.clearGroundGain);
        r.put("clearance_pause", c.clearancePause);
        r.put("exposed_surfaces", c.map.exposedSurfaces);
        r.put("adaptive_footholds", c.adaptiveFootholds);
        r.put("world_swing_height", c.worldSwingHeight);
        r.put("stance_compression", c.stanceCompression);
        return r;
    }

    private static void apply(MinecraftMachinesLeaperPredatorControl c, JsonObject data) {
        c.extraLift = data.get("extra_lift").getAsDouble();
        if (!Double.isFinite(c.extraLift) || c.extraLift < 0 || c.extraLift > 3)
            throw new IllegalArgumentException("Invalid candidate lift");
        c.shoulderMinimum =
                data.has("shoulder_minimum") ? data.get("shoulder_minimum").getAsDouble() : -1.05;
        if (!Double.isFinite(c.shoulderMinimum)
                || c.shoulderMinimum < -1.6
                || c.shoulderMinimum > -1.05)
            throw new IllegalArgumentException("Invalid shoulder minimum");
        c.shoulderReach = data.get("shoulder_maximum").getAsDouble();
        c.kneeReach = data.get("knee_maximum").getAsDouble();
        c.terrainAwareSweep = data.get("terrain_aware_sweep").getAsBoolean();
        c.adaptiveLift = data.get("adaptive_lift").getAsBoolean();
        c.map.exposedSurfaces =
                data.has("exposed_surfaces") && data.get("exposed_surfaces").getAsBoolean();
        c.freshSupport = data.has("fresh_support") && data.get("fresh_support").getAsBoolean();
        c.worldSwing = data.get("world_swing").getAsBoolean();
        c.worldSwingHeight =
                data.has("world_swing_height") && data.get("world_swing_height").getAsBoolean();
        c.adaptiveFootholds =
                data.has("adaptive_footholds") && data.get("adaptive_footholds").getAsBoolean();
        c.postClearanceLift =
                data.has("post_clearance_lift") && data.get("post_clearance_lift").getAsBoolean();
        c.paceLift = data.has("pace_lift") && data.get("pace_lift").getAsBoolean();
        c.slowDamping = data.has("slow_damping") ? data.get("slow_damping").getAsDouble() : 0;
        if (!Double.isFinite(c.slowDamping) || c.slowDamping < 0 || c.slowDamping > .15)
            throw new IllegalArgumentException("Invalid slow-pace damping");
        c.flatCadence = data.has("flat_cadence") ? data.get("flat_cadence").getAsDouble() : 0;
        if (!Double.isFinite(c.flatCadence) || c.flatCadence < 0 || c.flatCadence > .4)
            throw new IllegalArgumentException("Invalid clear-ground cadence gain");
        c.clearGroundGain =
                data.has("clear_ground_gain") ? data.get("clear_ground_gain").getAsDouble() : 0;
        if (!Double.isFinite(c.clearGroundGain) || c.clearGroundGain < 0 || c.clearGroundGain > 1)
            throw new IllegalArgumentException("Invalid clear-ground recovery gain");
        c.clearancePause =
                data.has("clearance_pause") ? data.get("clearance_pause").getAsDouble() : 0;
        if (!Double.isFinite(c.clearancePause) || c.clearancePause < 0 || c.clearancePause > .6)
            throw new IllegalArgumentException("Invalid measured-clearance wait budget");
        c.stanceCompression =
                data.has("stance_compression") ? data.get("stance_compression").getAsDouble() : 0;
        if (!Double.isFinite(c.stanceCompression)
                || c.stanceCompression < 0
                || c.stanceCompression > .6)
            throw new IllegalArgumentException("Invalid slope stance compression");
        c.rig.predatorReach(c.shoulderMinimum, c.shoulderReach, c.kneeReach);
    }
}
