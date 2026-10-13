package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonParser;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperPartBlock;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPointController;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

record MinecraftMachinesLeaperModel(
        double[] gait,
        double[] navigation,
        double cadence,
        double slew,
        double power,
        boolean sprint) {
    MinecraftMachinesLeaperModel(double[] gait, double[] navigation) {
        this(gait, navigation, 1, .6, 1, false);
    }

    MinecraftMachinesLeaperModel(double[] gait, double[] navigation, double cadence, double slew) {
        this(gait, navigation, cadence, slew, 1, false);
    }

    MinecraftMachinesLeaperModel(
            double[] gait, double[] navigation, double cadence, double slew, double power) {
        this(gait, navigation, cadence, slew, power, false);
    }

    double minimumUp() {
        return sprint ? .90 : .96;
    }

    MinecraftMachinesLeaperModel {
        if (gait.length != 10
                || navigation.length != 10
                || !Double.isFinite(cadence)
                || cadence < .5
                || cadence > 12
                || !Double.isFinite(slew)
                || slew < .6
                || slew > 8
                || !Double.isFinite(power)
                || power < 1
                || power > 4)
            throw new IllegalArgumentException("Invalid Leaper gait or actuator envelope");
        for (double[] array : new double[][] {gait, navigation})
            for (double value : array)
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite model");
        gait = gait.clone();
        navigation = navigation.clone();
    }

    double[] action(
            double seconds,
            double heading,
            double upX,
            double upZ,
            double forward,
            double strafe,
            double turn) {
        return LeaperGait.action(
                gait, seconds * cadence, heading, upX, upZ, forward, strafe, turn, sprint);
    }

    static MinecraftMachinesLeaperModel load() {
        return load("fast");
    }

    static MinecraftMachinesLeaperModel load(String pace) {
        if (!pace.equals("careful") && !pace.equals("fast") && !pace.equals("horrific"))
            throw new IllegalArgumentException("Unknown Leaper pace: " + pace);
        String filename = pace.equals("careful") ? "navigation.json" : pace + "_navigation.json";
        try (var stream =
                MinecraftMachinesLeaperModel.class.getResourceAsStream(
                        "/data/minecraft_machines/leaper/" + filename)) {
            if (stream == null)
                throw new IllegalStateException(
                        "No qualified Leaper navigation model is installed");
            var data =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!data.get("validated").getAsBoolean()
                    || !data.get("contract").getAsString().equals(MinecraftMachinesLeaper.CONTRACT)
                    || !data.get("gait_policy")
                            .getAsString()
                            .equals(
                                    pace.equals("horrific")
                                            ? LeaperGait.SPRINT_VERSION
                                            : LeaperGait.VERSION)
                    || !data.get("navigation_policy")
                            .getAsString()
                            .equals(LeaperPointController.VERSION))
                throw new IllegalStateException("Leaper model does not match this rig");
            double friction =
                    PhysicsBlockPropertyHelper.getFriction(
                            MinecraftMachinesBlocks.LEAPER_PART
                                    .getDefaultState()
                                    .setValue(LeaperPartBlock.PART, 43));
            if (Math.abs(friction - data.get("foot_friction").getAsDouble()) > 1e-6)
                throw new IllegalStateException(
                        "Leaper foot friction differs from its qualified model");
            try (var geometry =
                    MinecraftMachinesLeaperModel.class.getResourceAsStream(
                            "/data/minecraft_machines/leaper/geometry.json")) {
                if (geometry == null) throw new IllegalStateException("Missing Leaper geometry");
                String digest =
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(geometry.readAllBytes()));
                if (!digest.equals(data.get("geometry_sha256").getAsString()))
                    throw new IllegalStateException(
                            "Leaper collision geometry differs from its qualified model");
            }
            double[][] arrays = new double[2][10];
            String[] keys = {"gait", "navigation"};
            for (int a = 0; a < 2; a++) {
                var values = data.getAsJsonArray(keys[a]);
                if (values.size() != 10) throw new IllegalStateException("Malformed Leaper model");
                for (int i = 0; i < 10; i++) {
                    arrays[a][i] = values.get(i).getAsDouble();
                    if (!Double.isFinite(arrays[a][i]))
                        throw new IllegalStateException("Nonfinite model");
                }
            }
            double cadence =
                    data.has("cadence_multiplier")
                            ? data.get("cadence_multiplier").getAsDouble()
                            : 1;
            double slew =
                    data.has("joint_slew_rad_s") ? data.get("joint_slew_rad_s").getAsDouble() : .6;
            double power =
                    data.has("motor_power_multiplier")
                            ? data.get("motor_power_multiplier").getAsDouble()
                            : 1;
            return new MinecraftMachinesLeaperModel(
                    arrays[0], arrays[1], cadence, slew, power, pace.equals("horrific"));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read Leaper model", e);
        }
    }
}
