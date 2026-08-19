package dev.ahmedhamedi.minecraft_machines.content.training.cem;

public interface GenomePolicy {
    int observationSize();

    int actionSize();

    int genomeSize();

    double[] action(double[] genome, double[] observation);
}
