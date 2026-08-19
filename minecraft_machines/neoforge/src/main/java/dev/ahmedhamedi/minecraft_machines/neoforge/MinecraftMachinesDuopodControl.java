package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.servo.RoboticServoJointBlockEntity;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodActionDecoder;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodInstance;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.DuopodKinematics;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod.ServoAngleLimits;
import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoTelemetrySample;
import dev.ahmedhamedi.minecraft_machines.content.training.environment.StandingDisturbance;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;

final class MinecraftMachinesDuopodControl {
    // Policy actions and telemetry use a mirrored body frame; the right servo is mounted facing the opposite way.
    private static final double LEFT_SERVO_FRAME_SIGN = 1.0;
    private static final double RIGHT_SERVO_FRAME_SIGN = -1.0;

    private final ServerLevel level;
    private DuopodInstance duopod;

    MinecraftMachinesDuopodControl(final ServerLevel level, final DuopodInstance duopod) {
        this.level = level;
        this.duopod = duopod;
    }

    DuopodInstance duopod() {
        return this.duopod;
    }

    void setNormalizedAction(final double left, final double right) {
        final RoboticServoJointBlockEntity leftServo = this.leftServo();
        final RoboticServoJointBlockEntity rightServo = this.rightServo();
        if (leftServo == null || rightServo == null) {
            return;
        }
        final DuopodActionDecoder.DecodedDuopodAction decoded = DuopodActionDecoder.decode(
                new double[]{
                        toServoFrame(left, LEFT_SERVO_FRAME_SIGN),
                        toServoFrame(right, RIGHT_SERVO_FRAME_SIGN)
                },
                new ServoAngleLimits(leftServo.getMinimumAngleDegrees(), leftServo.getMaximumAngleDegrees()),
                new ServoAngleLimits(rightServo.getMinimumAngleDegrees(), rightServo.getMaximumAngleDegrees()));
        leftServo.setTargetAngleDegrees(decoded.leftTargetDegrees());
        rightServo.setTargetAngleDegrees(decoded.rightTargetDegrees());
        this.refreshServoPositions(leftServo, rightServo);
    }

    void setLeftTargetAngleDegrees(final double angle) {
        final RoboticServoJointBlockEntity servo = this.leftServo();
        if (servo != null) {
            servo.setTargetAngleDegrees(toServoFrame(angle, LEFT_SERVO_FRAME_SIGN));
            this.duopod = this.duopod.withServoPositions(servo.getBlockPos(), this.duopod.rightServoPosition());
        }
    }

    void setRightTargetAngleDegrees(final double angle) {
        final RoboticServoJointBlockEntity servo = this.rightServo();
        if (servo != null) {
            servo.setTargetAngleDegrees(toServoFrame(angle, RIGHT_SERVO_FRAME_SIGN));
            this.duopod = this.duopod.withServoPositions(this.duopod.leftServoPosition(), servo.getBlockPos());
        }
    }

    double getLeftActualAngleDegrees() {
        final RoboticServoJointBlockEntity servo = this.leftServo();
        return servo == null ? 0.0 : toSemanticFrame(servo.getActualAngleDegrees(), LEFT_SERVO_FRAME_SIGN);
    }

    double getRightActualAngleDegrees() {
        final RoboticServoJointBlockEntity servo = this.rightServo();
        return servo == null ? 0.0 : toSemanticFrame(servo.getActualAngleDegrees(), RIGHT_SERVO_FRAME_SIGN);
    }

    double getLeftAngularVelocityRadPerSecond() {
        final RoboticServoJointBlockEntity servo = this.leftServo();
        return servo == null ? 0.0 : toSemanticFrame(servo.getAngularVelocityRadPerSecond(), LEFT_SERVO_FRAME_SIGN);
    }

    double getRightAngularVelocityRadPerSecond() {
        final RoboticServoJointBlockEntity servo = this.rightServo();
        return servo == null ? 0.0 : toSemanticFrame(servo.getAngularVelocityRadPerSecond(), RIGHT_SERVO_FRAME_SIGN);
    }

    double getLeftLoad() {
        final RoboticServoJointBlockEntity servo = this.leftServo();
        return servo == null ? 0.0 : servo.getJointLoad();
    }

    double getRightLoad() {
        final RoboticServoJointBlockEntity servo = this.rightServo();
        return servo == null ? 0.0 : servo.getJointLoad();
    }

    ServoTelemetrySample getLeftTelemetrySample(final double previousAction) {
        return this.telemetrySample("left", this.leftServo(), previousAction, LEFT_SERVO_FRAME_SIGN);
    }

    ServoTelemetrySample getRightTelemetrySample(final double previousAction) {
        return this.telemetrySample("right", this.rightServo(), previousAction, RIGHT_SERVO_FRAME_SIGN);
    }

    Vec3 getBasePosition() {
        final Vec3 position = MinecraftMachinesDuopodSpawner.getBodyPosition(this.level, this.duopod.baseSubLevelId());
        return position == null ? Vec3.ZERO : position;
    }

    Vec3 getLeftChildPosition() {
        final Vec3 position = MinecraftMachinesDuopodSpawner.getBodyPosition(this.level, this.duopod.leftChildSubLevelId());
        return position == null ? Vec3.ZERO : position;
    }

    Vec3 getRightChildPosition() {
        final Vec3 position = MinecraftMachinesDuopodSpawner.getBodyPosition(this.level, this.duopod.rightChildSubLevelId());
        return position == null ? Vec3.ZERO : position;
    }

    Vec3 getLeftHoneyTipPosition() {
        return this.honeyTipPosition(this.duopod.leftChildSubLevelId(), this.duopod.leftHoneyTipLocalOffsetView());
    }

    Vec3 getRightHoneyTipPosition() {
        return this.honeyTipPosition(this.duopod.rightChildSubLevelId(), this.duopod.rightHoneyTipLocalOffsetView());
    }

    Vec3 getLeftServoPosition() {
        return this.servoWorldPosition(this.leftServo());
    }

    Vec3 getRightServoPosition() {
        return this.servoWorldPosition(this.rightServo());
    }

    Vec3 getAggregateCenterOfMassPosition() {
        final Vec3 fallback = this.getBasePosition();
        double weightedX = 0.0;
        double weightedY = 0.0;
        double weightedZ = 0.0;
        double totalMass = 0.0;

        for (final UUID subLevelId : new UUID[]{
                this.duopod.baseSubLevelId(),
                this.duopod.leftChildSubLevelId(),
                this.duopod.rightChildSubLevelId()
        }) {
            final MassPoint sample = this.massPoint(subLevelId);
            if (sample == null) {
                continue;
            }
            weightedX += sample.position().x * sample.mass();
            weightedY += sample.position().y * sample.mass();
            weightedZ += sample.position().z * sample.mass();
            totalMass += sample.mass();
        }

        if (!Double.isFinite(totalMass) || totalMass <= 0.0) {
            return fallback;
        }
        return new Vec3(weightedX / totalMass, weightedY / totalMass, weightedZ / totalMass);
    }

    Quaterniondc getBaseOrientation() {
        final Quaterniond orientation = MinecraftMachinesDuopodSpawner.getBodyOrientation(this.level, this.duopod.baseSubLevelId());
        return orientation == null ? new Quaterniond() : orientation;
    }

    Vector3dc getBaseLinearVelocity() {
        return new Vector3d(MinecraftMachinesDuopodSpawner.getLinearVelocity(this.level, this.duopod.baseSubLevelId()));
    }

    Vector3dc getBaseAngularVelocity() {
        return new Vector3d(MinecraftMachinesDuopodSpawner.getAngularVelocity(this.level, this.duopod.baseSubLevelId()));
    }

    boolean isValid() {
        return MinecraftMachinesDuopodSpawner.getServerSubLevel(this.level, this.duopod.baseSubLevelId()) != null
                && MinecraftMachinesDuopodSpawner.getServerSubLevel(this.level, this.duopod.leftChildSubLevelId()) != null
                && MinecraftMachinesDuopodSpawner.getServerSubLevel(this.level, this.duopod.rightChildSubLevelId()) != null
                && this.leftServo() != null
                && this.rightServo() != null;
    }

    void commandNeutral() {
        this.setNormalizedAction(0.0, 0.0);
    }

    boolean applyStandingDisturbance(final StandingDisturbance disturbance, final boolean delayed) {
        final DuopodKinematics.Axes axes = DuopodKinematics.worldAxes(
                this.duopod.modelForwardDirection(),
                this.getBaseOrientation());
        final double linearForward = delayed
                ? disturbance.delayedLinearImpulseForward()
                : disturbance.initialLinearImpulseForward();
        final double linearRight = delayed
                ? disturbance.delayedLinearImpulseRight()
                : disturbance.initialLinearImpulseRight();
        final double angularRollImpulse = delayed
                ? disturbance.delayedAngularImpulseRoll()
                : disturbance.initialAngularImpulseRoll() + 0.10 * disturbance.rollRadians();
        final double angularPitchImpulse = delayed
                ? disturbance.delayedAngularImpulsePitch()
                : disturbance.initialAngularImpulsePitch() + 0.10 * disturbance.pitchRadians();

        final Vector3d linearImpulse = localVector(axes, linearForward, linearRight, 0.0);
        final Vector3d angularImpulse = localVector(axes, angularRollImpulse, angularPitchImpulse, 0.0);
        final boolean impulseApplied = MinecraftMachinesDuopodSpawner.applyLinearAndAngularImpulse(
                this.level,
                this.duopod.baseSubLevelId(),
                linearImpulse,
                angularImpulse);

        if (!delayed) {
            final Vector3d angularVelocity = localVector(
                    axes,
                    disturbance.rollAngularVelocityRadPerSecond(),
                    disturbance.pitchAngularVelocityRadPerSecond(),
                    0.0);
            MinecraftMachinesDuopodSpawner.addLinearAndAngularVelocity(
                    this.level,
                    this.duopod.baseSubLevelId(),
                    new Vector3d(),
                    angularVelocity);
        }
        return impulseApplied;
    }

    void disable() {
        final RoboticServoJointBlockEntity left = this.leftServo();
        final RoboticServoJointBlockEntity right = this.rightServo();
        if (left != null) {
            left.setEnabled(false);
        }
        if (right != null) {
            right.setEnabled(false);
        }
    }

    void destroy() {
        MinecraftMachinesDuopodSpawner.removeDuopod(this.level, this.duopod);
    }

    private static Vector3d localVector(
            final DuopodKinematics.Axes axes,
            final double forward,
            final double right,
            final double up
    ) {
        return new Vector3d(axes.forward()).mul(forward)
                .fma(right, axes.right())
                .fma(up, axes.up());
    }

    private static double toServoFrame(final double semanticValue, final double servoFrameSign) {
        return semanticValue * servoFrameSign;
    }

    private static double toSemanticFrame(final double servoValue, final double servoFrameSign) {
        return servoValue * servoFrameSign;
    }

    private static double semanticMinimumAngleDegrees(
            final double servoMinimumDegrees,
            final double servoMaximumDegrees,
            final double servoFrameSign
    ) {
        return Math.min(
                toSemanticFrame(servoMinimumDegrees, servoFrameSign),
                toSemanticFrame(servoMaximumDegrees, servoFrameSign));
    }

    private static double semanticMaximumAngleDegrees(
            final double servoMinimumDegrees,
            final double servoMaximumDegrees,
            final double servoFrameSign
    ) {
        return Math.max(
                toSemanticFrame(servoMinimumDegrees, servoFrameSign),
                toSemanticFrame(servoMaximumDegrees, servoFrameSign));
    }

    private RoboticServoJointBlockEntity leftServo() {
        return MinecraftMachinesServoLocator.resolve(
                this.level,
                this.duopod.leftServoPosition(),
                this.duopod.leftServoInstanceId(),
                this.duopod.leftChildSubLevelId());
    }

    private RoboticServoJointBlockEntity rightServo() {
        return MinecraftMachinesServoLocator.resolve(
                this.level,
                this.duopod.rightServoPosition(),
                this.duopod.rightServoInstanceId(),
                this.duopod.rightChildSubLevelId());
    }

    private Vec3 honeyTipPosition(final java.util.UUID childSubLevelId, final Vector3dc localOffset) {
        final Vec3 childPosition = MinecraftMachinesDuopodSpawner.getBodyPosition(this.level, childSubLevelId);
        final Quaterniondc childOrientation = MinecraftMachinesDuopodSpawner.getBodyOrientation(this.level, childSubLevelId);
        if (childPosition == null || childOrientation == null) {
            return Vec3.ZERO;
        }
        final Vector3d worldOffset = new Vector3d(localOffset);
        childOrientation.transform(worldOffset);
        return new Vec3(
                childPosition.x + worldOffset.x(),
                childPosition.y + worldOffset.y(),
                childPosition.z + worldOffset.z());
    }

    private Vec3 servoWorldPosition(final RoboticServoJointBlockEntity servo) {
        if (servo == null) {
            return Vec3.ZERO;
        }
        final Vec3 subLevelPosition = Vec3.atCenterOf(servo.getBlockPos());
        final ServerSubLevel baseSubLevel = MinecraftMachinesDuopodSpawner.getServerSubLevel(this.level, this.duopod.baseSubLevelId());
        return baseSubLevel == null ? subLevelPosition : baseSubLevel.logicalPose().transformPosition(subLevelPosition);
    }

    private MassPoint massPoint(final UUID subLevelId) {
        final ServerSubLevel subLevel = MinecraftMachinesDuopodSpawner.getServerSubLevel(this.level, subLevelId);
        if (subLevel == null) {
            return null;
        }
        final MassData massData = subLevel.getMassTracker();
        if (massData == null || massData.isInvalid()) {
            return null;
        }
        final double mass = massData.getMass();
        final Vector3dc localCenterOfMass = massData.getCenterOfMass();
        if (!Double.isFinite(mass) || mass <= 0.0 || localCenterOfMass == null) {
            return null;
        }
        final Vector3d worldCenterOfMass = subLevel.logicalPose().transformPosition(localCenterOfMass, new Vector3d());
        if (!Double.isFinite(worldCenterOfMass.x())
                || !Double.isFinite(worldCenterOfMass.y())
                || !Double.isFinite(worldCenterOfMass.z())) {
            return null;
        }
        return new MassPoint(new Vec3(worldCenterOfMass.x(), worldCenterOfMass.y(), worldCenterOfMass.z()), mass);
    }

    private void refreshServoPositions(final RoboticServoJointBlockEntity leftServo, final RoboticServoJointBlockEntity rightServo) {
        this.duopod = this.duopod.withServoPositions(leftServo.getBlockPos(), rightServo.getBlockPos());
    }

    private ServoTelemetrySample telemetrySample(
            final String name,
            final RoboticServoJointBlockEntity servo,
            final double previousAction,
            final double servoFrameSign
    ) {
        if (servo == null) {
            return new ServoTelemetrySample(
                    name,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    -1.0,
                    1.0,
                    1.0,
                    false,
                    false,
                    false,
                    previousAction);
        }
        final double minimumAngleDegrees = semanticMinimumAngleDegrees(
                servo.getMinimumAngleDegrees(),
                servo.getMaximumAngleDegrees(),
                servoFrameSign);
        final double maximumAngleDegrees = semanticMaximumAngleDegrees(
                servo.getMinimumAngleDegrees(),
                servo.getMaximumAngleDegrees(),
                servoFrameSign);
        return new ServoTelemetrySample(
                name,
                toSemanticFrame(servo.getTargetAngleDegrees(), servoFrameSign),
                toSemanticFrame(servo.getActualAngleDegrees(), servoFrameSign),
                toSemanticFrame(servo.getAngleErrorDegrees(), servoFrameSign),
                toSemanticFrame(servo.getAngularVelocityRadPerSecond(), servoFrameSign),
                toSemanticFrame(servo.getGeneratedSpeed(), servoFrameSign),
                toSemanticFrame(servo.getEstimatedTorque(), servoFrameSign),
                servo.getJointLoad(),
                minimumAngleDegrees,
                maximumAngleDegrees,
                servo.getMaxTorque(),
                servo.isEnabled(),
                servo.getAttachedSubLevelId() != null,
                servo.hasValidConstraint(),
                previousAction);
    }

    private record MassPoint(Vec3 position, double mass) {
    }
}
