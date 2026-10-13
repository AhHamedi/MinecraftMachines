package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedDirectionalGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.QuadrupedPointController;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

record MinecraftMachinesQuadrupedNavigationModel(double[] gait, double[] navigation) {
    static MinecraftMachinesQuadrupedNavigationModel candidate() throws Exception {
        return parse(
                JsonParser.parseString(
                                Files.readString(Path.of("quadruped_navigation/candidate.json")))
                        .getAsJsonObject(),
                false);
    }

    static MinecraftMachinesQuadrupedNavigationModel packaged() throws Exception {
        try (var stream =
                MinecraftMachinesQuadrupedNavigationModel.class.getResourceAsStream(
                        "/data/minecraft_machines/quadruped/navigation.json")) {
            if (stream == null)
                throw new IllegalStateException("No qualified navigation model is installed");
            return parse(
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject(),
                    true);
        }
    }

    private static MinecraftMachinesQuadrupedNavigationModel parse(
            JsonObject json, boolean requireEvaluation) {
        if (!json.get("morphology").getAsString().equals(MinecraftMachinesQuadruped.CONTRACT)
                || !json.get("gait_policy").getAsString().equals(QuadrupedDirectionalGait.VERSION)
                || !json.get("navigation_policy")
                        .getAsString()
                        .equals(QuadrupedPointController.VERSION)
                || !json.get("training_complete").getAsBoolean())
            throw new IllegalStateException("Navigation model contract mismatch");
        if (requireEvaluation) {
            if (!json.get("validated").getAsBoolean())
                throw new IllegalStateException("Navigation model has not passed qualification");
            var qualification = json.getAsJsonObject("qualification");
            if (!qualification.get("policy").getAsString().equals(QuadrupedPointController.VERSION))
                throw new IllegalStateException("Qualification policy mismatch");
            if (!java.util.Arrays.equals(
                            array(json, "navigation_genome"),
                            array(qualification, "navigation_genome"))
                    || !java.util.Arrays.equals(
                            array(json, "gait_genome"), array(qualification, "gait_genome")))
                throw new IllegalStateException(
                        "Qualification does not match the installed parameters");
            var trials = qualification.getAsJsonArray("trials");
            if (trials.size() != 80)
                throw new IllegalStateException("Navigation model is missing qualification trials");
            for (var entry : trials) {
                var t = entry.getAsJsonObject();
                double error = t.get("final_error_blocks").getAsDouble(),
                        up = t.get("minimum_up_dot").getAsDouble(),
                        hold = t.get("maximum_hold_error_blocks").getAsDouble();
                if (!t.get("success").getAsBoolean()
                        || !Double.isFinite(error + up + hold)
                        || error > .75
                        || hold > .75
                        || up < .9)
                    throw new IllegalStateException(
                            "Navigation model failed point-arrival qualification");
            }
            var routes = json.getAsJsonObject("route_qualification");
            if (routes.get("accepted").getAsInt() != 4
                    || routes.getAsJsonArray("routes").size() != 4)
                throw new IllegalStateException("Navigation model is missing route qualification");
            for (var route : routes.getAsJsonArray("routes"))
                if (!route.getAsJsonObject().get("success").getAsBoolean())
                    throw new IllegalStateException("Navigation model failed route qualification");
        }
        return new MinecraftMachinesQuadrupedNavigationModel(
                array(json, "gait_genome"), array(json, "navigation_genome"));
    }

    private static double[] array(JsonObject json, String key) {
        var values = json.getAsJsonArray(key);
        if (values.size() != 10) throw new IllegalStateException("Invalid " + key);
        double[] result = new double[10];
        for (int i = 0; i < 10; i++) {
            result[i] = values.get(i).getAsDouble();
            if (!Double.isFinite(result[i])) throw new IllegalStateException("Nonfinite " + key);
        }
        return result;
    }
}
