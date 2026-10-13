package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Eight physical motor joints. The floating chassis has all six degrees of freedom. */
final class MinecraftMachinesQuadruped implements AutoCloseable {
    static final String CONTRACT = "sable_quadruped_8dof_v1";
    private final ServerLevel level;
    private final List<ServerSubLevel> bodies = new ArrayList<>();
    private final List<RotaryConstraintHandle> joints = new ArrayList<>();
    private final List<Vector3d> initialPositions = new ArrayList<>();
    private final List<Quaterniond> initialOrientations = new ArrayList<>();
    private final double[] targets = new double[8];
    private boolean closed;

    private MinecraftMachinesQuadruped(ServerLevel level) { this.level = level; }

    /** Center is the chassis block center; feet begin four blocks below it. */
    static MinecraftMachinesQuadruped spawn(ServerLevel level, BlockPos center) {
        MinecraftMachinesQuadruped rig = new MinecraftMachinesQuadruped(level);
        Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        try {
            List<BlockPos> chassis = new ArrayList<>();
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) chassis.add(center.offset(x, 0, z));
            }
            rig.assemble(chassis, Blocks.OAK_PLANKS.defaultBlockState(), originals);
            for (int leg = 0; leg < 4; leg++) {
                int x = leg % 2 == 0 ? -3 : 3;
                int z = leg < 2 ? -2 : 2;
                rig.assemble(List.of(center.offset(x, 0, z), center.offset(x, -1, z)),
                        Blocks.OAK_PLANKS.defaultBlockState(), originals);
                rig.assemble(List.of(center.offset(x, -2, z), center.offset(x, -3, z)),
                        Blocks.OAK_PLANKS.defaultBlockState(), originals);
                Vector3d hip = new Vector3d(center.getX() + x + 0.5, center.getY() + 0.5, center.getZ() + z + 0.5);
                Vector3d knee = new Vector3d(hip).sub(0, 2, 0);
                rig.connect(rig.bodies.getFirst(), rig.bodies.get(1 + 2 * leg), hip);
                rig.connect(rig.bodies.get(1 + 2 * leg), rig.bodies.get(2 + 2 * leg), knee);
            }
            for (ServerSubLevel body : rig.bodies) {
                rig.initialPositions.add(new Vector3d(body.logicalPose().position()));
                rig.initialOrientations.add(new Quaterniond(body.logicalPose().orientation()));
            }
            rig.command(new double[8]);
            return rig;
        } catch (RuntimeException e) {
            rig.close();
            originals.forEach(level::setBlockAndUpdate);
            throw e;
        }
    }

    private void assemble(List<BlockPos> blocks, BlockState material, Map<BlockPos, BlockState> originals) {
        for (BlockPos block : blocks) {
            if (!level.getBlockState(block).canBeReplaced()) throw new IllegalStateException("Quadruped spawn obstructed: " + block);
            originals.put(block, level.getBlockState(block));
            if (!level.setBlockAndUpdate(block, material)) throw new IllegalStateException("Cannot place " + block);
        }
        ServerSubLevel body = SubLevelAssemblyHelper.assembleBlocks(level, blocks.getFirst(), blocks, BoundingBox3i.from(blocks));
        if (body == null) throw new IllegalStateException("Sable refused quadruped assembly");
        bodies.add(body);
        body.setName("Minecraft Machines Quadruped " + (bodies.size() - 1));
    }

    private void connect(ServerSubLevel parent, ServerSubLevel child, Vector3d worldAnchor) {
        // Sable joint anchors are coordinates in each sublevel's block plot, not COM offsets.
        Vector3d a = parent.logicalPose().transformPositionInverse(worldAnchor, new Vector3d());
        Vector3d b = child.logicalPose().transformPositionInverse(worldAnchor, new Vector3d());
        RotaryConstraintHandle joint = SubLevelPhysicsSystem.get(level).getPipeline().addConstraint(parent, child,
                new RotaryConstraintConfiguration(a, b, new Vector3d(1, 0, 0), new Vector3d(1, 0, 0)));
        if (joint == null || !joint.isValid()) throw new IllegalStateException("Invalid quadruped joint");
        joints.add(joint);
        joint.setContactsEnabled(false);
    }

    void command(double[] radians) {
        if (radians.length != 8) throw new IllegalArgumentException("Expected eight targets");
        for (double value : radians) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite joint target");
        for (ServerSubLevel body : bodies) SubLevelPhysicsSystem.get(level).getPipeline().wakeUp(body);
        for (int j = 0; j < 8; j++) {
            targets[j] = Math.clamp(radians[j], -1.3, 1.3);
            // Force-limited native motors, with explicit gains in physical units.
            joints.get(j).setMotor(RotaryConstraintHandle.DEFAULT_AXIS, targets[j], 25000.0, 2500.0, true, 150000.0);
        }
    }

    double[] angles() {
        double[] result = new double[8];
        for (int leg = 0; leg < 4; leg++) {
            result[2 * leg] = relativeAngle(bodies.getFirst(), bodies.get(1 + 2 * leg));
            result[2 * leg + 1] = relativeAngle(bodies.get(1 + 2 * leg), bodies.get(2 + 2 * leg));
        }
        return result;
    }

    private static double relativeAngle(ServerSubLevel a, ServerSubLevel b) {
        Quaterniond relative = new Quaterniond(a.logicalPose().orientation()).invert().mul(b.logicalPose().orientation());
        return Math.atan2(Math.sin(2 * Math.atan2(relative.x, relative.w)), Math.cos(2 * Math.atan2(relative.x, relative.w)));
    }

    Vector3d position() {
        var body = bodies.getFirst();
        return body.logicalPose().transformPosition(body.getMassTracker().getCenterOfMass(), new Vector3d());
    }
    Vector3d velocity() { return RigidBodyHandle.of(bodies.getFirst()).getLinearVelocity(new Vector3d()); }
    Vector3d angularVelocity() { return RigidBodyHandle.of(bodies.getFirst()).getAngularVelocity(new Vector3d()); }
    Vector3d up() { return bodies.getFirst().logicalPose().orientation().transform(new Vector3d(0, 1, 0)); }
    double heading() {
        Vector3d forward = bodies.getFirst().logicalPose().orientation().transform(new Vector3d(0, 0, -1));
        return Math.atan2(forward.x, -forward.z);
    }
    double upright() { return up().y; }
    int bodyCount() { return bodies.size(); }
    int jointCount() { return joints.size(); }
    boolean valid() { return !closed && joints.stream().allMatch(RotaryConstraintHandle::isValid); }

    void captureResetPose() {
        initialPositions.clear(); initialOrientations.clear();
        for (ServerSubLevel body : bodies) {
            initialPositions.add(new Vector3d(body.logicalPose().position()));
            initialOrientations.add(new Quaterniond(body.logicalPose().orientation()));
        }
    }

    void disturb(double lateralVelocity, double rollVelocity) {
        RigidBodyHandle.of(bodies.getFirst()).addLinearAndAngularVelocity(new Vector3d(lateralVelocity, 0, 0), new Vector3d(0, 0, rollVelocity));
    }

    /** Evaluation-only initial condition; never used by the walking controller. */
    void rotateInitialHeading(double radians) {
        var rotation = new Quaterniond().rotateY(radians);
        var center = position();
        for (ServerSubLevel body : bodies) {
            var position = new Vector3d(body.logicalPose().position()).sub(center);
            rotation.transform(position).add(center);
            var orientation = new Quaterniond(rotation).mul(body.logicalPose().orientation());
            RigidBodyHandle.of(body).teleport(position, orientation);
        }
        for (ServerSubLevel body : bodies) {
            SubLevelPhysicsSystem.get(level).getPipeline().resetVelocity(body);
            body.updateLastPose();
        }
        command(new double[8]);
    }

    void reset() {
        for (int i = 0; i < bodies.size(); i++) RigidBodyHandle.of(bodies.get(i)).teleport(initialPositions.get(i), initialOrientations.get(i));
        for (ServerSubLevel body : bodies) {
            SubLevelPhysicsSystem.get(level).getPipeline().resetVelocity(body);
            body.updateLastPose();
        }
        command(new double[8]);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (RotaryConstraintHandle joint : joints) if (joint.isValid()) joint.remove();
        var container = SubLevelContainer.getContainer(level);
        for (ServerSubLevel body : bodies) if (!body.isRemoved()) container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        container.processSubLevelRemovals();
    }
}
