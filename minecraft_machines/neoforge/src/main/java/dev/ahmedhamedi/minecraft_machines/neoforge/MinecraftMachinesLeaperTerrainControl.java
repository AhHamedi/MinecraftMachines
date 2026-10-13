package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;

import java.util.Arrays;

/** Per-leg terrain adaptation around the saved bounded gait primitives. */
final class MinecraftMachinesLeaperTerrainControl {
    static final String VERSION = "leaper_perceptive_terrain_v5";
    private static final double[] THETA = {-55, 55, -125, 125};
    private final MinecraftMachinesLeaper rig;
    private final MinecraftMachinesLeaperTerrainMap map;
    private final MinecraftMachinesLeaperModel slow, fast, sprint;
    private final MinecraftMachinesLeaperTerrainParameters parameters;
    private final double[] contact = new double[4],
            departure = new double[4],
            landing = new double[4];
    private final boolean[] swinging = new boolean[4];
    private final double[][] shift = new double[4][2],
            fromShift = new double[4][2],
            toShift = new double[4][2];
    private boolean initialized, previousRough;
    private int entryTicks;
    boolean recovery;
    double travelX, travelZ;
    private double cycles, ground, roughHold;
    double roughness, peakRoughness, activeSpeed, minimumClearance = 99;
    int terrainTicks, sprintTicks, rejectedFootholds, attitudeReplants;
    String gaitName = "careful";

    void requestRecovery() {
        entryTicks = 40;
    }

    MinecraftMachinesLeaperTerrainControl(
            MinecraftMachinesLeaper rig, MinecraftMachinesLeaperTerrainMap map) {
        this(rig, map, MinecraftMachinesLeaperTerrainParameters.seed());
    }

    MinecraftMachinesLeaperTerrainControl(
            MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperTerrainMap map,
            MinecraftMachinesLeaperTerrainParameters parameters) {
        this.rig = rig;
        this.map = map;
        this.parameters = parameters;
        slow = MinecraftMachinesLeaperModel.load("careful");
        fast = MinecraftMachinesLeaperModel.load("fast");
        sprint = MinecraftMachinesLeaperModel.load("horrific");
    }

    void step(double forward, double strafe, double turn, String pace) {
        var center = rig.corePivot();
        double yaw = rig.heading(), c = Math.cos(yaw), s = Math.sin(yaw);
        double ceiling = center.y + 4;
        double[] sensed = new double[4];
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(THETA[i]);
            double x = 5.25 * Math.sin(a), z = -5.25 * Math.cos(a);
            var toe = rig.toe(i);
            sensed[i] = map.height(toe.x, toe.z, ceiling);
            if (!Double.isFinite(sensed[i])) sensed[i] = center.y - 2.2;
            for (int look = 0; look < 3; look++) {
                double lx = x + 3 * look * strafe, lz = z - 3 * look * forward;
                double h =
                        map.height(center.x + c * lx - s * lz, center.z + s * lx + c * lz, ceiling);
                if (!Double.isFinite(h)) {
                    min = Math.min(min, center.y - 6);
                    continue;
                }
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        roughness = max - min;
        peakRoughness = Math.max(peakRoughness, roughness);
        if (roughness > .2) roughHold = parameters.values[5];
        else roughHold = Math.max(0, roughHold - .05);
        boolean rough = roughHold > 0;
        if (rough && !previousRough) entryTicks = 40;
        // Replant before a developing tilt can become a fall. Uses measured chassis attitude.
        if (rough && rig.up().y < .95 && entryTicks == 0) {
            entryTicks = 40;
            attitudeReplants++;
        }
        previousRough = rough;
        boolean entering = entryTicks > 0;
        if (entering) {
            --entryTicks;
            forward = strafe = turn = 0;
            if (entryTicks == 0) cycles = 0;
            for (int i = 0; i < 4; i++) {
                contact[i] = departure[i] = landing[i] = sensed[i];
                swinging[i] = false;
                Arrays.fill(shift[i], 0);
                Arrays.fill(fromShift[i], 0);
                Arrays.fill(toShift[i], 0);
            }
        }
        var model =
                rough || pace.equals("careful") ? slow : pace.equals("horrific") ? sprint : fast;
        gaitName = entering ? "preparing" : rough ? "climbing" : pace;
        if (rough) terrainTicks++;
        else if (model.sprint()) sprintTicks++;
        // Slower support exchanges on ledges; motor strength is retained to lift the chassis.
        double cadence = rough ? parameters.values[0] : model.cadence();
        activeSpeed = cadence;
        if (!initialized) {
            ground = Arrays.stream(sensed).average().orElse(center.y - 2.2);
            for (int i = 0; i < 4; i++) contact[i] = departure[i] = landing[i] = sensed[i];
            initialized = true;
        }
        double desiredGround = Arrays.stream(sensed).average().orElse(ground);
        double centerGround = map.height(center.x, center.z, ceiling);
        if (Double.isFinite(centerGround))
            desiredGround = Math.max(desiredGround, centerGround - .8);
        ground +=
                Math.clamp(
                        desiredGround - ground,
                        -.05 * parameters.values[3],
                        .05 * parameters.values[3]);
        cycles += .05 * cadence / LeaperGait.period(model.gait(), model.sprint());
        double seconds = cycles * LeaperGait.period(model.gait(), model.sprint());
        var up = rig.up();
        double[] base =
                LeaperGait.action(
                        model.gait(),
                        seconds,
                        yaw,
                        up.x,
                        up.z,
                        forward,
                        strafe,
                        turn,
                        model.sprint());
        double duty =
                range(model.gait()[3], model.sprint() ? .40 : .65, model.sprint() ? .75 : .88);
        double radius = range(model.gait()[4], 4.6, 5.8);
        double stride = range(model.gait()[0], model.sprint() ? 1.8 : .5, model.sprint() ? 5 : 2.2);
        double activity =
                Math.clamp(4 * Math.max(Math.hypot(forward, strafe), Math.abs(turn)), 0, 1);
        for (int i = 0; i < 4; i++) {
            double phase =
                    cycles
                            + ((i % 2 == 1) ? range(model.gait()[6], .3, .7) : 0)
                            + (i >= 2 ? range(model.gait()[7], .1, .9) : 0);
            phase -= Math.floor(phase);
            boolean swing = phase >= duty && activity > .01;
            var foot = LeaperGait.forward(i, base[3 * i], base[3 * i + 1], base[3 * i + 2]);
            double a = Math.toRadians(THETA[i]),
                    nx = radius * Math.sin(a),
                    nz = -radius * Math.cos(a);
            if (swing && !swinging[i]) {
                departure[i] = contact[i];
                double lx = nx + .5 * stride * (strafe - turn * nz / radius);
                double lz = nz + .5 * stride * (-forward + turn * nx / radius);
                var velocity = rig.velocity();
                double duration =
                        (1 - duty) * LeaperGait.period(model.gait(), model.sprint()) / cadence;
                double wx =
                        center.x + c * lx - s * lz + velocity.x * duration * parameters.values[4];
                double wz =
                        center.z + s * lx + c * lz + velocity.z * duration * parameters.values[4];
                double h = Double.NaN, best = Double.POSITIVE_INFINITY;
                fromShift[i] = shift[i].clone();
                for (double dx : new double[] {0, -parameters.values[7], parameters.values[7]})
                    for (double dz :
                            new double[] {0, -parameters.values[7], parameters.values[7]}) {
                        double candidate = map.padHeight(wx + dx, wz + dz, ceiling);
                        double cost = dx * dx + dz * dz + .04 * Math.abs(candidate - contact[i]);
                        if (Double.isFinite(candidate)
                                && Math.abs(candidate - contact[i]) <= 1.51
                                && cost < best) {
                            best = cost;
                            h = candidate;
                            toShift[i][0] = c * dx + s * dz;
                            toShift[i][1] = -s * dx + c * dz;
                        }
                    }
                if (!Double.isFinite(h)) {
                    h = contact[i];
                    rejectedFootholds++;
                    toShift[i] = fromShift[i].clone();
                }
                landing[i] = Double.isFinite(h) ? h : contact[i];
            }
            double h = contact[i];
            if (swing) {
                double t = (phase - duty) / (1 - duty), smooth = t * t * (3 - 2 * t);
                h = departure[i] + (landing[i] - departure[i]) * smooth;
                if (rough)
                    foot[1] +=
                            (parameters.values[1]
                                            + parameters.values[2]
                                                    * Math.abs(landing[i] - departure[i]))
                                    * activity
                                    * Math.sin(Math.PI * t);
                for (int j = 0; j < 2; j++)
                    shift[i][j] = fromShift[i][j] + (toShift[i][j] - fromShift[i][j]) * smooth;
            } else if (swinging[i]) {
                contact[i] = h = landing[i];
                shift[i] = toShift[i].clone();
            }
            swinging[i] = swing;
            if (rough || Math.abs(h - ground) > .05) foot[1] += h - ground;
            if (rough) foot[1] -= parameters.values[6];
            foot[0] += shift[i][0];
            foot[2] += shift[i][1];
            var q = LeaperGait.inverse(i, foot[0], foot[1], foot[2]);
            base[3 * i] = q[0];
            base[3 * i + 1] = q[1];
            base[3 * i + 2] = q[2];
        }
        minimumClearance =
                Math.min(minimumClearance, center.y - map.height(center.x, center.z, ceiling));
        rig.command(base, .05, rough ? 2.2 : model.slew(), rough ? 2 : model.power());
    }

    private static double range(double p, double a, double b) {
        return a + (b - a) * (.5 + .5 * Math.tanh(p));
    }
}
