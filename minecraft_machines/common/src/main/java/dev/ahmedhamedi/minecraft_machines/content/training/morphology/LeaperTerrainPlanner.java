package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

import java.util.*;

/** Bounded A* over collision heights, including the robot's wide support footprint. */
public final class LeaperTerrainPlanner {
    public interface Surface {
        double height(double x, double z);
    }

    public record Point(double x, double y, double z) {}

    public record Result(List<Point> points, int expanded, String reason) {
        public boolean found() {
            return !points.isEmpty();
        }
    }

    private record Cell(int x, int z) {}

    private record QueueEntry(Cell cell, double cost, double priority) {}

    private final Surface surface;
    private final Map<Cell, Double> heights = new HashMap<>();
    private final Map<Cell, Boolean> supports = new HashMap<>();
    private static final double STEP = 2;
    private final double originX, originZ;

    public LeaperTerrainPlanner(Surface surface, double originX, double originZ) {
        this.surface = surface;
        this.originX = originX;
        this.originZ = originZ;
    }

    private double x(Cell c) {
        return originX + c.x * STEP;
    }

    private double z(Cell c) {
        return originZ + c.z * STEP;
    }

    private double height(Cell c) {
        return heights.computeIfAbsent(c, k -> surface.height(x(k), z(k)));
    }

    private boolean support(Cell c) {
        return supports.computeIfAbsent(c, k -> supported(x(k), z(k)));
    }

    public boolean supported(double x, double z) {
        double center = surface.height(x, z), min = center, max = center;
        if (!Double.isFinite(center)) return false;
        for (int ix = -3; ix <= 3; ix++)
            for (int iz = -3; iz <= 3; iz++) {
                if (ix * ix + iz * iz > 10) continue;
                double h = surface.height(x + ix * 1.9, z + iz * 1.9);
                if (!Double.isFinite(h) || Math.abs(h - center) > 3.1) return false;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        return max - min <= 4.1;
    }

    public boolean segment(double ax, double az, double bx, double bz) {
        int n = Math.max(1, (int) Math.ceil(Math.hypot(ax - bx, az - bz) * 2));
        double last = surface.height(ax, az);
        for (int i = 0; i <= n; i++) {
            double x = ax + (bx - ax) * i / n, z = az + (bz - az) * i / n, h = surface.height(x, z);
            if (!supported(x, z) || !Double.isFinite(last) || Math.abs(h - last) > 1.01)
                return false;
            last = h;
        }
        return true;
    }

    private boolean edge(Cell a, Cell b) {
        if (!support(b)) return false;
        double last = height(a);
        for (int i = 1; i <= 6; i++) {
            double t = i / 6.0,
                    h = surface.height(x(a) + (x(b) - x(a)) * t, z(a) + (z(b) - z(a)) * t);
            if (!Double.isFinite(h) || Math.abs(h - last) > 1.01) return false;
            last = h;
        }
        return true;
    }

    public Result plan(double goalX, double goalY, double goalZ) {
        if (!Double.isFinite(goalX + goalY + goalZ + originX + originZ))
            throw new IllegalArgumentException("Nonfinite route");
        var start = new Cell(0, 0);
        var goal =
                new Cell(
                        (int) Math.round((goalX - originX) / STEP),
                        (int) Math.round((goalZ - originZ) / STEP));
        if (!support(start))
            return new Result(List.of(), 0, "Starting stance has insufficient support");
        if (!supported(goalX, goalZ)
                || Math.abs(surface.height(goalX, goalZ) - goalY) > .51
                || !support(goal)
                || Math.abs(height(goal) - goalY) > 1.1)
            return new Result(List.of(), 0, "Destination has insufficient support or clearance");
        int minX = Math.min(0, goal.x) - 14,
                maxX = Math.max(0, goal.x) + 14,
                minZ = Math.min(0, goal.z) - 14,
                maxZ = Math.max(0, goal.z) + 14;
        var queue = new PriorityQueue<QueueEntry>(Comparator.comparingDouble(QueueEntry::priority));
        var costs = new HashMap<Cell, Double>();
        var parents = new HashMap<Cell, Cell>();
        costs.put(start, 0.0);
        queue.add(new QueueEntry(start, 0, 0));
        int expanded = 0;
        while (!queue.isEmpty() && expanded < 12000) {
            var e = queue.remove();
            var a = e.cell;
            if (e.cost > costs.getOrDefault(a, Double.POSITIVE_INFINITY) + 1e-9) continue;
            ++expanded;
            if (a.equals(goal)) {
                if (!segment(x(a), z(a), goalX, goalZ))
                    return new Result(
                            List.of(), expanded, "Selected point has insufficient support");
                var path = new ArrayList<Point>();
                while (a != null) {
                    path.add(new Point(x(a), height(a), z(a)));
                    a = parents.get(a);
                }
                Collections.reverse(path);
                // The exact selected surface point is retained as the final destination.
                path.add(new Point(goalX, goalY, goalZ));
                return new Result(List.copyOf(path), expanded, "");
            }
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    var b = new Cell(a.x + dx, a.z + dz);
                    if (b.x < minX || b.x > maxX || b.z < minZ || b.z > maxZ || !edge(a, b))
                        continue;
                    if (dx != 0
                            && dz != 0
                            && (!edge(a, new Cell(a.x + dx, a.z))
                                    || !edge(a, new Cell(a.x, a.z + dz)))) continue;
                    double cost =
                            e.cost
                                    + STEP * Math.hypot(dx, dz)
                                    + 2 * Math.abs(height(a) - height(b));
                    if (cost >= costs.getOrDefault(b, Double.POSITIVE_INFINITY)) continue;
                    costs.put(b, cost);
                    parents.put(b, a);
                    queue.add(
                            new QueueEntry(
                                    b, cost, cost + STEP * Math.hypot(b.x - goal.x, b.z - goal.z)));
                }
        }
        return new Result(List.of(), expanded, "No supported route within the planning boundary");
    }
}
