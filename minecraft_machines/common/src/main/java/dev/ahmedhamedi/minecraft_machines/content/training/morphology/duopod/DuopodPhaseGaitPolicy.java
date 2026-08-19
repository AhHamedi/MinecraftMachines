package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

import dev.ahmedhamedi.minecraft_machines.content.training.api.ServoFirstObservationEncoder;
import dev.ahmedhamedi.minecraft_machines.content.training.cem.GenomePolicy;

import java.util.Arrays;

public final class DuopodPhaseGaitPolicy implements GenomePolicy {
    public static final String POLICY_TYPE = "duopod_phase_gait_v2";
    public static final int GENOME_SIZE = 7;

    private static final int PHASE_SIN_INDEX = 0;
    private static final int PHASE_COS_INDEX = 1;
    private static final int DESIRED_FORWARD_INDEX = 2;
    private static final double MAX_TRAINING_FORWARD_VELOCITY = 1.10;
    private static final double MAX_SPEED_AMPLITUDE_GAIN = 0.60;
    private static final double MIN_SPEED_SCALE = 0.40;
    private static final double MAX_SPEED_SCALE = 1.60;

    @Override
    public int observationSize() {
        return DuopodSchemas.observationSpec().size();
    }

    @Override
    public int actionSize() {
        return DuopodSchemas.actionSpec().size();
    }

    @Override
    public int genomeSize() {
        return GENOME_SIZE;
    }

    @Override
    public double[] action(final double[] genome, final double[] observation) {
        validateGenome(genome);
        if (observation.length != this.observationSize()) {
            throw new IllegalArgumentException("observation length mismatch");
        }

        final double phase = Math.atan2(
                finiteOrZero(observation[PHASE_SIN_INDEX]),
                finiteOrZero(observation[PHASE_COS_INDEX]));
        final double encodedDesiredForward = clamp(
                finiteOrZero(observation[DESIRED_FORWARD_INDEX]),
                -1.0,
                1.0);
        final double desiredForwardVelocity = encodedDesiredForward
                * ServoFirstObservationEncoder.MAX_COMMAND_FORWARD_VELOCITY;
        final double trainingSpeedFraction = clamp(
                desiredForwardVelocity / MAX_TRAINING_FORWARD_VELOCITY,
                -1.0,
                1.0);

        final double center = 0.35 * Math.tanh(genome[0]);
        final double centerDifferential = 0.25 * Math.tanh(genome[1]);
        final double baseAmplitude = 0.10 + 0.75 * normalizedTanh(genome[2]);
        final double amplitudeDifferential = 0.30 * Math.tanh(genome[3]);
        final double rightPhaseOffset = Math.PI * Math.tanh(genome[4]);
        final double commonPhaseOffset = Math.PI * Math.tanh(genome[5]);
        final double speedAmplitudeGain = MAX_SPEED_AMPLITUDE_GAIN * Math.tanh(genome[6]);
        final double speedScale = clamp(
                1.0 + speedAmplitudeGain * trainingSpeedFraction,
                MIN_SPEED_SCALE,
                MAX_SPEED_SCALE);

        final double leftAmplitude = clamp(baseAmplitude + amplitudeDifferential, 0.05, 0.95) * speedScale;
        final double rightAmplitude = clamp(baseAmplitude - amplitudeDifferential, 0.05, 0.95) * speedScale;
        final double gaitPhase = phase + commonPhaseOffset;

        return new double[]{
                clamp(center + centerDifferential + leftAmplitude * Math.sin(gaitPhase), -1.0, 1.0),
                clamp(center - centerDifferential + rightAmplitude * Math.sin(gaitPhase + rightPhaseOffset), -1.0, 1.0)
        };
    }

    private static void validateGenome(final double[] genome) {
        if (genome.length != GENOME_SIZE) {
            throw new IllegalArgumentException("genome length mismatch: expected " + GENOME_SIZE);
        }
        for (final double value : genome) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("genome values must be finite: " + Arrays.toString(genome));
            }
        }
    }

    private static double normalizedTanh(final double value) {
        return (Math.tanh(value) + 1.0) * 0.5;
    }

    private static double finiteOrZero(final double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static double clamp(final double value, final double minimum, final double maximum) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
