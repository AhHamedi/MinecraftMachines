package dev.ahmedhamedi.minecraft_machines.content.training.morphology;

import java.util.*;

/** Layered stance graph: surfaces under and over a ledge are distinct physical states. */
public final class LeaperPredatorPlanner {
    public interface Terrain {
        default double bodyScale() { return 1; }

        default double chassisStepClearance() {
            return 1;
        }

        default boolean adaptiveStanceSampling() {
            return false;
        }

        double height(double x, double z);

        default double extraCost(double x, double floor, double z) {
            return 0;
        }

        boolean bodyClear(double x, double floor, double z);

        default boolean bodyClear(
                double x, double floor, double z, double gradientX, double gradientZ) {
            return bodyClear(x, floor, z);
        }

        default List<Double> surfaces(double x, double z, double minimum, double maximum) {
            double h = height(x, z);
            return Double.isFinite(h) && h >= minimum - 1e-6 && h <= maximum + 1e-6
                    ? List.of(h)
                    : List.of();
        }
    }

    public record Point(double x, double y, double z) {}

    public record Result(List<Point> points, int expanded, String reason) {
        public boolean found() {
            return !points.isEmpty();
        }
    }

    private record Cell(int x, int y, int z) {}

    private record Node(Cell cell, double cost, double priority) {}

    private record Location(double x, double y, double z) {}

    private record Support(double cost, boolean valid, double floor, double gx, double gz) {}

    private final Terrain terrain;
    private final double ox, oz, initialFloor;
    private final Map<Location, Support> samples = new HashMap<>();
    private double segmentRisk, segmentCost;
    // Sample each voxel tread: a two-block grid skips one-block treads and turns
    // a climbable pair of two-block risers into an invalid four-block edge.
    private static final double GRID = 1, UP = 3.01, DOWN = 3.51;

    public LeaperPredatorPlanner(Terrain terrain, double x, double z) {
        this(terrain, x, Double.NaN, z);
    }

    public LeaperPredatorPlanner(Terrain terrain, double x, double floor, double z) {
        this.terrain = terrain;
        ox = x;
        oz = z;
        initialFloor = floor;
    }

    private double x(Cell c) {
        return ox + c.x * GRID;
    }

    private double z(Cell c) {
        return oz + c.z * GRID;
    }

    private double y(Cell c) {
        return c.y / 16.;
    }

    private static int quantize(double y) {
        return (int) Math.round(y * 16);
    }

    private double near(double x, double z, double reference, double below, double above) {
        double best = Double.NaN, error = 1e9;
        for (double h : terrain.surfaces(x, z, reference - below, reference + above)) {
            if (Math.abs(h - reference) < error) {
                error = Math.abs(h - reference);
                best = h;
            }
        }
        return best;
    }

    private Support sample(double x, double floor, double z) {
        return samples.computeIfAbsent(new Location(x, floor, z), p -> measure(p.x, p.y, p.z));
    }

    private Support measure(double x, double floor, double z) {
        double scale = terrain.bodyScale();
        var surrounding = new ArrayList<Double>();
        double supportBand = terrain.adaptiveStanceSampling() ? 8.5 : 5.5;
        double sx = 0, sz = 0, xx = 0, zz = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4,
                    dx = 5 * Math.cos(a) * scale,
                    dz = 5 * Math.sin(a) * scale,
                    h = near(x + dx, z + dz, floor, supportBand, supportBand);
            if (!Double.isFinite(h) && terrain.adaptiveStanceSampling()) {
                dx = 3.3 * Math.cos(a) * scale;
                dz = 3.3 * Math.sin(a) * scale;
                h = near(x + dx, z + dz, floor, supportBand, supportBand);
            }
            if (Double.isFinite(h)) {
                surrounding.add(h);
                sx += dx * (h - floor);
                sz += dz * (h - floor);
                xx += dx * dx;
                zz += dz * dz;
            }
        }
        if (surrounding.size() < 3) return new Support(99, false, Double.NaN, 0, 0);
        double gx = sx / Math.max(1, xx),
                gz = sz / Math.max(1, zz),
                norm = Math.max(1, Math.hypot(gx, gz) / 1.2);
        gx /= norm;
        gz /= norm;
        Collections.sort(surrounding);
        double mean = 0;
        for (int i = 1; i < surrounding.size() - 1; i++)
            mean += surrounding.get(i) / (surrounding.size() - 2);
        double poseFloor = Math.max(floor, mean - .5);
        for (double dx : new double[] {-1.7 * scale, 0, 1.7 * scale})
            for (double dz : new double[] {-1.7 * scale, 0, 1.7 * scale}) {
                // A short staircase can put its second tread under the torso before
                // the center reaches the first. Raise the planned stance to clear it;
                // the real body collision and foothold-sector checks still run below.
                double h =
                        near(
                                x + dx,
                                z + dz,
                                floor,
                                4,
                                terrain.adaptiveStanceSampling() ? 6.1 : 3.6);
                if (Double.isFinite(h))
                    poseFloor = Math.max(poseFloor, h - terrain.chassisStepClearance());
            }
        if (!terrain.bodyClear(x, poseFloor, z, gx, gz))
            return new Support(99, false, Double.NaN, 0, 0);
        int sectors = 0;
        double difficulty = 0;
        for (int i = 0; i < 4; i++) {
            double best = 99;
            for (double angle :
                    new double[] {
                        i * Math.PI / 2 + .35, i * Math.PI / 2 + .78, i * Math.PI / 2 + 1.2
                    })
                for (double radius :
                        terrain.adaptiveStanceSampling()
                                ? new double[] {3.2, 4.4, 5.5, 6.2}
                                : new double[] {4.4, 5.5, 6.2}) {
                    double dx = radius * Math.cos(angle) * scale,
                            dz = radius * Math.sin(angle) * scale,
                            expected = poseFloor + gx * dx + gz * dz;
                    double h = near(x + dx, z + dz, expected, 3, 3);
                    if (Double.isFinite(h))
                        best =
                                Math.min(
                                        best,
                                        Math.abs(h - expected) + .1 * Math.abs(h - poseFloor));
                }
            if (best < 99) {
                sectors++;
                difficulty += best;
            }
        }
        boolean center = Double.isFinite(near(x, z, floor, .1, .1));
        return new Support(
                Math.max(0, terrain.extraCost(x, floor, z))
                        + .5 * difficulty
                        + 8 * (8 - surrounding.size())
                        + (sectors == 3 ? 12 : 0)
                        + (center ? 0 : 5),
                sectors >= 3,
                poseFloor,
                gx,
                gz);
    }

    public double stanceFloor(double x, double floor, double z) {
        var support = sample(x, floor, z);
        return support.valid ? support.floor : Double.NaN;
    }

    private List<Double> candidates(double x, double z, double previous) {
        var values = new ArrayList<>(terrain.surfaces(x, z, previous - DOWN, previous + UP));
        // A long-legged stance may straddle a small hole if its foothold sectors remain supported.
        if (values.isEmpty()) values.add(previous);
        return values;
    }

    private double startHeight() {
        double reference = Double.isFinite(initialFloor) ? initialFloor : terrain.height(ox, oz);
        if (!Double.isFinite(reference)) return Double.NaN;
        double best = Double.NaN, error = 1e9;
        for (double h : candidates(ox, oz, reference))
            if (Math.abs(h - reference) < error && sample(ox, h, oz).valid) {
                best = h;
                error = Math.abs(h - reference);
            }
        return best;
    }

    public boolean segmentTo(double bx, double by, double bz) {
        return segment(ox, startHeight(), oz, bx, by, bz);
    }

    public boolean shortcutTo(double bx, double by, double bz) {
        return segmentTo(bx, by, bz) && segmentRisk < 10;
    }

    /** Recheck only the imminent part of a long corridor against fresh world state. */
    public boolean segmentAheadTo(double bx, double by, double bz, double distance) {
        if (!Double.isFinite(distance) || distance <= 0) return false;
        return segment(ox, startHeight(), oz, bx, by, bz, distance);
    }

    public boolean segment(double ax, double az, double bx, double bz) {
        double ay = Math.hypot(ax - ox, az - oz) < 1e-6 ? startHeight() : terrain.height(ax, az);
        return segment(ax, ay, az, bx, terrain.height(bx, bz), bz);
    }

    public boolean segment(double ax, double ay, double az, double bx, double by, double bz) {
        return segment(ax, ay, az, bx, by, bz, Double.POSITIVE_INFINITY);
    }

    private boolean segment(
            double ax, double ay, double az, double bx, double by, double bz, double distance) {
        segmentRisk = segmentCost = 0;
        if (!Double.isFinite(ax + ay + az + bx + by + bz) || !sample(ax, ay, az).valid)
            return false;
        int count = Math.max(1, (int) Math.ceil(Math.hypot(bx - ax, bz - az)));
        int checked = Math.min(count, (int) Math.ceil(distance));
        double last = ay;
        for (int i = 1; i <= checked; i++) {
            double t = i / (double) count,
                    px = ax + (bx - ax) * t,
                    pz = az + (bz - az) * t,
                    expected = ay + (by - ay) * t;
            double next = Double.NaN, cost = 1e9;
            for (double h : candidates(px, pz, last)) {
                if (h - last > UP || last - h > DOWN) continue;
                var support = sample(px, h, pz);
                double score = Math.abs(h - expected) + .02 * support.cost;
                if (support.valid && score < cost) {
                    next = h;
                    cost = score;
                }
            }
            if (!Double.isFinite(next)) return false;
            double step = Math.hypot(bx - ax, bz - az) / count;
            var support = sample(px, next, pz);
            double length = Math.max(.001, Math.hypot(bx - ax, bz - az));
            double uphill = (support.gx * (bx - ax) + support.gz * (bz - az)) / length;
            // The animal can gain height along a diagonal instead of repeatedly
            // driving directly into the steepest face. Smoothing uses this same cost.
            double gradeCost = 8 * Math.pow(Math.max(0, uphill - .45), 2);
            segmentRisk = Math.max(segmentRisk, Math.max(support.cost, uphill > .6 ? 11 : 0));
            segmentCost += step * (1 + support.cost + gradeCost) + .35 * Math.abs(next - last);
            last = next;
        }
        return checked < count || Math.abs(last - by) < .6;
    }

    public Result plan(double gx, double gy, double gz) {
        var search = search(gx, gy, gz);
        while (!search.done()) search.advance(Integer.MAX_VALUE, Long.MAX_VALUE);
        return search.result();
    }

    /** Resumed on the owning simulation thread; never reads terrain from a worker thread. */
    public Search search(double gx, double gy, double gz) {
        return new Search(gx, gy, gz, false);
    }

    /** Try a fully sampled low-risk corridor before expanding the stance graph. */
    public Search searchDirectFirst(double gx, double gy, double gz) {
        return new Search(gx, gy, gz, true);
    }

    public final class Search {
        final double gx, gy, gz;
        double sy, minY, maxY;
        int minX, maxX, minZ, maxZ, expanded;
        boolean initialized;
        boolean direct;
        int directStep, directSteps;
        double directX, directY, directZ;
        Node current;
        int neighbor;
        Result result;
        final PriorityQueue<Node> queue =
                new PriorityQueue<>(Comparator.comparingDouble(Node::priority));
        final Map<Cell, Double> costs = new HashMap<>();
        final Map<Cell, Cell> parents = new HashMap<>();

        Search(double gx, double gy, double gz, boolean direct) {
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.direct = direct;
        }

        public boolean done() {
            return result != null;
        }

        public Result result() {
            return result;
        }

        private Result finish(List<Point> path, int count, String reason) {
            return result = new Result(path, count, reason);
        }

        private Result initialize() {
            if (!Double.isFinite(gx + gy + gz + ox + oz) || Math.hypot(gx - ox, gz - oz) > 192)
                return finish(List.of(), 0, "Destination is outside the current planning horizon");
            if (!Double.isFinite(near(gx, gz, gy, .6, .6)) || !sample(gx, gy, gz).valid)
                return finish(List.of(), 0, "Destination has no reachable dry stance");
            sy = startHeight();
            if (!Double.isFinite(sy)) return finish(List.of(), 0, "No supported escape stance");
            directX = ox;
            directY = sy;
            directZ = oz;
            directSteps = Math.max(1, (int) Math.ceil(Math.hypot(gx - ox, gz - oz)));
            var start = new Cell(0, quantize(sy), 0);
            int goalX = (int) Math.round((gx - ox) / GRID),
                    goalZ = (int) Math.round((gz - oz) / GRID);
            int margin = (int) Math.ceil(40 / GRID);
            minX = Math.min(0, goalX) - margin;
            maxX = Math.max(0, goalX) + margin;
            minZ = Math.min(0, goalZ) - margin;
            maxZ = Math.max(0, goalZ) + margin;
            minY = Math.min(sy, gy) - 32;
            maxY = Math.max(sy, gy) + 32;

            costs.put(start, 0.);
            queue.add(new Node(start, 0, 0));
            return null;
        }

        public Result advance(int maxNodes, long deadlineNanos) {
            if (done()) return result;
            if (System.nanoTime() >= deadlineNanos) return null;
            if (!initialized) {
                initialized = true;
                if (initialize() != null) return result;
            }
            int work = 0;
            while (direct && work < maxNodes && System.nanoTime() < deadlineNanos) {
                double t = (directStep + 1.) / directSteps;
                double px = ox + (gx - ox) * t, pz = oz + (gz - oz) * t;
                double py = near(px, pz, directY, DOWN, UP);
                work++;
                // Reuse the same support, swept body, grade and risk checks as A*.
                if (!segment(directX, directY, directZ, px, py, pz) || segmentRisk >= 10) {
                    direct = false;
                    break;
                }
                directX = px;
                directY = py;
                directZ = pz;
                if (++directStep == directSteps) {
                    direct = false;
                    if (Math.abs(py - gy) < .6)
                        return finish(List.of(new Point(ox, sy, oz), new Point(gx, gy, gz)), 0, "");
                }
            }
            if (direct || work >= maxNodes) return null;
            int end = expanded + Math.min(maxNodes, 24000);
            while ((!queue.isEmpty() || current != null)
                    && (expanded < 24000 || current != null)
                    && (expanded < end || current != null)
                    && System.nanoTime() < deadlineNanos) {
                if (current == null) {
                    var item = queue.remove();
                    var a = item.cell;
                    if (item.cost > costs.getOrDefault(a, 1e9) + 1e-9) continue;
                    expanded++;
                    if (Math.hypot(x(a) - gx, z(a) - gz) <= GRID * 1.5
                            && segment(x(a), y(a), z(a), gx, gy, gz)) {
                        var path = new ArrayList<Point>();
                        while (a != null) {
                            path.add(new Point(x(a), y(a), z(a)));
                            a = parents.get(a);
                        }
                        Collections.reverse(path);
                        path.add(new Point(gx, gy, gz));
                        return finish(List.copyOf(path), expanded, "");
                    }
                    current = item;
                    neighbor = 0;
                }
                var item = current;
                var a = item.cell;
                int dx = neighbor / 3 - 1, dz = neighbor % 3 - 1;
                if (++neighbor == 9) current = null;
                if (dx == 0 && dz == 0) continue;
                int nx = a.x + dx, nz = a.z + dz;
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                double wx = ox + nx * GRID, wz = oz + nz * GRID;
                for (double height : candidates(wx, wz, y(a))) {
                    if (height < minY
                            || height > maxY
                            || height - y(a) > UP
                            || y(a) - height > DOWN) continue;
                    var b = new Cell(nx, quantize(height), nz);
                    var support = sample(wx, y(b), wz);
                    if (!support.valid) continue;
                    if (!segment(x(a), y(a), z(a), wx, y(b), wz)) continue;
                    double cost = item.cost + segmentCost;
                    if (cost >= costs.getOrDefault(b, 1e9)) continue;
                    costs.put(b, cost);
                    parents.put(b, a);
                    queue.add(
                            new Node(
                                    b,
                                    cost,
                                    cost
                                            + Math.hypot(wx - gx, wz - gz)
                                            + .35 * Math.abs(y(b) - gy)));
                }
            }
            if ((queue.isEmpty() || expanded >= 24000) && current == null)
                return finish(
                        List.of(),
                        expanded,
                        "No traversable route within the planning horizon (explored "
                                + expanded
                                + ", start floor "
                                + sy
                                + ")");
            return null;
        }
    }
}
