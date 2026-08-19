package dev.ahmedhamedi.minecraft_machines.content.training.morphology.duopod;

public record PlanarLocalOffset(double forward, double right) {
    public double horizontalDistance() {
        return Math.hypot(this.forward, this.right);
    }
}
