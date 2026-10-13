package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Gameplay configuration shared by the standalone mod and development client. */
final class MinecraftMachinesLeaperRuntime {
    private static final String RESOURCE = "/data/minecraft_machines/leaper/gameplay.json";

    static boolean bundled() {
        return MinecraftMachinesLeaperRuntime.class.getResource(RESOURCE) != null;
    }

    static String mode() {
        return System.getProperty("minecraft_machines.leaperMode", bundled() ? "bundled" : "packaged");
    }

    static boolean motionEnabled(String name) {
        return Boolean.parseBoolean(System.getProperty("minecraft_machines." + name,
                Boolean.toString(bundled())));
    }

    static String reportName(String name) {
        String profile = System.getProperty("minecraft_machines.leaperProfile", bundled() ? "horrific" : "careful");
        return profile.equals("careful") ? name : profile + "-" + name;
    }

    static MinecraftMachinesLeaperPredatorPolicy bundledPolicy() {
        try (var in = MinecraftMachinesLeaperRuntime.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Missing bundled Leaper movement settings");
            JsonObject data = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var result = policy(data);
            result.packagedHighStepSettings = data.getAsJsonObject("highstep_settings").deepCopy();
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read bundled Leaper movement settings", e);
        }
    }

    static MinecraftMachinesLeaperPredatorPolicy previewPolicy() throws java.io.IOException {
        var path = Path.of("leaper_learning", reportName("predator-candidate") + ".json");
        if (!Files.exists(path)) return bundled() ? bundledPolicy() : MinecraftMachinesLeaperPredatorPolicy.seed();
        return policy(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
    }

    private static MinecraftMachinesLeaperPredatorPolicy policy(JsonObject data) {
        var values = data.getAsJsonArray("genome");
        double[] genome = new double[values.size()];
        for (int i = 0; i < genome.length; i++) genome[i] = values.get(i).getAsDouble();
        return new MinecraftMachinesLeaperPredatorPolicy(genome);
    }
}
