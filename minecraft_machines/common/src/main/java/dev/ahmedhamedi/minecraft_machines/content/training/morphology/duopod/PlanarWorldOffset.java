package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public record PlanarWorldOffset(double x, double z) {
    public double length() {
        return Math.hypot(this.x, this.z);
    }
}
