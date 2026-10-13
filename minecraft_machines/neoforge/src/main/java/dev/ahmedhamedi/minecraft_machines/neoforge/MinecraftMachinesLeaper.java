package dev.ahmedhamedi.minecraft_machines.neoforge;

import static dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis.*;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperPartBlock;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperScaledPartBlock;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperBehaviour;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperGait;
import dev.ahmedhamedi.minecraft_machines.index.MinecraftMachinesBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.*;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;

/** Leaper-inspired 13-body rig, with optional finite predator attitude assistance. */
final class MinecraftMachinesLeaper implements AutoCloseable {
    static final String CONTRACT = "leaper_12dof_v1";
    static final String PREDATOR_CONTRACT = "leaper_12dof_precise_predator_v2";
    static final double UPPER = 4.0, LOWER = 4.5, HEIGHT = 2.25;
    static final double[] MIN = {-.60, -.65, -.85}, MAX = {.60, .65, .85};
    private final ServerLevel level;
    final double scale;
    private final List<ServerSubLevel> bodies = new ArrayList<>();
    private final List<Joint> joints = new ArrayList<>();
    private final double[] targets = new double[12];
    double motorForceScale = 1, motorDampingScale = 1;
    boolean apexVersion = true;
    double assistedHeading = Double.NaN;
    private final List<Vector3d> initialPositions = new ArrayList<>(),
            toeAnchors = new ArrayList<>();
    private final List<Quaterniond> initialOrientations = new ArrayList<>();
    private boolean closed, preciseCollision;
    private final List<BlockPos> collisionCells = new ArrayList<>();
    private final List<BlockPos> eyeCells = new ArrayList<>();
    private LeaperBehaviour.Mode behaviourMode = LeaperBehaviour.Mode.PATROLLING;
    private final GenericConstraintHandle[] footGrips = new GenericConstraintHandle[4];

    /** Change only the emissive eye material; the physical cells and their shapes stay identical. */
    void setBehaviourMode(LeaperBehaviour.Mode mode) {
        Objects.requireNonNull(mode, "Leaper behaviour mode");
        if (mode == behaviourMode || closed) return;
        var eyeMode =
                switch (mode) {
                    case PATROLLING -> LeaperPartBlock.EyeMode.PATROLLING;
                    case ALERT -> LeaperPartBlock.EyeMode.ALERT;
                    case HUNTING -> LeaperPartBlock.EyeMode.HUNTING;
                };
        for (var pos : eyeCells) {
            var state = level.getBlockState(pos);
            if (!state.hasProperty(LeaperPartBlock.EYE_MODE))
                throw new IllegalStateException("Lost Leaper eye cell " + pos);
            level.setBlockAndUpdate(pos, state.setValue(LeaperPartBlock.EYE_MODE, eyeMode));
        }
        behaviourMode = mode;
    }

    LeaperBehaviour.Mode behaviourMode() {
        return behaviourMode;
    }

    void gripFoot(int leg, boolean touching, double strength) {
        var grip = footGrips[leg];
        if (!touching) {
            if (grip != null && grip.isValid()) grip.remove();
            footGrips[leg] = null;
            return;
        }
        if (grip == null || !grip.isValid()) {
            var body = bodies.get(3 + 3 * leg);
            var toe = toe(leg);
            grip =
                    SubLevelPhysicsSystem.get(level)
                            .getPipeline()
                            .addConstraint(
                                    null,
                                    body,
                                    new GenericConstraintConfiguration(
                                            new Vector3d(toe),
                                            new Vector3d(toeAnchors.get(leg)),
                                            new Quaterniond(),
                                            new Quaterniond(body.logicalPose().orientation())
                                                    .invert(),
                                            EnumSet.noneOf(ConstraintJointAxis.class)));
            footGrips[leg] = grip;
        }
        if (grip != null && grip.isValid()) {
            // Horizontal contact traction only. Vertical translation and all rotations stay free.
            grip.setMotor(
                    LINEAR_X,
                    0,
                    (200000 * strength) * Math.pow(scale, 3),
                    (10000 * Math.sqrt(strength)) * Math.pow(scale, 3),
                    true,
                    (100000 * strength) * Math.pow(scale, 3));
            grip.setMotor(
                    LINEAR_Z,
                    0,
                    (200000 * strength) * Math.pow(scale, 3),
                    (10000 * Math.sqrt(strength)) * Math.pow(scale, 3),
                    true,
                    (100000 * strength) * Math.pow(scale, 3));
        }
    }

    void releaseFeet() {
        for (int i = 0; i < 4; i++) gripFoot(i, false, 1);
    }

    private double[] activeMin = MIN.clone(), activeMax = MAX.clone();
    private GenericConstraintHandle balanceMotor;
    private MinecraftMachinesLeaperPhysicsLease predatorPhysics;

    void disablePredator() {
        disablePredator(true);
    }

    void disablePredator(boolean legacyGeometry) {
        releaseFeet();
        if (legacyGeometry) preciseCollision(false);
        if (balanceMotor != null && balanceMotor.isValid()) balanceMotor.remove();
        balanceMotor = null;
        if (predatorPhysics != null) predatorPhysics.close();
        predatorPhysics = null;
        activeMin = MIN.clone();
        activeMax = MAX.clone();
        for (int i = 0; i < 12; i++)
            joints.get(i).handle.setLimit(ANGULAR_X, activeMin[i % 3], activeMax[i % 3]);
    }

    /** Finite gameplay attitude assistance: torque only, no position or velocity override. */
    void stabilize(double gain) {
        stabilize(gain, 0, 0);
    }

    void stabilize(double gain, double gradientX, double gradientZ) {
        if (!Double.isFinite(gain) || gain < 0 || gain > 4)
            throw new IllegalArgumentException("Balance gain 0..4");
        var core = bodies.getFirst();
        var frame =
                new Quaterniond()
                        .rotateY(-(Double.isFinite(assistedHeading) ? assistedHeading : heading()));
        var normal = new Vector3d(-gradientX, 1, -gradientZ).normalize();
        var localNormal = new Quaterniond(frame).transformInverse(normal);
        frame.rotateX(Math.atan2(localNormal.z, localNormal.y))
                .rotateZ(-Math.asin(Math.clamp(localNormal.x, -1, 1)));
        if (balanceMotor == null || !balanceMotor.isValid())
            balanceMotor =
                    SubLevelPhysicsSystem.get(level)
                            .getPipeline()
                            .addConstraint(
                                    null,
                                    core,
                                    new GenericConstraintConfiguration(
                                            position(),
                                            new Vector3d(core.getMassTracker().getCenterOfMass()),
                                            frame,
                                            new Quaterniond(),
                                            EnumSet.noneOf(ConstraintJointAxis.class)));
        if (balanceMotor != null && balanceMotor.isValid()) {
            balanceMotor.setFrame1(position(), frame);
            // Native implicit springs run at the solver's substep rate. Translation is
            // free; Apex optionally steers yaw with finite torque, without hard locks.
            for (var axis : new ConstraintJointAxis[] {ANGULAR_X, ANGULAR_Z})
                balanceMotor.setMotor(
                        axis, 0, (1000000 * gain) * Math.pow(scale, 5), (180000 * Math.sqrt(gain)) * Math.pow(scale, 5), true, (2000000 * gain) * Math.pow(scale, 5));
            boolean steering = Double.isFinite(assistedHeading);
            balanceMotor.setMotor(
                    ANGULAR_Y,
                    0,
                    steering ? 200000 * Math.pow(scale, 5) : 0,
                    steering ? 45000 * Math.pow(scale, 5) : 0,
                    true,
                    steering ? 300000 * Math.pow(scale, 5) : 0);
        }
    }

    int preciseCellCount() {
        return (int)
                collisionCells.stream()
                        .filter(p -> level.getBlockState(p).getValue(LeaperPartBlock.PRECISE))
                        .count();
    }

    private void preciseCollision(boolean enabled) {
        if (preciseCollision == enabled) return;
        for (var pos : collisionCells) {
            var state = level.getBlockState(pos);
            if (!state.hasProperty(LeaperPartBlock.EYE_MODE))
                throw new IllegalStateException("Lost Leaper collision cell " + pos);
            level.setBlockAndUpdate(pos, state.setValue(LeaperPartBlock.PRECISE, enabled));
        }
        preciseCollision = enabled;
    }

    void predatorMotors() {
        preciseCollision(true);
        if (predatorPhysics == null)
            predatorPhysics = new MinecraftMachinesLeaperPhysicsLease(level, scale);
        activeMin = new double[] {-.9, -1.05, -1.2};
        activeMax = new double[] {.9, 1.05, 1.2};
        for (int i = 0; i < 12; i++)
            joints.get(i).handle.setLimit(ANGULAR_X, activeMin[i % 3], activeMax[i % 3]);
    }

    double limitViolation() {
        double violation = 0;
        var q = angles();
        for (int i = 0; i < 12; i++)
            violation =
                    Math.max(violation, Math.max(activeMin[i % 3] - q[i], q[i] - activeMax[i % 3]));
        return violation;
    }

    boolean reachableFoot(int leg, double x, double y, double z) {
        if (leg < 0 || leg >= 4 || !Double.isFinite(x + y + z)) return false;
        x /= scale; y /= scale; z /= scale;
        double[] q = LeaperGait.inverse(leg, x, y, z);
        for (int j = 0; j < 3; j++)
            if (!Double.isFinite(q[j]) || q[j] < activeMin[j] || q[j] > activeMax[j]) return false;
        double[] actual = LeaperGait.forward(leg, q[0], q[1], q[2]);
        return new Vector3d(actual[0], actual[1], actual[2]).distance(x, y, z) < .03;
    }

    void predatorReach(double shoulder, double knee) {
        predatorReach(-1.05, shoulder, knee);
    }

    void predatorReach(double shoulderMinimum, double shoulder, double knee) {
        if (!Double.isFinite(shoulderMinimum)
                || shoulderMinimum < -1.6
                || shoulderMinimum > -1.05
                || !Double.isFinite(shoulder)
                || shoulder < 1.05
                || shoulder > 1.5
                || !Double.isFinite(knee)
                || knee < 1.2
                || knee > 1.7) throw new IllegalArgumentException("Invalid limited reach envelope");
        activeMin[1] = shoulderMinimum;
        activeMax[1] = shoulder;
        activeMax[2] = knee;
        for (int i = 0; i < 12; i++)
            joints.get(i).handle.setLimit(ANGULAR_X, activeMin[i % 3], activeMax[i % 3]);
    }

    private record Joint(
            GenericConstraintHandle handle,
            ServerSubLevel parent,
            ServerSubLevel child,
            Vector3d a,
            Vector3d b,
            Quaterniond frameA,
            Quaterniond frameB) {}

    private MinecraftMachinesLeaper(ServerLevel level, double scale) {
        this.level = level;
        this.scale = scale;
    }

    static MinecraftMachinesLeaper spawn(ServerLevel level, Vector3d center) {
        return spawn(level, center, 1);
    }

    static MinecraftMachinesLeaper spawn(ServerLevel level, Vector3d center, double scale) {
        if (scale != 1 && scale != 1.5 && scale != 2) throw new IllegalArgumentException("Supported scales: 1, 1.5, 2");
        BlockPos staging = assemblySpace(level, center, scale);
        var rig = new MinecraftMachinesLeaper(level, scale);
        var originals = new LinkedHashMap<BlockPos, BlockState>();
        try {
            var core = rig.assemble("core", staging, center, new Quaterniond(), originals);
            for (int leg = 0; leg < 4; leg++) {
                double theta = Math.toRadians(new double[] {-55, 55, -125, 125}[leg]);
                var radial = new Vector3d(Math.sin(theta), 0, -Math.cos(theta));
                var tangent = new Vector3d(-radial.z, 0, radial.x);
                var hip = new Vector3d(center).add(new Vector3d(radial).mul(1.03 * scale)).add(0, .55 * scale, 0);
                var upperDirection =
                        new Vector3d(radial)
                                .mul(Math.cos(Math.toRadians(27)))
                                .add(0, Math.sin(Math.toRadians(27)), 0);
                var lowerDirection =
                        new Vector3d(radial)
                                .mul(Math.cos(Math.toRadians(80)))
                                .add(0, -Math.sin(Math.toRadians(80)), 0);
                // The transverse X bearing axis stays tangent to the leg's radial plane.
                var qHip = new Quaterniond().rotateY(-theta);
                var qUpper = new Quaterniond(qHip).rotateX(Math.toRadians(-63));
                var qLower = new Quaterniond(qHip).rotateX(Math.toRadians(-170));
                var knee = new Vector3d(hip).add(new Vector3d(upperDirection).mul(UPPER * scale));
                var swivel = rig.assemble("hip", staging, hip, qHip, originals);
                var upper = rig.assemble("upper", staging, hip, qUpper, originals);
                var lower = rig.assemble("lower", staging, knee, qLower, originals);
                rig.toeAnchors.add(
                        lower.logicalPose()
                                .transformPositionInverse(
                                        new Vector3d(knee)
                                                .add(new Vector3d(lowerDirection).mul(LOWER * scale)),
                                        new Vector3d()));
                rig.connect(core, swivel, hip, new Vector3d(0, 1, 0), 0);
                rig.connect(swivel, upper, hip, tangent, 1);
                rig.connect(upper, lower, knee, tangent, 2);
            }
            rig.command(new double[12], 1);
            rig.captureResetPose();
            return rig;
        } catch (RuntimeException e) {
            rig.close();
            originals.forEach(level::setBlockAndUpdate);
            throw e;
        }
    }

    private static BlockPos assemblySpace(ServerLevel level, Vector3d center, double scale) {
        var offsets = new java.util.HashSet<BlockPos>();
        for (String part : new String[] {"core", "hip", "upper", "lower"}) {
            var cells = scale == 1 ? LeaperPartBlock.PARTS.get(part)
                    : LeaperScaledPartBlock.PARTS.get(part + "_" + (int) (scale * 100));
            for (var cell : cells) offsets.add(cell.offset());
        }
        int minY = offsets.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int maxY = offsets.stream().mapToInt(BlockPos::getY).max().orElse(0);
        // Assembly needs temporary world cells; destination clearance is irrelevant.
        // Search air only so successful assembly never consumes terrain or fluids.
        for (int column = 0; column < 25; column++) {
            int dx = column == 0 ? 0 : ((column - 1) % 5 - 2) * 16;
            int dz = column == 0 ? 0 : ((column - 1) / 5 - 2) * 16;
            for (int y = level.getMaxBuildHeight() - 1 - maxY;
                    y >= level.getMinBuildHeight() - minY; y--) {
                var candidate = BlockPos.containing(center.x + dx, y, center.z + dz);
                boolean clear = true;
                for (var offset : offsets) {
                    if (!level.getBlockState(candidate.offset(offset)).isAir()) { clear = false; break; }
                }
                if (clear) return candidate;
            }
        }
        throw new IllegalStateException("No empty temporary assembly cells available near this location.");
    }

    private ServerSubLevel assemble(
            String part,
            BlockPos staging,
            Vector3d pivot,
            Quaterniond orientation,
            Map<BlockPos, BlockState> originals) {
        var positions = new ArrayList<BlockPos>();
        var cells = scale == 1 ? LeaperPartBlock.PARTS.get(part)
                : LeaperScaledPartBlock.PARTS.get(part + "_" + (int) (scale * 100));
        for (var cell : cells) {
            var pos = staging.offset(cell.offset());
            if (!level.getBlockState(pos).canBeReplaced())
                throw new IllegalStateException("Leaper assembly area is obstructed: " + pos);
            originals.putIfAbsent(pos, level.getBlockState(pos));
            positions.add(pos);
            level.setBlockAndUpdate(
                    pos,
                    scale == 1 ? MinecraftMachinesBlocks.LEAPER_PART.getDefaultState().setValue(LeaperPartBlock.PART, cell.index())
                            : MinecraftMachinesBlocks.LEAPER_SCALED_PART.getDefaultState().setValue(LeaperScaledPartBlock.PART, cell.index()));
        }
        var body =
                SubLevelAssemblyHelper.assembleBlocks(
                        level, positions.getFirst(), positions, BoundingBox3i.from(positions));
        if (body == null) throw new IllegalStateException("Sable refused Leaper assembly");
        bodies.add(body);
        for (int i = 0; i < positions.size(); i++) {
            var pos = positions.get(i);
            var local =
                    body.logicalPose()
                            .transformPositionInverse(
                                    new Vector3d(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5),
                                    new Vector3d());
            var localPos = BlockPos.containing(local.x, local.y, local.z);
            collisionCells.add(localPos);
            if ((scale == 1 ? LeaperPartBlock.isEyePart(cells.get(i).index()) : LeaperScaledPartBlock.isEyePart(cells.get(i).index()))) eyeCells.add(localPos);
        }
        body.setName("Minecraft Machines Leaper " + part + " " + bodies.size());
        var oldPivot = new Vector3d(staging.getX() + .5, staging.getY() + .5, staging.getZ() + .5);
        var translation = new Vector3d(body.logicalPose().position()).sub(oldPivot);
        orientation.transform(translation).add(pivot);
        RigidBodyHandle.of(body).teleport(translation, orientation);
        SubLevelPhysicsSystem.get(level).getPipeline().resetVelocity(body);
        body.updateLastPose();
        return body;
    }

    private void connect(
            ServerSubLevel parent, ServerSubLevel child, Vector3d anchor, Vector3d axis, int kind) {
        var a = parent.logicalPose().transformPositionInverse(anchor, new Vector3d());
        var b = child.logicalPose().transformPositionInverse(anchor, new Vector3d());
        var worldFrame = new Quaterniond().rotationTo(new Vector3d(1, 0, 0), axis);
        var fa = new Quaterniond(parent.logicalPose().orientation()).invert().mul(worldFrame);
        var fb = new Quaterniond(child.logicalPose().orientation()).invert().mul(worldFrame);
        var h =
                SubLevelPhysicsSystem.get(level)
                        .getPipeline()
                        .addConstraint(
                                parent,
                                child,
                                new GenericConstraintConfiguration(
                                        a,
                                        b,
                                        fa,
                                        fb,
                                        Set.of(
                                                LINEAR_X, LINEAR_Y, LINEAR_Z, ANGULAR_Y,
                                                ANGULAR_Z)));
        if (h == null || !h.isValid()) throw new IllegalStateException("Invalid Leaper joint");
        h.setContactsEnabled(false);
        h.setLimit(ANGULAR_X, MIN[kind], MAX[kind]);
        joints.add(new Joint(h, parent, child, a, b, fa, fb));
    }

    private boolean superDriveLimits;

    void superMotors(boolean enabled) {
        superDriveLimits = enabled;
    }

    void command(double[] radians, double dt) {
        command(radians, dt, .6);
    }

    void command(double[] radians, double dt, double slew) {
        command(radians, dt, slew, 1);
    }

    void command(double[] radians, double dt, double slew, double power) {
        command(radians, dt, slew, power, null);
    }

    void command(double[] radians, double dt, double slew, double power, boolean[] cartesianSlew) {
        if (radians.length != 12 || !(dt > 0) || !Double.isFinite(dt))
            throw new IllegalArgumentException("Expected 12 targets and positive dt");
        if (!Double.isFinite(slew) || slew < .1 || slew > (superDriveLimits ? 20 : 8))
            throw new IllegalArgumentException(
                    "Joint target speed exceeds the selected finite actuator profile");
        if (!Double.isFinite(power) || power < 1 || power > (superDriveLimits ? 16 : 8))
            throw new IllegalArgumentException(
                    "Motor power exceeds the selected finite actuator profile");
        if (cartesianSlew != null && cartesianSlew.length != 4)
            throw new IllegalArgumentException("Expected four swing flags");
        for (double value : radians)
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite target");
        for (var body : bodies) SubLevelPhysicsSystem.get(level).getPipeline().wakeUp(body);
        for (int leg = 0; leg < 4; leg++) {
            if (cartesianSlew != null && cartesianSlew[leg]) {
                // Independent angular clipping can turn a vertical toe lift into a forward
                // shove. Choose one feasible point along the Cartesian command segment.
                double[] next =
                        LeaperGait.rateLimitedCartesian(
                                leg,
                                Arrays.copyOfRange(targets, 3 * leg, 3 * leg + 3),
                                Arrays.copyOfRange(radians, 3 * leg, 3 * leg + 3),
                                activeMin,
                                activeMax,
                                slew * dt);
                System.arraycopy(next, 0, targets, 3 * leg, 3);
            } else
                for (int k = 0; k < 3; k++) {
                    int j = 3 * leg + k;
                    double goal = Math.clamp(radians[j], activeMin[k], activeMax[k]);
                    targets[j] += Math.clamp(goal - targets[j], -slew * dt, slew * dt);
                }
        }
        for (int j = 0; j < 12; j++) {
            joints.get(j)
                    .handle
                    .setMotor(
                            ANGULAR_X,
                            targets[j],
                            (100000 * power) * Math.pow(scale, 5),
                            (8000 * Math.sqrt(power) * motorDampingScale) * Math.pow(scale, 5),
                            true,
                            (150000 * power * motorForceScale) * Math.pow(scale, 5));
        }
    }

    double[] boundedMotorTargets() {
        return targets.clone();
    }

    double[] angles() {
        var values = new double[12];
        for (int i = 0; i < 12; i++) {
            var j = joints.get(i);
            var a = new Quaterniond(j.parent.logicalPose().orientation()).mul(j.frameA);
            var b = new Quaterniond(j.child.logicalPose().orientation()).mul(j.frameB);
            var q = a.invert().mul(b);
            values[i] =
                    Math.atan2(
                            Math.sin(2 * Math.atan2(q.x, q.w)), Math.cos(2 * Math.atan2(q.x, q.w)));
        }
        return values;
    }

    double jointStrain() { return anchorError() / scale; }

    double assemblyMass() { return bodies.stream().mapToDouble(b -> b.getMassTracker().getMass()).sum(); }

    double anchorError() {
        double max = 0;
        for (var j : joints)
            max =
                    Math.max(
                            max,
                            j.parent
                                    .logicalPose()
                                    .transformPosition(j.a, new Vector3d())
                                    .distance(
                                            j.child
                                                    .logicalPose()
                                                    .transformPosition(j.b, new Vector3d())));
        return max;
    }

    Vector3d position() {
        var b = bodies.getFirst();
        return b.logicalPose()
                .transformPosition(b.getMassTracker().getCenterOfMass(), new Vector3d());
    }

    double upright() {
        return bodies.getFirst().logicalPose().orientation().transform(new Vector3d(0, 1, 0)).y;
    }

    Vector3d up() {
        return bodies.getFirst().logicalPose().orientation().transform(new Vector3d(0, 1, 0));
    }

    double heading() {
        var f = bodies.getFirst().logicalPose().orientation().transform(new Vector3d(0, 0, -1));
        return Math.atan2(f.x, -f.z);
    }

    Vector3d velocity() {
        return RigidBodyHandle.of(bodies.getFirst()).getLinearVelocity(new Vector3d());
    }

    Vector3d angularVelocity() {
        return RigidBodyHandle.of(bodies.getFirst()).getAngularVelocity(new Vector3d());
    }

    Vector3d assemblyVelocity() {
        var sum = new Vector3d();
        double mass = 0;
        for (var body : bodies) {
            double m = body.getMassTracker().getMass();
            sum.fma(m, RigidBodyHandle.of(body).getLinearVelocity(new Vector3d()));
            mass += m;
        }
        return sum.div(mass);
    }

    Vector3d assemblyPosition() {
        var sum = new Vector3d();
        double mass = 0;
        for (var body : bodies) {
            double m = body.getMassTracker().getMass();
            sum.fma(
                    m,
                    body.logicalPose()
                            .transformPosition(
                                    body.getMassTracker().getCenterOfMass(), new Vector3d()));
            mass += m;
        }
        return sum.div(mass);
    }

    /** Mass-weighted landing COM relative to the model pivot, using native child COM/anchors. */
    Vector3d landingMassOffset(double radius, double down) {
        var angles = new double[12];
        double[] thetaDegrees = {-55, 55, -125, 125};
        for (int leg = 0; leg < 4; leg++) {
            double theta = Math.toRadians(thetaDegrees[leg]);
            double[] q =
                    LeaperGait.inverse(
                            leg, radius * Math.sin(theta), -down, -radius * Math.cos(theta));
            System.arraycopy(q, 0, angles, 3 * leg, 3);
        }
        return landingMassOffset(angles, heading());
    }

    Vector3d landingMassOffset(double[] angles, double landingHeading) {
        if (angles == null || angles.length != 12 || !Double.isFinite(landingHeading))
            throw new IllegalArgumentException("Invalid landing pose");
        for (double angle : angles)
            if (!Double.isFinite(angle))
                throw new IllegalArgumentException("Nonfinite landing pose");
        var root = bodies.getFirst();
        var rootOffset = coreOrientation().transformInverse(position().sub(corePivot()));
        double total = root.getMassTracker().getMass();
        var sum = rootOffset.mul(total);
        double[] thetaDegrees = {-55, 55, -125, 125};
        for (int leg = 0; leg < 4; leg++) {
            double theta = Math.toRadians(thetaDegrees[leg]);
            var hip = new Vector3d(1.03 * Math.sin(theta), .55, -1.03 * Math.cos(theta)).mul(scale);
            var q = Arrays.copyOfRange(angles, 3 * leg, 3 * leg + 3);
            var swivel = new Quaterniond().rotateY(-theta + q[0]);
            var upper = new Quaterniond(swivel).rotateX(Math.toRadians(-63) + q[1]);
            var lower = new Quaterniond(swivel).rotateX(Math.toRadians(-170) + q[1] + q[2]);
            var knee = upper.transform(new Vector3d(0, 4 * scale, 0)).add(hip);
            Quaterniond[] orientations = {swivel, upper, lower};
            for (int segment = 0; segment < 3; segment++) {
                var joint = joints.get(3 * leg + segment);
                var body = joint.child;
                double m = body.getMassTracker().getMass();
                var offset = new Vector3d(body.getMassTracker().getCenterOfMass()).sub(joint.b);
                orientations[segment].transform(offset).add(segment == 2 ? knee : hip);
                sum.fma(m, offset);
                total += m;
            }
        }
        return new Quaterniond().rotateY(-landingHeading).transform(sum.div(total));
    }

    final List<Map<String, Object>> leapImpulses = new ArrayList<>();

    double airDrag() {
        return DimensionPhysicsData.getUniversalDrag(level);
    }

    /** Resolved dimension gravity used to initialize Sable's native scene, in blocks/s squared. */
    Vector3d gravity() {
        var gravity = DimensionPhysicsData.getGravity(level);
        if (!gravity.isFinite()) throw new IllegalStateException("Nonfinite Leaper gravity");
        return gravity;
    }

    /**
     * One takeoff impulse, requested as a world-space velocity increment in blocks/s. The caller
     * owns grounded/cooldown checks and must suppress foot grips until touchdown. Run on the server
     * thread between physics steps. Returns the actual total mass used for the impulse.
     */
    double leapImpulse(Vector3d deltaVelocity) {
        return assemblyImpulse(deltaVelocity, true);
    }

    int terrainAssistImpulses;
    double terrainAssistDeltaTotal, terrainAssistMaximumHorizontal, terrainAssistMaximumVertical;
    int climbAssistImpulses;
    double climbAssistDeltaTotal, climbAssistMaximumHorizontal, climbAssistMaximumVertical;

    void climbAssistImpulse(Vector3d deltaVelocity, boolean braking) {
        if (deltaVelocity == null
                || !deltaVelocity.isFinite()
                || Math.hypot(deltaVelocity.x, deltaVelocity.z) > (braking ? 3.00001 : .60001)
                || deltaVelocity.y < -.70001
                || deltaVelocity.y > 1.25001)
            throw new IllegalArgumentException("Climb assistance exceeds bounded delta-v");
        if (braking) {
            var v = assemblyVelocity();
            if (Math.hypot(v.x + deltaVelocity.x, v.z + deltaVelocity.z)
                    > Math.hypot(v.x, v.z) + 1e-6)
                throw new IllegalArgumentException(
                        "Climb impact braking must remove horizontal energy");
        }
        assemblyImpulse(deltaVelocity, false);
        climbAssistImpulses++;
        climbAssistDeltaTotal += deltaVelocity.length();
        climbAssistMaximumHorizontal =
                Math.max(
                        climbAssistMaximumHorizontal, Math.hypot(deltaVelocity.x, deltaVelocity.z));
        climbAssistMaximumVertical =
                Math.max(climbAssistMaximumVertical, Math.abs(deltaVelocity.y));
    }

    void terrainAssistImpulse(Vector3d deltaVelocity) {
        if (deltaVelocity == null
                || !deltaVelocity.isFinite()
                || Math.hypot(deltaVelocity.x, deltaVelocity.z) > .301
                || deltaVelocity.y < 0
                || deltaVelocity.y > .451)
            throw new IllegalArgumentException("Terrain assist exceeds bounded delta-v");
        assemblyImpulse(deltaVelocity, false);
        terrainAssistImpulses++;
        terrainAssistDeltaTotal += deltaVelocity.length();
        terrainAssistMaximumHorizontal =
                Math.max(
                        terrainAssistMaximumHorizontal,
                        Math.hypot(deltaVelocity.x, deltaVelocity.z));
        terrainAssistMaximumVertical = Math.max(terrainAssistMaximumVertical, deltaVelocity.y);
    }

    int apexImpulses;
    double apexMaximumHorizontal, apexMaximumVertical;

    void apexImpulse(Vector3d deltaVelocity) {
        if (deltaVelocity == null
                || !deltaVelocity.isFinite()
                || Math.hypot(deltaVelocity.x, deltaVelocity.z) > 2.70001
                || deltaVelocity.y < -2.70001
                || deltaVelocity.y > 1.25001)
            throw new IllegalArgumentException("Apex assistance exceeds its acceleration envelope");
        assemblyImpulse(deltaVelocity, false);
        apexImpulses++;
        apexMaximumHorizontal =
                Math.max(apexMaximumHorizontal, Math.hypot(deltaVelocity.x, deltaVelocity.z));
        apexMaximumVertical = Math.max(apexMaximumVertical, Math.abs(deltaVelocity.y));
    }

    private double assemblyImpulse(Vector3d deltaVelocity, boolean takeoff) {
        if (deltaVelocity == null
                || !deltaVelocity.isFinite()
                || Math.hypot(deltaVelocity.x, deltaVelocity.z) > 55
                || deltaVelocity.y < (takeoff ? 0 : -2.70001)
                || deltaVelocity.y > 24)
            throw new IllegalArgumentException(
                    "Leaper takeoff requires horizontal delta <= 55 and vertical delta 0..24");
        if (!level.getServer().isSameThread() || SubLevelPhysicsSystem.IN_PHYSICS_STEP)
            throw new IllegalStateException(
                    "Leaper takeoff requires the server thread between physics steps");
        if (!valid() || bodies.size() != 13)
            throw new IllegalStateException("Leaper takeoff requires an intact 13-body rig");

        var handles = new RigidBodyHandle[13];
        var impulses = new Vector3d[13];
        double totalMass = 0;
        // Validate every body before releasing grips or applying any impulse.
        for (int i = 0; i < bodies.size(); i++) {
            var body = bodies.get(i);
            var handle = RigidBodyHandle.of(body);
            double mass = body.getMassTracker().getMass();
            if (handle == null
                    || !handle.isValid()
                    || !Double.isFinite(mass)
                    || mass <= 0
                    || mass > Float.MAX_VALUE
                    || !handle.getLinearVelocity(new Vector3d()).isFinite()
                    || !handle.getAngularVelocity(new Vector3d()).isFinite())
                throw new IllegalStateException("Invalid Leaper takeoff body " + i);
            var orientation = new Quaterniond(body.logicalPose().orientation());
            double norm = orientation.lengthSquared();
            if (!Double.isFinite(norm) || norm < 1e-12)
                throw new IllegalStateException("Invalid Leaper takeoff orientation " + i);
            // Sable ForceTotal/RigidBodyHandle impulses are body-local. Each differently rotated
            // link needs its own inverse transform to receive the same world-space delta-v.
            var impulse = orientation.transformInverse(new Vector3d(deltaVelocity).mul(mass));
            if (!impulse.isFinite()
                    || Math.abs(impulse.x) > Float.MAX_VALUE
                    || Math.abs(impulse.y) > Float.MAX_VALUE
                    || Math.abs(impulse.z) > Float.MAX_VALUE)
                throw new IllegalStateException("Unrepresentable Leaper takeoff impulse " + i);
            handles[i] = handle;
            impulses[i] = impulse;
            totalMass += mass;
        }
        if (!Double.isFinite(totalMass) || totalMass <= 0)
            throw new IllegalStateException("Invalid Leaper total takeoff mass");
        if (deltaVelocity.lengthSquared() == 0) return totalMass;
        var before = assemblyVelocity();
        var expected = new Vector3d(before).add(deltaVelocity);
        // Native contact rebounds can exceed commanded launch speeds. A bounded
        // recovery impulse must still be able to brake that existing motion.
        // Takeoffs and impulses that increase an over-limit component remain rejected.
        if (deltaVelocity.length() > 60
                || Math.hypot(expected.x, expected.z) > 50
                        && (takeoff || Math.hypot(expected.x, expected.z) > Math.hypot(before.x, before.z) + 1e-6)
                || expected.y > 24 && (takeoff || expected.y > before.y + 1e-6))
            throw new IllegalArgumentException("Leaper assembly takeoff speed exceeds bounded arc");
        // Remove only ground traction; the twelve articulated joints and their limits stay intact.
        if (takeoff) releaseFeet();
        for (int i = 0; i < handles.length; i++) handles[i].applyLinearImpulse(impulses[i]);
        if (!takeoff) return totalMass;
        var after = assemblyVelocity();
        var log = new LinkedHashMap<String, Object>();
        log.put("game_tick", level.getGameTime());
        log.put("phase", "server control between native physics intervals");
        log.put("delta_velocity", new double[] {deltaVelocity.x, deltaVelocity.y, deltaVelocity.z});
        log.put("mass", totalMass);
        log.put("com_velocity_before", new double[] {before.x, before.y, before.z});
        log.put("com_velocity_after_api", new double[] {after.x, after.y, after.z});
        var rows = new ArrayList<Object>();
        for (int i = 0; i < handles.length; i++) {
            var j = impulses[i];
            rows.add(
                    Map.of(
                            "body",
                            bodies.get(i).getUniqueId().toString(),
                            "mass",
                            bodies.get(i).getMassTracker().getMass(),
                            "local_impulse",
                            new double[] {j.x, j.y, j.z}));
        }
        log.put("bodies", rows);
        leapImpulses.add(log);
        return totalMass;
    }

    Vector3d toe(int leg) {
        return bodies.get(3 + 3 * leg)
                .logicalPose()
                .transformPosition(toeAnchors.get(leg), new Vector3d());
    }

    Vector3d boundedCommandToe(int leg) {
        double[] p =
                LeaperGait.forward(
                        leg, targets[3 * leg], targets[3 * leg + 1], targets[3 * leg + 2]);
        return coreOrientation().transform(new Vector3d(p[0], p[1], p[2]).mul(scale)).add(corePivot());
    }

    Vector3d localToe(int leg) {
        return bodies.getFirst()
                .logicalPose()
                .orientation()
                .transformInverse(toe(leg).sub(corePivot()));
    }

    boolean ownsBody(dev.ryanhcode.sable.sublevel.SubLevel body) {
        return bodies.contains(body);
    }

    Quaterniond coreOrientation() {
        return new Quaterniond(bodies.getFirst().logicalPose().orientation());
    }

    Vector3d corePivot() {
        // Both the core and hip joint anchors were assembled around this exact model pivot.
        var hip = joints.getFirst();
        var offset =
                new Vector3d(
                        Math.sin(Math.toRadians(-55)) * 1.03,
                        .55,
                        -Math.cos(Math.toRadians(-55)) * 1.03);
        offset.mul(scale);
        bodies.getFirst().logicalPose().orientation().transform(offset);
        return hip.parent.logicalPose().transformPosition(hip.a, new Vector3d()).sub(offset);
    }

    void captureResetPose() {
        initialPositions.clear();
        initialOrientations.clear();
        for (var b : bodies) {
            initialPositions.add(new Vector3d(b.logicalPose().position()));
            initialOrientations.add(new Quaterniond(b.logicalPose().orientation()));
        }
    }

    /** Episode initialization only; locomotion never teleports or anchors the chassis. */
    void reset(double heading) {
        if (balanceMotor != null && balanceMotor.isValid()) balanceMotor.remove();
        balanceMotor = null;
        releaseFeet();
        var center = initialPositions.getFirst();
        var rotation = new Quaterniond().rotateY(-heading);
        for (int i = 0; i < bodies.size(); i++) {
            var b = bodies.get(i);
            RigidBodyHandle.of(b)
                    .teleport(
                            rotation.transform(new Vector3d(initialPositions.get(i)).sub(center))
                                    .add(center),
                            new Quaterniond(rotation).mul(initialOrientations.get(i)));
            SubLevelPhysicsSystem.get(level).getPipeline().resetVelocity(b);
            b.updateLastPose();
        }
        Arrays.fill(targets, 0);
        command(new double[12], .05);
    }

    void disturb(double vx, double vz, double roll) {
        RigidBodyHandle.of(bodies.getFirst())
                .addLinearAndAngularVelocity(new Vector3d(vx, 0, vz), new Vector3d(0, 0, roll));
    }

    boolean valid() {
        return !closed && joints.size() == 12 && joints.stream().allMatch(j -> j.handle.isValid());
    }

    int bodyCount() {
        return bodies.size();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (balanceMotor != null && balanceMotor.isValid()) balanceMotor.remove();
        balanceMotor = null;
        releaseFeet();
        for (var j : joints) if (j.handle.isValid()) j.handle.remove();
        var container = SubLevelContainer.getContainer(level);
        for (var body : bodies)
            if (!body.isRemoved()) container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        container.processSubLevelRemovals();
        if (predatorPhysics != null) predatorPhysics.close();
        predatorPhysics = null;
    }
}
