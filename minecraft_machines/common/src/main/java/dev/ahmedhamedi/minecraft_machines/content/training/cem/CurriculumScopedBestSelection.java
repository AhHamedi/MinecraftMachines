package dev.ahmedhamedi.minecraft_machines.content.training.cem;

public final class CurriculumScopedBestSelection {
    private CurriculumScopedBestSelection() {
    }

    public static boolean shouldReplace(
            final String currentCurriculumStage,
            final double currentAggregateFitness,
            final String candidateCurriculumStage,
            final double candidateAggregateFitness
    ) {
        final String candidateStage = requireStage("candidateCurriculumStage", candidateCurriculumStage);
        requireFinite("candidateAggregateFitness", candidateAggregateFitness);
        if (currentCurriculumStage == null || currentCurriculumStage.isBlank()) {
            return true;
        }
        final String currentStage = requireStage("currentCurriculumStage", currentCurriculumStage);
        requireFinite("currentAggregateFitness", currentAggregateFitness);
        if (!currentStage.equals(candidateStage)) {
            return true;
        }
        return candidateAggregateFitness > currentAggregateFitness;
    }

    private static String requireStage(final String name, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireFinite(final String name, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
