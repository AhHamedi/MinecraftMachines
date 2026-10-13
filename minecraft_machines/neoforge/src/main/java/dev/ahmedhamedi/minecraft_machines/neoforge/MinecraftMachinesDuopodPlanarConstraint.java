package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.MinecraftMachines;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Locks a training Duopod's chassis to a deliberately reduced motion space.
 *
 * <p>The world constraint removes lateral translation, roll, and yaw from the
 * central body at the physics solver. The existing one-axis leg joints keep the
 * two limbs in that same plane. Forward and vertical translation and pitch
 * remain free, so the servos retain their intended walking motion without
 * over-constraining the articulated system.</p>
 */
final class MinecraftMachinesDuopodPlanarConstraint implements AutoCloseable {
    static final String MODE_ID = "minecraft_machines:duopod_planar_forward_up_v1";
    static final String VERTICAL_ONLY_MODE_ID = "minecraft_machines:duopod_vertical_pitch_only_v1";

    private static final List<MinecraftMachinesDuopodPlanarConstraint> PENDING = new ArrayList<>();

    private final ServerLevel level;
    private final DuopodInstance duopod;
    private final MotionMode motionMode;
    private final long spawnGameTime;
    private List<GenericConstraintHandle> handles = List.of();
    private String failure;
    private boolean closed;

    private MinecraftMachinesDuopodPlanarConstraint(
            final ServerLevel level,
            final DuopodInstance duopod,
            final MotionMode motionMode
    ) {
        this.level = level;
        this.duopod = duopod;
        this.motionMode = motionMode;
        this.spawnGameTime = level.getGameTime();
    }

    static MinecraftMachinesDuopodPlanarConstraint attach(
            final ServerLevel level,
            final DuopodInstance duopod
    ) {
        return attach(level, duopod, MotionMode.PLANAR_FORWARD_UP);
    }

    static MinecraftMachinesDuopodPlanarConstraint attach(
            final ServerLevel level,
            final DuopodInstance duopod,
            final MotionMode motionMode
    ) {
        final MinecraftMachinesDuopodPlanarConstraint constraint =
                new MinecraftMachinesDuopodPlanarConstraint(level, duopod, motionMode);
        // The swivel and servo joints replace their assembly-time world handles on the
        // first full server tick. Adding another native constraint before that handoff
        // can reuse one of those stale native ids. Defer by one tick so every existing
        // joint owns its final body-to-body handle before the planar joint is created.
        PENDING.add(constraint);
        return constraint;
    }

    static void tick(final ServerTickEvent.Pre event) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (final MinecraftMachinesDuopodPlanarConstraint constraint : List.copyOf(PENDING)) {
            if (constraint.closed || constraint.level.getGameTime() > constraint.spawnGameTime) {
                PENDING.remove(constraint);
                if (!constraint.closed) {
                    constraint.attachNow();
                }
            }
        }
    }

    boolean isValid() {
        if (this.closed || this.failure != null) {
            return false;
        }
        if (this.handles.isEmpty()) {
            return this.hasAllBodies();
        }
        return this.handles.size() == 1 && this.handles.getFirst().isValid();
    }

    int bodyConstraintCount() {
        return this.handles.size();
    }

    int validBodyConstraintCount() {
        return (int) this.handles.stream().filter(GenericConstraintHandle::isValid).count();
    }

    String modeId() {
        return this.motionMode.modeId();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        PENDING.remove(this);
        removeHandles(this.handles);
        this.handles = List.of();
    }

    private void attachNow() {
        final SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(this.level);
        if (physicsSystem == null) {
            this.fail("physics system is unavailable");
            return;
        }

        final Vector3d planeNormal = spawnPlaneNormal(this.duopod);
        final Quaterniond worldFrame = worldConstraintFrame(planeNormal);
        final List<GenericConstraintHandle> attached = new ArrayList<>(1);
        try {
            attachBody(this.level, physicsSystem.getPipeline(), this.duopod.baseSubLevelId(), worldFrame, this.motionMode, attached);
            this.handles = List.copyOf(attached);
        } catch (final RuntimeException e) {
            removeHandles(attached);
            this.fail(e.getMessage());
        }
    }

    private boolean hasAllBodies() {
        return MinecraftMachinesDuopodSpawner.getServerSubLevel(this.level, this.duopod.baseSubLevelId()) != null
                && MinecraftMachinesDuopodSpawner.getServerSubLevel(this.level, this.duopod.leftChildSubLevelId()) != null
                && MinecraftMachinesDuopodSpawner.getServerSubLevel(this.level, this.duopod.rightChildSubLevelId()) != null;
    }

    private void fail(final String message) {
        this.failure = message == null || message.isBlank() ? "unknown planar-constraint failure" : message;
        MinecraftMachines.LOGGER.error(
                "MM_DUOPOD_PLANAR_CONSTRAINT_FAILED machine={} failure={}",
                this.duopod.machineId(),
                this.failure);
    }

    private static void attachBody(
            final ServerLevel level,
            final PhysicsPipeline pipeline,
            final UUID subLevelId,
            final Quaterniond worldFrame,
            final MotionMode motionMode,
            final List<GenericConstraintHandle> handles
    ) {
        final ServerSubLevel body = MinecraftMachinesDuopodSpawner.getServerSubLevel(level, subLevelId);
        if (body == null) {
            throw new IllegalStateException("cannot constrain missing Duopod body " + subLevelId);
        }
        final MassData massData = body.getMassTracker();
        if (massData == null || massData.isInvalid() || massData.getCenterOfMass() == null) {
            throw new IllegalStateException("cannot constrain Duopod body without valid mass data " + subLevelId);
        }

        final Vector3d localAnchor = new Vector3d(massData.getCenterOfMass());
        final Vector3d worldAnchor = body.logicalPose().transformPosition(localAnchor, new Vector3d());
        final Quaterniond bodyLocalFrame = new Quaterniond(body.logicalPose().orientation())
                .invert()
                .mul(worldFrame)
                .normalize();
        final GenericConstraintConfiguration configuration = new GenericConstraintConfiguration(
                worldAnchor,
                localAnchor,
                new Quaterniond(worldFrame),
                bodyLocalFrame,
                EnumSet.copyOf(motionMode.lockedAxes()));
        final GenericConstraintHandle handle = pipeline.addConstraint(null, body, configuration);
        if (handle == null || !handle.isValid()) {
            throw new IllegalStateException("physics engine rejected the planar constraint for Duopod body " + subLevelId);
        }
        handles.add(handle);
    }

    private static Vector3d spawnPlaneNormal(final DuopodInstance duopod) {
        final Direction right = duopod.modelRightDirection();
        final Vector3d normal = new Vector3d(right.getStepX(), right.getStepY(), right.getStepZ());
        duopod.spawnOrientationView().transform(normal);
        if (!normal.isFinite() || normal.lengthSquared() < 1.0e-12) {
            throw new IllegalStateException("Duopod spawn frame does not define a finite 2D plane");
        }
        return normal.normalize();
    }

    private static Quaterniond worldConstraintFrame(final Vector3dc planeNormal) {
        final Vector3d up = new Vector3d(0.0, 1.0, 0.0);
        final Vector3d backward = new Vector3d(planeNormal).cross(up).normalize();
        final Matrix3d axes = new Matrix3d()
                .setColumn(0, planeNormal)
                .setColumn(1, up)
                .setColumn(2, backward);
        return axes.getNormalizedRotation(new Quaterniond()).normalize();
    }

    private static void removeHandles(final List<GenericConstraintHandle> handles) {
        for (final GenericConstraintHandle handle : handles) {
            if (handle != null && handle.isValid()) {
                handle.remove();
            }
        }
    }

    enum MotionMode {
        /** Lateral translation, roll, and yaw locked; forward/up and pitch remain free. */
        PLANAR_FORWARD_UP(MODE_ID, EnumSet.of(
                ConstraintJointAxis.LINEAR_X,
                ConstraintJointAxis.ANGULAR_Y,
                ConstraintJointAxis.ANGULAR_Z)),
        /** Both horizontal translations locked; only vertical translation and pitch remain free. */
        VERTICAL_PITCH_ONLY(VERTICAL_ONLY_MODE_ID, EnumSet.of(
                ConstraintJointAxis.LINEAR_X,
                ConstraintJointAxis.LINEAR_Z,
                ConstraintJointAxis.ANGULAR_Y,
                ConstraintJointAxis.ANGULAR_Z));

        private final String modeId;
        private final EnumSet<ConstraintJointAxis> lockedAxes;

        MotionMode(final String modeId, final EnumSet<ConstraintJointAxis> lockedAxes) {
            this.modeId = modeId;
            this.lockedAxes = lockedAxes;
        }

        String modeId() {
            return this.modeId;
        }

        EnumSet<ConstraintJointAxis> lockedAxes() {
            return EnumSet.copyOf(this.lockedAxes);
        }
    }
}
