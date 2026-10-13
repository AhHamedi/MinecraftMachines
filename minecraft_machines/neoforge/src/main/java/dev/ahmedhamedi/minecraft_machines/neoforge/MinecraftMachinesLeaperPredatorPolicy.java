package dev.ahmedhamedi.minecraft_machines.neoforge;

import java.util.*;

/** Bounded, continuously terrain-conditioned foot-trajectory policy optimized in native physics. */
final class MinecraftMachinesLeaperPredatorPolicy {
    static final String VERSION = "leaper_predator_highstep_v11";
    static final String LEGACY_VERSION = "leaper_predator_clearance_v10";
    static final double[] LOW = {
        2.8, .75, .52, 4.5, 2.1, .7, .45, .38, .1, 2, 0, 0, -.15, 0, 0, .2, 2, 3.5, 0, 0
    };
    static final double[] HIGH = {
        5.2, 1.5, .80, 5.8, 3.5, 2.4, .55, .62, 1.5, 6, 1, 1, .3, .9, 1, 1.4, 8, 7.5, .4, 1
    };
    static final String[] NAMES = {
        "stride",
        "period",
        "duty",
        "radius",
        "body_height",
        "swing_lift",
        "phase_lr",
        "phase_fb",
        "step_clearance",
        "height_tracking",
        "pitch_alignment",
        "roll_alignment",
        "vertical_damping",
        "landing_prediction",
        "stance_feedback",
        "foothold_radius",
        "motor_power",
        "motor_slew",
        "stride_adaptation",
        "swing_feedback"
    };
    static final int COUNT = LOW.length;
    final double[] genome, value;
    com.google.gson.JsonObject packagedHighStepSettings;

    MinecraftMachinesLeaperPredatorPolicy(double[] p) {
        if (p.length != COUNT) throw new IllegalArgumentException("Predator policy dimension");
        genome = p.clone();
        value = new double[COUNT];
        for (int i = 0; i < COUNT; i++) {
            if (!Double.isFinite(p[i])) throw new IllegalArgumentException("Nonfinite policy");
            value[i] = LOW[i] + (HIGH[i] - LOW[i]) * (.5 + .5 * Math.tanh(p[i]));
        }
    }

    static MinecraftMachinesLeaperPredatorPolicy seed() {
        return physical(
                new double[] {
                    3.6, 1.15, .66, 5.2, 2.65, 1.2, .52, .55, .6, 4, .5, .5, .05, .4, .6, .8, 4, 5,
                    .1, .3
                });
    }

    static MinecraftMachinesLeaperPredatorPolicy physical(double[] v) {
        if (v.length != COUNT) throw new IllegalArgumentException("Predator policy dimension");
        double[] p = new double[COUNT];
        for (int i = 0; i < COUNT; i++) {
            if (!Double.isFinite(v[i]))
                throw new IllegalArgumentException("Nonfinite physical parameter");
            double x = Math.clamp(2 * (v[i] - LOW[i]) / (HIGH[i] - LOW[i]) - 1, -.9999, .9999);
            p[i] = .5 * Math.log((1 + x) / (1 - x));
        }
        return new MinecraftMachinesLeaperPredatorPolicy(p);
    }

    Map<String, Double> named() {
        var out = new LinkedHashMap<String, Double>();
        for (int i = 0; i < COUNT; i++) out.put(NAMES[i], value[i]);
        return out;
    }

    static MinecraftMachinesLeaperPredatorPolicy load() {
        if (MinecraftMachinesLeaperRuntime.mode().equals("bundled"))
            return MinecraftMachinesLeaperRuntime.bundledPolicy();
        if (MinecraftMachinesLeaperRuntime.mode().equals("predator-preview")) {
            try {
                return MinecraftMachinesLeaperRuntime.previewPolicy();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load preview candidate", e);
            }
        }
        try (var in =
                MinecraftMachinesLeaperPredatorPolicy.class.getResourceAsStream(
                        "/data/minecraft_machines/leaper/predator.json")) {
            if (in == null)
                throw new IllegalStateException("No qualified predator model is installed");
            var data =
                    com.google.gson.JsonParser.parseString(
                                    new String(
                                            in.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!data.get("validated").getAsBoolean()
                    || !Set.of(VERSION, LEGACY_VERSION).contains(data.get("policy").getAsString())
                    || !MinecraftMachinesLeaper.PREDATOR_CONTRACT.equals(
                            data.get("contract").getAsString()))
                throw new IllegalStateException("Predator model contract mismatch");
            if (data.get("balance_gain").getAsDouble() != 1
                    || !data.get("contact_grip").getAsBoolean()
                    || data.get("physics_substeps").getAsInt() != 4)
                throw new IllegalStateException("Predator actuator contract mismatch");
            if (Double.parseDouble(System.getProperty("minecraft_machines.leaperBalance", "1")) != 1
                    || Integer.getInteger("minecraft_machines.leaperSubsteps", 4) != 4
                    || !Boolean.parseBoolean(
                            System.getProperty("minecraft_machines.leaperGrip", "true")))
                throw new IllegalStateException(
                        "Packaged predator requires its qualified actuator settings");
            var bindings = data.getAsJsonObject("resource_sha256");
            var expected =
                    Set.of(
                            "geometry.json",
                            "collision_geometry.json",
                            "navigation.json",
                            "fast_navigation.json",
                            "horrific_navigation.json");
            if (!bindings.keySet().equals(expected))
                throw new IllegalStateException("Predator resource bindings are incomplete");
            for (var name : expected)
                try (var resource =
                        MinecraftMachinesLeaperPredatorPolicy.class.getResourceAsStream(
                                "/data/minecraft_machines/leaper/" + name)) {
                    if (resource == null
                            || !HexFormat.of()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("SHA-256")
                                                    .digest(resource.readAllBytes()))
                                    .equals(bindings.get(name).getAsString()))
                        throw new IllegalStateException(
                                "Predator requires its qualified geometry and navigation");
                }
            // Also validates the actual Sable foot friction and geometry contract.
            MinecraftMachinesLeaperModel.load("horrific");
            var a = data.getAsJsonArray("genome");
            double[] genome = new double[a.size()];
            for (int i = 0; i < a.size(); i++) genome[i] = a.get(i).getAsDouble();
            var result = new MinecraftMachinesLeaperPredatorPolicy(genome);
            if (VERSION.equals(data.get("policy").getAsString())) {
                if (!data.has("highstep_settings") || !data.get("highstep_settings").isJsonObject())
                    throw new IllegalStateException(
                            "High-step model requires qualified gait settings");
                result.packagedHighStepSettings =
                        data.getAsJsonObject("highstep_settings").deepCopy();
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load predator model", e);
        }
    }
}
