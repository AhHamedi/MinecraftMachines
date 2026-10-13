package dev.ahmedhamedi.minecraft_machines.neoforge;

import java.util.*;

/** Bounded terrain-control search parameters. The three saved flat gaits stay unchanged. */
final class MinecraftMachinesLeaperTerrainParameters {
    static final int COUNT = 8;
    private static final double[] LOW = {1.0, .3, .1, .35, .0, 1.5, .05, .0};
    private static final double[] HIGH = {2.8, 1.0, .8, 1.8, .9, 5.0, .65, .6};
    static final String[] NAMES = {
        "climb_cadence",
        "swing_clearance",
        "step_clearance_gain",
        "height_tracking_rate",
        "landing_prediction",
        "rough_hold_seconds",
        "body_lift",
        "foothold_search_radius"
    };
    final double[] genome, values;

    MinecraftMachinesLeaperTerrainParameters(double[] genome) {
        if (genome.length != COUNT) throw new IllegalArgumentException("Terrain parameter count");
        this.genome = genome.clone();
        values = new double[COUNT];
        for (int i = 0; i < COUNT; i++) {
            if (!Double.isFinite(genome[i]))
                throw new IllegalArgumentException("Nonfinite terrain parameter");
            values[i] = LOW[i] + (HIGH[i] - LOW[i]) * (.5 + .5 * Math.tanh(genome[i]));
        }
    }

    static MinecraftMachinesLeaperTerrainParameters seed() {
        double[] v = {1.5, .65, .4, 1.0, .5, 3, .3, .4}, p = new double[COUNT];
        for (int i = 0; i < COUNT; i++) {
            double t = 2 * (v[i] - LOW[i]) / (HIGH[i] - LOW[i]) - 1;
            p[i] = .5 * Math.log((1 + t) / (1 - t));
        }
        return new MinecraftMachinesLeaperTerrainParameters(p);
    }

    Map<String, Double> named() {
        var m = new LinkedHashMap<String, Double>();
        for (int i = 0; i < COUNT; i++) m.put(NAMES[i], values[i]);
        return m;
    }

    static MinecraftMachinesLeaperTerrainParameters load() {
        try (var in =
                MinecraftMachinesLeaperTerrainParameters.class.getResourceAsStream(
                        "/data/minecraft_machines/leaper/terrain.json")) {
            if (in == null)
                throw new IllegalStateException("No qualified terrain model is installed");
            var data =
                    com.google.gson.JsonParser.parseString(
                                    new String(
                                            in.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!data.get("validated").getAsBoolean()
                    || !data.get("policy")
                            .getAsString()
                            .equals(MinecraftMachinesLeaperTerrainControl.VERSION)
                    || !data.get("contract").getAsString().equals(MinecraftMachinesLeaper.CONTRACT))
                throw new IllegalStateException("Terrain model contract mismatch");
            for (var entry : data.getAsJsonObject("resource_sha256").entrySet()) {
                if (!Set.of(
                                "geometry.json",
                                "navigation.json",
                                "fast_navigation.json",
                                "horrific_navigation.json")
                        .contains(entry.getKey()))
                    throw new IllegalStateException("Unexpected terrain resource binding");
                try (var resource =
                        MinecraftMachinesLeaperTerrainParameters.class.getResourceAsStream(
                                "/data/minecraft_machines/leaper/" + entry.getKey())) {
                    if (resource == null
                            || !HexFormat.of()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("SHA-256")
                                                    .digest(resource.readAllBytes()))
                                    .equals(entry.getValue().getAsString()))
                        throw new IllegalStateException(
                                "Terrain model requires its qualified geometry and gaits");
                }
            }
            if (data.getAsJsonObject("resource_sha256").size() != 4)
                throw new IllegalStateException("Incomplete terrain resource binding");
            var a = data.getAsJsonArray("genome");
            double[] p = new double[a.size()];
            for (int i = 0; i < a.size(); i++) p[i] = a.get(i).getAsDouble();
            return new MinecraftMachinesLeaperTerrainParameters(p);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load terrain model", e);
        }
    }
}
