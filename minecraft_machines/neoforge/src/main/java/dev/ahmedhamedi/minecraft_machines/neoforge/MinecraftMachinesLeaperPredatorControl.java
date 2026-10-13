package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperClearanceClock;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperSwingClearance;

import org.joml.Vector3d;

import java.util.*;

/**
 * One continuously running gait. Terrain changes footholds and clearance, without crawl switches.
 */
final class MinecraftMachinesLeaperPredatorControl {
    static final double[] THETA = {-55, 55, -125, 125};
    final MinecraftMachinesLeaper rig;
    final MinecraftMachinesLeaperTerrainMap map;
    final MinecraftMachinesLeaperPredatorPolicy policy;
    final double[] contact = new double[4],
            departure = new double[4],
            landing = new double[4],
            obstacle = new double[4];
    final double[][] shift = new double[4][2], from = new double[4][2], to = new double[4][2];
    final boolean[] swingBefore = new boolean[4];
    boolean initialized;
    boolean holding;
    final double[][] holdFeet = new double[4][3];
    final double[] holdAngles = new double[12];
    final int[] holdLoweringTicks = new int[4];
    int holdTicks;
    final double[][] resumeFeet = new double[4][3], poseFeet = new double[4][3];
    final double[] resumeAngles = new double[12], poseAngles = new double[12];
    static final int RESUME_TICKS = 8;
    int resumeTicks = RESUME_TICKS, poseTicks, poseDuration;
    double resumeStandingHeight;
    double shockwaveCompression;
    private boolean inLeapPose, wasPouncing;
    private boolean terrainLandingPose;
    private double[][] landingFeet;
    private double landingHeading;
    double holdX, holdZ, previousX, previousZ, measuredX, measuredZ;
    boolean measured;
    double cycles, ground, roughness;
    double gradientX, gradientZ;
    int rejectedFootholds;
    // Package-private switches are used only by the explicit native ablation harness.
    boolean earlyClearance = true, coordinatedSwing = true;
    double extraLift,
            highStep,
            slowDamping,
            flatCadence,
            clearGroundGain,
            clearancePause,
            stanceCompression;
    final LeaperClearanceClock clearanceClock = new LeaperClearanceClock();
    double clearGroundBlend;
    boolean clearGroundMeasured;
    double liftLead = .20;
    double shoulderMinimum = -1.05,
            shoulderReach = 1.05,
            kneeReach = 1.2,
            ascentRateScale = 1,
            bodyLookaheadRise = 3.6;
    boolean adaptiveLift, terrainAwareSweep, freshSupport;
    boolean postClearanceLift;
    boolean paceLift;
    boolean worldSwing, adaptiveFootholds, worldSwingHeight;
    final double[] commandedToeHeight = new double[4], boundedToeHeight = new double[4];
    final double[] rawRequestedAngles = new double[12];
    final boolean agile = MinecraftMachinesLeaperRuntime.motionEnabled("leaperAgile");
    final Vector3d[] swingFrom = new Vector3d[4], swingTo = new Vector3d[4];
    int clearanceSwings, shoulderClamps;
    private boolean superHorrific;
    double superDrive;
    double turnDrive;
    boolean sprintAllowed = true;
    boolean apex;
    double apexTargetSpeed;
    double commandedPeriod, commandedStride, commandedPower, commandedSlew;

    // Super selects finite native actuator limits (20 rad/s, power16). Ordinary paces
    // retain 8/8; gait drive still ramps down near a destination and on rough ground.
    void setPace(String pace) {
        switch (pace) {
            case "super_horrific" -> superHorrific = true;
            case "careful", "fast", "standard", "horrific" -> {
                superHorrific = false;
                superDrive = 0;
            }
            default -> throw new IllegalArgumentException("Unknown predator pace: " + pace);
        }
        rig.superMotors(superHorrific);
    }

    private void updateSuperDrive(double forward, double strafe, double turn, double speed) {
        if (!superHorrific) return;
        double request =
                Math.min(Math.clamp(Math.hypot(forward, strafe), 0, 1), Math.clamp(speed, 0, 1));
        double target =
                (sprintAllowed ? 1 : 0)
                        * Math.clamp((request - .25) / .75, 0, 1)
                        // Routine heading feedback must not repeatedly cancel acceleration.
                        // Full boost through .3; sharp turns taper to ordinary gait at .7.
                        * Math.clamp((.7 - Math.abs(turn)) / .4, 0, 1)
                        * clearGroundBlend;
        // Enter the faster stroke over1.5 seconds. Near a point, during a turn or on rough
        // ground, return to ordinary strokes in at most.3 seconds. Holding keeps its
        // existing captured-foot mechanism; this adds no translation force or impulse.
        superDrive += Math.clamp(target - superDrive, -.05 / .3, .05 / 1.5);
    }

    double balance =
            Double.parseDouble(System.getProperty("minecraft_machines.leaperBalance", "1"));

    MinecraftMachinesLeaperPredatorControl(
            MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperTerrainMap map,
            MinecraftMachinesLeaperPredatorPolicy policy) {
        this.rig = rig;
        this.map = rig.scale == 1 ? map : new MinecraftMachinesLeaperTerrainMap(map.level(), rig.scale);
        this.policy = policy;
        rig.predatorMotors();
        MinecraftMachinesLeaperHighStepSettings.applyPreview(this);
    }

    /** World-space body height; gait policy lengths remain in the trained model's units. */
    double standingHeight() { return policy.value[4] * rig.scale; }

    double[] walkingParameters() {
        var p = policy.value.clone();
        if (agile) {
            double blend = clearGroundBlend;
            p[0] += blend * (3.8 - p[0]);
            p[2] += blend * (.60 - p[2]);
            p[3] += blend * .20;
            p[5] += blend * (.75 - p[5]);
        }
        if (superHorrific) {
            p[0] += superDrive * (5.4 - p[0]);
            p[1] += superDrive * (.48 - p[1]);
            p[2] += superDrive * (.55 - p[2]);
            p[5] += superDrive * (.40 - p[5]);
            p[16] += superDrive * (14 - p[16]);
            p[17] += superDrive * (16 - p[17]);
            if (apex) {
                double fast =
                        Math.clamp(
                                        (Math.max(apexTargetSpeed, Math.hypot(measuredX, measuredZ))
                                                        - 6)
                                                / 18,
                                        0,
                                        1)
                                * clearGroundBlend;
                p[1] = Math.min(p[1], .48 - .22 * fast);
            }
        }
        return p;
    }

    void resumeAfterLeap() {
        captureBoundedFeet(resumeFeet, resumeAngles);
        landingFeet = null;
        var center = rig.corePivot().div(rig.scale);
        double floor = map.height(center.x, center.z, center.y + .35);
        if (!Double.isFinite(floor)) {
            double sum = 0;
            int count = 0;
            for (int i = 0; i < 4; i++) {
                var toe = rig.toe(i).div(rig.scale);
                double h = map.height(toe.x, toe.z, toe.y + .35);
                if (Double.isFinite(h) && Math.abs(toe.y - h) < .6) {
                    sum += h;
                    count++;
                }
            }
            floor = count > 0 ? sum / count : Double.NaN;
        }
        // Ease extra standing extension after a crouch/landing. If airborne, do not
        // stretch the legs toward a distant floor beyond their normal stance height.
        resumeStandingHeight =
                Double.isFinite(floor)
                        ? Math.clamp(center.y - floor, .75, policy.value[4])
                        : policy.value[4];
        resumeTicks = 0;
        inLeapPose = wasPouncing = false;
        superDrive = 0;
        initialized = false;
        measured = false;
        holding = false;
        holdTicks = 0;
        cycles = 0;
        for (int i = 0; i < 4; i++) {
            swingBefore[i] = false;
            Arrays.fill(from[i], 0);
            Arrays.fill(to[i], 0);
            Arrays.fill(shift[i], 0);
        }
    }

    void leapPose(double radius, double down, boolean grounded) {
        if (!inLeapPose) {
            captureBoundedFeet(poseFeet, poseAngles);
            poseTicks = 0;
            poseDuration = wasPouncing ? 12 : 4;
            terrainLandingPose = wasPouncing && landingFeet != null;
            inLeapPose = true;
            wasPouncing = false;
        }
        double blend = smoothUnit(poseTicks / (double) poseDuration);
        var q = new double[12];
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(THETA[i]);
            var target =
                    terrainLandingPose
                            ? landingFoot(i).add(0, policy.value[4] - down, 0)
                            : new Vector3d(radius * Math.sin(a), -down, -radius * Math.cos(a));
            var foot =
                    new Vector3d(poseFeet[i][0], poseFeet[i][1], poseFeet[i][2])
                            .lerp(target, blend);
            var angles =
                    poseTicks == 0
                            ? Arrays.copyOfRange(poseAngles, 3 * i, 3 * i + 3)
                            : LeaperGait.inverse(i, foot.x, foot.y, foot.z);
            System.arraycopy(angles, 0, q, 3 * i, 3);
            var toe = rig.toe(i).div(rig.scale);
            double floor = grounded ? map.height(toe.x, toe.z, toe.y + .35) : Double.NaN;
            rig.gripFoot(i, grounded && Double.isFinite(floor) && Math.abs(toe.y - floor) < .42, 1);
        }
        rig.command(q, .05, 5, policy.value[16], new boolean[] {true, true, true, true});
        poseTicks = Math.min(poseTicks + 1, poseDuration);
        rig.stabilize(balance, 0, 0);
    }

    void pouncePose(double fraction) {
        inLeapPose = false;
        wasPouncing = true;
        var q = new double[12];
        double spread = Math.sin(Math.PI * Math.clamp(fraction / .78, 0, 1));
        double reachLanding = Math.clamp((fraction - .60) / .30, 0, 1);
        for (int leg = 0; leg < 4; leg++) {
            double theta = Math.toRadians(THETA[leg]);
            double flare = Math.sin(2 * Math.PI * fraction + leg * 1.7);
            double radius = 4.8 + (1 - reachLanding) * (1.0 + .4 * flare);
            double down =
                    policy.value[4] * reachLanding
                            + (1 - reachLanding)
                                    * (1.3 - (leg < 2 ? 4.5 : 3.4) * spread + .7 * flare);
            var foot = new Vector3d(radius * Math.sin(theta), -down, -radius * Math.cos(theta));
            if (landingFeet != null) {
                // Replace only the late-flight landing component of the existing flare.
                // The target is a bounded per-leg terrain stance, never a root translation.
                var delta =
                        landingFoot(leg)
                                .sub(
                                        4.8 * Math.sin(theta),
                                        -policy.value[4],
                                        -4.8 * Math.cos(theta));
                foot.fma(reachLanding, delta);
            }
            var angles = LeaperGait.inverse(leg, foot.x, foot.y, foot.z);
            System.arraycopy(angles, 0, q, 3 * leg, 3);
            rig.gripFoot(leg, false, 1);
        }
        rig.command(q, .05, 8, policy.value[16], new boolean[] {true, true, true, true});
        // Bounded pitch/roll torque preserves an aimed body while the native links whip outward.
        rig.stabilize(balance * .55, 0, 0);
    }

    void setLandingFeet(double[][] feet, double heading) {
        if (feet == null) {
            landingFeet = null;
            return;
        }
        if (feet.length != 4 || !Double.isFinite(heading))
            throw new IllegalArgumentException("Expected four finite landing feet and heading");
        var copy = new double[4][3];
        for (int leg = 0; leg < 4; leg++) {
            if (feet[leg] == null
                    || feet[leg].length != 3
                    || !rig.reachableFoot(leg, feet[leg][0], feet[leg][1], feet[leg][2]))
                throw new IllegalArgumentException("Unreachable landing foot " + leg);
            copy[leg] = Arrays.stream(feet[leg]).map(v -> v / rig.scale).toArray();
        }
        landingFeet = copy;
        landingHeading = heading;
    }

    private Vector3d landingFoot(int leg) {
        var foot = new Vector3d(landingFeet[leg][0], landingFeet[leg][1], landingFeet[leg][2]);
        double c = Math.cos(landingHeading), s = Math.sin(landingHeading);
        return rig.coreOrientation()
                .transformInverse(
                        new Vector3d(c * foot.x - s * foot.z, foot.y, s * foot.x + c * foot.z));
    }

    private void captureBoundedFeet(double[][] feet, double[] angles) {
        System.arraycopy(rig.boundedMotorTargets(), 0, angles, 0, 12);
        for (int i = 0; i < 4; i++) {
            double[] p = LeaperGait.forward(i, angles[3 * i], angles[3 * i + 1], angles[3 * i + 2]);
            System.arraycopy(p, 0, feet[i], 0, 3);
        }
    }

    private static double smoothUnit(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    void step(double forward, double strafe, double turn, double speed) {
        inLeapPose = wasPouncing = false;
        updateSuperDrive(forward, strafe, turn, speed);
        var p = agile || superHorrific ? walkingParameters() : policy.value;
        if (shockwaveCompression != 0) {
            p = p.clone();
            p[4] -= shockwaveCompression;
        }
        // Faster pivot strokes, limited to deliberate turns with little translation
        // on sensed clear ground. Sprint and small heading corrections keep their gait.
        double pivot = Math.clamp((.5 - Math.hypot(forward, strafe)) / .35, 0, 1);
        double requestedTurn =
                Math.clamp((Math.abs(turn) - .3) / .5, 0, 1) * pivot * clearGroundBlend;
        turnDrive += Math.clamp(requestedTurn - turnDrive, -.05 / .2, .05 / .25);
        if (Math.abs(turn) > .01 && turnDrive > 0) {
            p = p.clone();
            p[0] += turnDrive * (Math.max(p[0], 4.6) - p[0]);
            p[1] += turnDrive * (Math.min(p[1], .60) - p[1]);
            p[5] += turnDrive * (Math.min(p[5], .40) - p[5]);
            p[16] += turnDrive * (Math.max(p[16], 8) - p[16]);
            p[17] += turnDrive * (Math.max(p[17], 8) - p[17]);
        }
        double resumeBlend = smoothUnit(resumeTicks / (double) RESUME_TICKS);
        if (resumeTicks < RESUME_TICKS) {
            p = p.clone();
            p[4] = resumeStandingHeight + resumeBlend * (p[4] - resumeStandingHeight);
        }
        var center = rig.corePivot().div(rig.scale);
        if (measured) {
            measuredX += .25 * ((center.x - previousX) / .05 - measuredX);
            measuredZ += .25 * ((center.z - previousZ) / .05 - measuredZ);
        }
        previousX = center.x;
        previousZ = center.z;
        measured = true;
        var velocity = rig.velocity().div(rig.scale);
        var up = rig.up();
        var orientation = rig.coreOrientation();
        double yaw = rig.heading(), c = Math.cos(yaw), s = Math.sin(yaw), ceiling = center.y + 6;
        double norm = Math.hypot(forward, strafe);
        if (norm > 1) {
            forward /= norm;
            strafe /= norm;
        }
        turn = Math.clamp(turn, -1, 1);
        double activity =
                Math.clamp(4 * Math.max(Math.hypot(forward, strafe), Math.abs(turn)), 0, 1);
        if (activity < .01 && !holding) {
            // Start from the bounded motor targets, including their unfinished swing height.
            // Capturing only actual X/Z then requesting stance Y made stop itself a hard step.
            captureBoundedFeet(holdFeet, holdAngles);
            holding = true;
            holdTicks = 0;
            holdX = center.x;
            holdZ = center.z;
        }
        if (activity >= .01) holding = false;
        double[] sensed = new double[4];
        Vector3d[] toes = new Vector3d[4];
        double min = 1e9, max = -1e9;
        for (int i = 0; i < 4; i++) {
            var toe = rig.toe(i).div(rig.scale);
            toes[i] = toe;
            sensed[i] = map.height(toe.x, toe.z, Math.min(ceiling, toe.y + 3));
            if (!Double.isFinite(sensed[i])) sensed[i] = initialized ? contact[i] : center.y - p[4];
        }
        double measuredMaximum = Arrays.stream(sensed).max().orElse(center.y - p[4]);
        for (int i = 0; i < 4; i++) {
            var toe = toes[i];
            double supportError =
                    sensed[i]
                            - (ground
                                    + gradientX * (toe.x - center.x)
                                    + gradientZ * (toe.z - center.z));
            // Retire a stale high contact only once every measured support surface
            // is lower. Preserve outlier rejection while straddling a real ledge.
            boolean vanishedSupport =
                    freshSupport && supportError < -4.5 && contact[i] > measuredMaximum + 4.5;
            if (initialized && Math.abs(supportError) > 4.5 && !vanishedSupport)
                sensed[i] = contact[i];
            min = Math.min(min, sensed[i]);
            max = Math.max(max, sensed[i]);
        }
        roughness = max - min;
        double mx = 0, mz = 0, mh = 0;
        for (int i = 0; i < 4; i++) {
            mx += toes[i].x * .25;
            mz += toes[i].z * .25;
            mh += sensed[i] * .25;
        }
        double xx = 0, zz = 0, xz = 0, xh = 0, zh = 0;
        for (int i = 0; i < 4; i++) {
            double tx = toes[i].x - mx, tz = toes[i].z - mz, th = sensed[i] - mh;
            xx += tx * tx;
            zz += tz * tz;
            xz += tx * tz;
            xh += tx * th;
            zh += tz * th;
        }
        double determinant = xx * zz - xz * xz;
        double rawX = determinant > 1e-6 ? (xh * zz - zh * xz) / determinant : 0;
        double rawZ = determinant > 1e-6 ? (zh * xx - xh * xz) / determinant : 0;
        double rawGrade = Math.max(1, Math.hypot(rawX, rawZ) / 1.2);
        rawX /= rawGrade;
        rawZ /= rawGrade;
        double localGX = (c * rawX + s * rawZ) * p[11], localGZ = (-s * rawX + c * rawZ) * p[10];
        double gx = c * localGX - s * localGZ, gz = s * localGX + c * localGZ;
        double grade = Math.max(1, Math.hypot(gx, gz) / .7);
        gx /= grade;
        gz /= grade;
        gradientX += Math.clamp((gx - gradientX) * .2, -.02, .02);
        gradientZ += Math.clamp((gz - gradientZ) * .2, -.02, .02);
        double referenceHeight =
                p[4] * Math.sqrt(1 + gradientX * gradientX + gradientZ * gradientZ);
        if (!initialized) {
            ground = Arrays.stream(sensed).average().orElse(center.y - p[4]);
            for (int i = 0; i < 4; i++) contact[i] = departure[i] = landing[i] = sensed[i];
            initialized = true;
        }
        double desired = mh - rawX * (mx - center.x) - rawZ * (mz - center.z),
                // Ground beneath the chassis cannot be a roof above it. Sampling up
                // to the lookahead ceiling made the legs push against low overhead
                // blocks, storing a violent release when a clearing burst removed them.
                centerFloor = map.height(center.x, center.z, center.y - 1.25);
        if (Double.isFinite(centerFloor)) desired = Math.max(desired, centerFloor - .45);
        // Raise the suspended chassis before a full voxel riser touches its underside.
        // Feet still provide all vertical support through their limited joints.
        for (double ox : new double[] {-1.7, 0, 1.7})
            for (double oz : new double[] {-1.7, 0, 1.7}) {
                double h =
                        map.height(
                                center.x + ox + velocity.x * .2,
                                center.z + oz + velocity.z * .2,
                                ceiling);
                if (Double.isFinite(h) && h - Math.min(min, center.y - p[4]) < bodyLookaheadRise)
                    desired = Math.max(desired, h - (p[4] - 1.5));
            }
        ground += Math.clamp(desired - ground, -p[9] * .05, p[9] * ascentRateScale * .05);
        double pace = Math.clamp(speed, .25, 1.3);
        double safeFlat = 0;
        if ((clearGroundGain > 0 || superHorrific) && Double.isFinite(centerFloor)) {
            double discrepancy = roughness;
            double headingNorm = Math.hypot(forward, strafe);
            double localX = headingNorm > .1 ? strafe / headingNorm : 0;
            double localZ = headingNorm > .1 ? -forward / headingNorm : -1;
            double wx = c * localX - s * localZ, wz = s * localX + c * localZ;
            // Check a full stance and two strides ahead, so high swing is active
            // before the first riser enters the foot corridor.
            for (double ahead : new double[] {-8, 0, 8, 16})
                for (double lateral : new double[] {-6, 0, 6}) {
                    double floor =
                            map.height(
                                    center.x + wx * ahead - wz * lateral,
                                    center.z + wz * ahead + wx * lateral,
                                    ceiling);
                    discrepancy =
                            Double.isFinite(floor)
                                    ? Math.max(discrepancy, Math.abs(floor - centerFloor))
                                    : 10;
                }
            safeFlat = Math.clamp(1 - discrepancy / .75, 0, 1);
        }
        clearGroundBlend =
                clearGroundMeasured
                        ? clearGroundBlend + .25 * (safeFlat - clearGroundBlend)
                        : safeFlat;
        clearGroundMeasured = true;
        double flatRecovery =
                clearGroundGain * clearGroundBlend * Math.clamp((pace - .45) / .55, 0, 1);
        double verticalDamping = p[12] + slowDamping * Math.clamp((1 - pace) / .55, 0, 1);
        verticalDamping += flatRecovery * (.0012053487767997029 - verticalDamping);
        // Low speed retains a useful step span and lowers cadence, so a foot
        // still clears a tall riser. The nominal stride/cadence product is unchanged.
        double clearStride = Math.clamp(1 - roughness / .5, 0, 1);
        for (int i = 0; i < 4; i++)
            clearStride *= 1 - Math.clamp((obstacle[i] - contact[i] - .15) / .5, 0, 1);
        double cadenceGain = flatCadence * clearStride * Math.clamp((pace - .45) / .55, 0, 1);
        double period = p[1] / (pace * (1 + cadenceGain));
        if (!holding)
            cycles =
                    clearanceClock.advance(
                            cycles,
                            .05 / period,
                            p[2],
                            p[6],
                            p[7],
                            new double[] {toes[0].y, toes[1].y, toes[2].y, toes[3].y},
                            departure,
                            obstacle,
                            activity > .01 ? clearancePause : 0);
        double stride = p[0] * (.75 + .25 * pace) * Math.clamp(1 - p[18] * roughness, .65, 1);
        commandedPeriod = period;
        commandedStride = stride;
        double ux = c * up.x + s * up.z, uz = -s * up.x + c * up.z;
        double[] action = new double[12];
        boolean[] cartesianSlew = new boolean[4];
        for (int i = 0; i < 4; i++) {
            double theta = Math.toRadians(THETA[i]),
                    nx = p[3] * Math.sin(theta),
                    nz = -p[3] * Math.cos(theta);
            // Shorten the stance along steep terrain so rear feet can reach the
            // next tread without front feet spanning several risers ahead.
            double slope = Math.hypot(gradientX, gradientZ);
            if (stanceCompression > 0 && slope > .1) {
                double gxLocal = (c * gradientX + s * gradientZ) / slope;
                double gzLocal = (-s * gradientX + c * gradientZ) / slope;
                double inward =
                        stanceCompression
                                * Math.clamp((slope - .1) / .4, 0, 1)
                                * (nx * gxLocal + nz * gzLocal);
                nx -= inward * gxLocal;
                nz -= inward * gzLocal;
            }
            double phase = cycles + (i % 2 == 1 ? p[6] : 0) + (i >= 2 ? p[7] : 0);
            phase -= Math.floor(phase);
            boolean swing = phase >= p[2] && activity > .01;
            if (Boolean.parseBoolean(System.getProperty("minecraft_machines.leaperGrip", "true"))) {
                var toe = rig.toe(i).div(rig.scale);
                double floor = map.height(toe.x, toe.z, toe.y + 1);
                rig.gripFoot(
                        i,
                        !swing && Double.isFinite(floor) && Math.abs(toe.y - floor) < .32,
                        apex && activity > .1 ? 1 - .85 * clearGroundBlend : 1);
            }
            double dx = strafe - turn * nz / p[3], dz = -forward + turn * nx / p[3];
            double footNorm = Math.max(1, Math.hypot(dx, dz));
            dx /= footNorm;
            dz /= footNorm;
            if (swing && !swingBefore[i]) {
                departure[i] = contact[i];
                from[i] = shift[i].clone();
                double lx = nx + .5 * stride * dx,
                        lz = nz + .5 * stride * dz,
                        duration = (1 - p[2]) * period;
                var offset = orientation.transform(new Vector3d(lx, -p[4], lz));
                double wx = center.x + offset.x + measuredX * duration * p[13],
                        wz = center.z + offset.z + measuredZ * duration * p[13];
                double best = 1e9, h = Double.NaN, chosenX = wx, chosenZ = wz;
                for (double fraction :
                        adaptiveFootholds ? new double[] {1, .7, .4, .15} : new double[] {1})
                    for (double ox : new double[] {0, -p[15], p[15], -2 * p[15], 2 * p[15]})
                        for (double oz : new double[] {0, -p[15], p[15], -2 * p[15], 2 * p[15]}) {
                            double ax = toes[i].x + (wx - toes[i].x) * fraction + ox;
                            double az = toes[i].z + (wz - toes[i].z) * fraction + oz;
                            // Keep the exact baseline arithmetic when the search is disabled.
                            if (!adaptiveFootholds) {
                                ax = wx + ox;
                                az = wz + oz;
                            }
                            double candidate = map.padHeight(ax, az, ceiling);
                            double ex = adaptiveFootholds ? ax - wx : ox,
                                    ez = adaptiveFootholds ? az - wz : oz;
                            double cost =
                                    ex * ex + ez * ez + .08 * Math.abs(candidate - contact[i]);
                            double expected =
                                    ground + rawX * (ax - center.x) + rawZ * (az - center.z);
                            var target =
                                    orientation.transformInverse(
                                            new Vector3d(
                                                    ax - center.x - measuredX * duration * p[13],
                                                    candidate - ground - referenceHeight,
                                                    az - center.z - measuredZ * duration * p[13]));
                            var angles = LeaperGait.inverse(i, target.x, target.y, target.z);
                            if (Double.isFinite(candidate)
                                    && Math.abs(candidate - expected) < 3.6
                                    && Math.abs(angles[0]) < .88
                                    && angles[1] > shoulderMinimum + .02
                                    && angles[1] < shoulderReach - .02
                                    && angles[2] > -1.18
                                    && angles[2] < kneeReach - .02
                                    && cost < best) {
                                best = cost;
                                h = candidate;
                                chosenX = ax;
                                chosenZ = az;
                                to[i][0] = c * ex + s * ez;
                                to[i][1] = -s * ex + c * ez;
                            }
                        }
                if (!Double.isFinite(h)) {
                    h = contact[i];
                    to[i] = from[i].clone();
                    rejectedFootholds++;
                }
                landing[i] = h;
                obstacle[i] = Math.max(departure[i], h);
                var toe = rig.toe(i).div(rig.scale);
                swingFrom[i] = new Vector3d(toe);
                swingTo[i] = new Vector3d(chosenX, h, chosenZ);
                if (worldSwing) departure[i] = toe.y;
                // Sweep the selected landing corridor, including the real square pad.
                // The old code tested the nominal endpoint even after moving the foothold.
                double clearance = map.sweepHeight(toe.x, toe.z, chosenX, chosenZ, ceiling);
                if (Double.isFinite(clearance) && clearance - ground < 4.2)
                    obstacle[i] = Math.max(obstacle[i], clearance);
            }
            double sweep = .5 - phase / p[2], h = contact[i], lift = 0;
            if (swing) {
                double t = (phase - p[2]) / (1 - p[2]), smooth = t * t * (3 - 2 * t);
                sweep = -.5 * Math.cos(Math.PI * t);
                h = departure[i] + (landing[i] - departure[i]) * smooth;
                // A drop is not an obstruction above the foothold corridor. Counting half
                // its depth as extra clearance produced unnecessarily high, hard landings.
                lift =
                        (p[5]
                                        + (postClearanceLift ? 0 : extraLift)
                                                * (adaptiveLift
                                                        ? Math.clamp(
                                                                (obstacle[i] - departure[i] - .15)
                                                                        / .75,
                                                                0,
                                                                1)
                                                        : 1)
                                        + p[8]
                                                * Math.max(
                                                        0,
                                                        obstacle[i]
                                                                - (earlyClearance
                                                                        ? Math.max(
                                                                                departure[i],
                                                                                landing[i])
                                                                        : .5
                                                                                * (departure[i]
                                                                                        + landing[
                                                                                                i]))))
                                * activity
                                * Math.sin(Math.PI * t);
                if (earlyClearance) {
                    var clear =
                            LeaperSwingClearance.shape(
                                    t, departure[i], landing[i], obstacle[i], h + lift, sweep);
                    if (clear.blend() > 0) {
                        lift = clear.height() - h;
                        sweep = clear.sweep();
                        if (!swingBefore[i]) clearanceSwings++;
                    }
                }
                if (highStep > 0) {
                    double top =
                            Math.max(obstacle[i], Math.max(departure[i], landing[i])) + highStep;
                    double u = Math.clamp(t / liftLead, 0, 1);
                    double down = Math.clamp((t - .70) / .30, 0, 1);
                    double high =
                            t < liftLead
                                    ? departure[i] + (top - departure[i]) * u * u * (3 - 2 * u)
                                    : top + (landing[i] - top) * down * down * (3 - 2 * down);
                    lift = Math.max(lift, (high - h) * activity);
                    double travel = Math.clamp((t - liftLead) / (1 - liftLead), 0, 1);
                    sweep = -.5 * Math.cos(Math.PI * travel);
                }
                if (postClearanceLift) {
                    double boost =
                            adaptiveLift
                                    ? Math.clamp((obstacle[i] - departure[i] - .15) / .75, 0, 1)
                                    : 1;
                    if (paceLift) {
                        double flat = Math.clamp((pace - .45) / .55, 0, 1);
                        double rise = Math.clamp((obstacle[i] - departure[i] - .15) / .75, 0, 1);
                        boost = flat + (1 - flat) * rise;
                    }
                    lift +=
                            extraLift
                                    * boost
                                    * (1 - flatRecovery)
                                    * activity
                                    * Math.sin(Math.PI * t);
                }
                var actual = rig.toe(i).div(rig.scale);
                double ahead =
                        map.height(
                                actual.x + .4 * (c * dx - s * dz),
                                actual.z + .4 * (s * dx + c * dz),
                                ceiling);
                if (Double.isFinite(ahead))
                    lift += p[19] * Math.clamp(ahead + .2 - actual.y, 0, 2) * Math.sin(Math.PI * t);
                for (int j = 0; j < 2; j++)
                    shift[i][j] = from[i][j] + (to[i][j] - from[i][j]) * smooth;
            } else {
                if (swingBefore[i] && !holding) {
                    contact[i] = landing[i];
                    shift[i] = to[i].clone();
                    if (worldSwing) {
                        var actual = rig.localToe(i).div(rig.scale);
                        shift[i][0] = actual.x - nx - stride * sweep * dx;
                        shift[i][1] = actual.z - nz - stride * sweep * dz;
                        to[i] = shift[i].clone();
                    }
                }
                contact[i] += Math.clamp((sensed[i] - contact[i]) * p[14], -p[9] * .05, p[9] * .05);
                h = contact[i];
            }
            swingBefore[i] = swing;
            cartesianSlew[i] = swing && coordinatedSwing;
            double x = nx + stride * sweep * dx + shift[i][0],
                    z = nz + stride * sweep * dz + shift[i][1];
            if (holding) {
                double ramp = Math.clamp(holdTicks / 7.0, 0, 1);
                ramp = ramp * ramp * (3 - 2 * ramp);
                double wx = ramp * (.6 * (center.x - holdX) + .2 * measuredX);
                double wz = ramp * (.6 * (center.z - holdZ) + .2 * measuredZ);
                x = holdFeet[i][0] + Math.clamp(c * wx + s * wz, -1.3, 1.3);
                z = holdFeet[i][2] + Math.clamp(-s * wx + c * wz, -1.3, 1.3);
            }
            var offset =
                    orientation.transform(new Vector3d(x, holding ? holdFeet[i][1] : -p[4], z));
            // Add a smooth lower bound to swing clearance when the torso sags.
            // Stance/body-height control and both stroke endpoints remain continuous.
            double sag =
                    swing && worldSwingHeight
                            ? LeaperSwingClearance.sagCompensation(
                                    (phase - p[2]) / (1 - p[2]),
                                    center.y - ground - referenceHeight)
                            : 0;
            double stanceY =
                    h - ground - referenceHeight + lift + sag + verticalDamping * velocity.y;
            if (holding) {
                if (holdTicks == 0)
                    holdLoweringTicks[i] =
                            Math.clamp(
                                    4 + (int) Math.ceil(Math.max(0, offset.y - stanceY) / .8),
                                    4,
                                    7);
                double t = Math.clamp(holdTicks / (double) holdLoweringTicks[i], 0, 1);
                double blend = t * t * (3 - 2 * t);
                offset.y += blend * (stanceY - offset.y);
                // Preserve the Cartesian lowering segment through the existing bounded solver.
                cartesianSlew[i] = holdTicks <= holdLoweringTicks[i];
            } else offset.y = stanceY;
            commandedToeHeight[i] = center.y + offset.y;
            var local = orientation.transformInverse(offset);
            if (worldSwing && swing) {
                double travel = Math.clamp(sweep + .5, 0, 1);
                var target = new Vector3d(swingFrom[i]).lerp(swingTo[i], travel);
                target.y = h + lift;
                local = orientation.transformInverse(target.sub(center));
            }
            if (resumeTicks < RESUME_TICKS) {
                local =
                        new Vector3d(resumeFeet[i][0], resumeFeet[i][1], resumeFeet[i][2])
                                .lerp(local, resumeBlend);
                cartesianSlew[i] = true;
                commandedToeHeight[i] = center.y + orientation.transform(new Vector3d(local)).y;
            }
            var q =
                    resumeTicks == 0
                            ? Arrays.copyOfRange(resumeAngles, 3 * i, 3 * i + 3)
                            : holding && holdTicks == 0
                                    ? Arrays.copyOfRange(holdAngles, 3 * i, 3 * i + 3)
                                    : LeaperGait.inverse(i, local.x, local.y, local.z);
            if (q[1] > shoulderReach) shoulderClamps++;
            for (int j = 0; j < 3; j++) action[3 * i + j] = q[j];
            System.arraycopy(q, 0, rawRequestedAngles, 3 * i, 3);
        }
        // Back off actuator force before a wedged link stretches its structural constraint.
        // Super tolerates ordinary faster-stroke anchor deflection before throttling;
        // held feet retain the original threshold and joint-limit backoff is unchanged.
        double anchorSoftStart = .025 + (superHorrific && !holding ? .020 * superDrive : 0);
        double governor =
                Math.min(
                        Math.clamp(1 - (rig.jointStrain() - anchorSoftStart) / .055, .15, 1),
                        Math.clamp(1 - (rig.limitViolation() - .025) / .09, .15, 1));
        commandedSlew = Math.max(1, p[17] * governor);
        commandedPower = Math.max(1, p[16] * governor);
        rig.command(action, .05, commandedSlew, commandedPower, cartesianSlew);
        for (int i = 0; i < 4; i++) boundedToeHeight[i] = rig.boundedCommandToe(i).y;
        if (holding) holdTicks = Math.min(holdTicks + 1, 8);
        if (resumeTicks < RESUME_TICKS) resumeTicks++;
        if (balance > 0) rig.stabilize(balance * governor, gradientX, gradientZ);
    }
}
