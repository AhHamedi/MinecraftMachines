package dev.ahmedhamedi.minecraft_machines.content.worm.training;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public record WormCemDistribution(
        double meanAmplitudeDeg,
        double stdAmplitudeDeg,
        double meanFrequencyHz,
        double stdFrequencyHz,
        double meanPhaseRad,
        double stdPhaseRad,
        double meanBiasDeg,
        double stdBiasDeg
) {
    private static final double UPDATE_KEEP = 0.35;
    private static final double UPDATE_ELITE = 0.65;
    private static final double MIN_STD_AMPLITUDE_DEG = 2.5;
    private static final double MIN_STD_FREQUENCY_HZ = 0.05;
    private static final double MIN_STD_PHASE_RAD = 0.05;
    private static final double MIN_STD_BIAS_DEG = 2.5;

    public static WormCemDistribution initial() {
        return new WormCemDistribution(
                35.0,
                25.0,
                1.0,
                0.6,
                Math.PI,
                Math.PI,
                0.0,
                25.0
        );
    }

    public WormCemDistribution {
        requireFinite("meanAmplitudeDeg", meanAmplitudeDeg);
        requireFinite("stdAmplitudeDeg", stdAmplitudeDeg);
        requireFinite("meanFrequencyHz", meanFrequencyHz);
        requireFinite("stdFrequencyHz", stdFrequencyHz);
        requireFinite("meanPhaseRad", meanPhaseRad);
        requireFinite("stdPhaseRad", stdPhaseRad);
        requireFinite("meanBiasDeg", meanBiasDeg);
        requireFinite("stdBiasDeg", stdBiasDeg);
        if (stdAmplitudeDeg < 0.0 || stdFrequencyHz < 0.0 || stdPhaseRad < 0.0 || stdBiasDeg < 0.0) {
            throw new IllegalArgumentException("standard deviations must be non-negative");
        }
    }

    public WormCemCandidate sample(final Random random) {
        return WormCemCandidate.clamped(
                sampleGaussian(random, this.meanAmplitudeDeg, this.stdAmplitudeDeg),
                sampleGaussian(random, this.meanFrequencyHz, this.stdFrequencyHz),
                sampleGaussian(random, this.meanPhaseRad, this.stdPhaseRad),
                sampleGaussian(random, this.meanBiasDeg, this.stdBiasDeg)
        );
    }

    public WormCemDistribution update(final List<ScoredWormCandidate> scoredCandidates, final int eliteCount) {
        if (eliteCount < 1 || eliteCount > scoredCandidates.size()) {
            throw new IllegalArgumentException("eliteCount must be in 1..scoredCandidates.size");
        }

        final List<WormCemCandidate> elites = scoredCandidates.stream()
                .sorted(Comparator.comparingDouble(ScoredWormCandidate::score).reversed())
                .limit(eliteCount)
                .map(ScoredWormCandidate::candidate)
                .toList();

        final Stats amplitude = stats(elites, Parameter.AMPLITUDE);
        final Stats frequency = stats(elites, Parameter.FREQUENCY);
        final Stats phase = stats(elites, Parameter.PHASE);
        final Stats bias = stats(elites, Parameter.BIAS);

        return new WormCemDistribution(
                smoothClamped(this.meanAmplitudeDeg, amplitude.mean(), WormCemCandidate.MIN_AMPLITUDE_DEG, WormCemCandidate.MAX_AMPLITUDE_DEG),
                smoothStd(this.stdAmplitudeDeg, amplitude.stdDev(), MIN_STD_AMPLITUDE_DEG),
                smoothClamped(this.meanFrequencyHz, frequency.mean(), WormCemCandidate.MIN_FREQUENCY_HZ, WormCemCandidate.MAX_FREQUENCY_HZ),
                smoothStd(this.stdFrequencyHz, frequency.stdDev(), MIN_STD_FREQUENCY_HZ),
                smoothClamped(this.meanPhaseRad, phase.mean(), WormCemCandidate.MIN_PHASE_RAD, WormCemCandidate.MAX_PHASE_RAD),
                smoothStd(this.stdPhaseRad, phase.stdDev(), MIN_STD_PHASE_RAD),
                smoothClamped(this.meanBiasDeg, bias.mean(), WormCemCandidate.MIN_BIAS_DEG, WormCemCandidate.MAX_BIAS_DEG),
                smoothStd(this.stdBiasDeg, bias.stdDev(), MIN_STD_BIAS_DEG)
        );
    }

    public String compactDescription() {
        return String.format(Locale.ROOT,
                "mean[amp=%.2f freq=%.3f phase=%.3f bias=%.2f] std[amp=%.2f freq=%.3f phase=%.3f bias=%.2f]",
                this.meanAmplitudeDeg,
                this.meanFrequencyHz,
                this.meanPhaseRad,
                this.meanBiasDeg,
                this.stdAmplitudeDeg,
                this.stdFrequencyHz,
                this.stdPhaseRad,
                this.stdBiasDeg);
    }

    private static double sampleGaussian(final Random random, final double mean, final double stdDev) {
        return mean + random.nextGaussian() * stdDev;
    }

    private static Stats stats(final List<WormCemCandidate> candidates, final Parameter parameter) {
        final double mean = candidates.stream()
                .mapToDouble(parameter::value)
                .average()
                .orElseThrow();
        final double variance = candidates.stream()
                .mapToDouble(candidate -> {
                    final double delta = parameter.value(candidate) - mean;
                    return delta * delta;
                })
                .average()
                .orElse(0.0);
        return new Stats(mean, Math.sqrt(variance));
    }

    private static double smoothClamped(final double previous, final double elite, final double minimum, final double maximum) {
        return WormCemCandidate.clamp(previous * UPDATE_KEEP + elite * UPDATE_ELITE, minimum, maximum);
    }

    private static double smoothStd(final double previous, final double elite, final double minimum) {
        return Math.max(minimum, previous * UPDATE_KEEP + elite * UPDATE_ELITE);
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private record Stats(double mean, double stdDev) {
    }

    private enum Parameter {
        AMPLITUDE {
            @Override
            double value(final WormCemCandidate candidate) {
                return candidate.amplitudeDeg();
            }
        },
        FREQUENCY {
            @Override
            double value(final WormCemCandidate candidate) {
                return candidate.frequencyHz();
            }
        },
        PHASE {
            @Override
            double value(final WormCemCandidate candidate) {
                return candidate.phaseRad();
            }
        },
        BIAS {
            @Override
            double value(final WormCemCandidate candidate) {
                return candidate.biasDeg();
            }
        };

        abstract double value(WormCemCandidate candidate);
    }
}
