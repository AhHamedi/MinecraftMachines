package dev.ahmedhamedi.minecraft_machines.content.leaper;

import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Cell-local visual meshes and collision proxies share a generated morphology definition. */
public final class LeaperPartBlock extends Block {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 255);

    public static final BooleanProperty PRECISE = BooleanProperty.create("precise");

    /** Saved and replicated by the normal blockstate path, including Sable sublevel chunks. */
    public static final EnumProperty<EyeMode> EYE_MODE =
            EnumProperty.create("eye_mode", EyeMode.class);

    public enum EyeMode implements StringRepresentable {
        PATROLLING,
        ALERT,
        HUNTING;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Cell(int index, BlockPos offset, VoxelShape shape) {}

    public static final Map<String, List<Cell>> PARTS = new HashMap<>();
    private static final VoxelShape[] SHAPES = new VoxelShape[256];

    private static final VoxelShape[] PRECISE_SHAPES = new VoxelShape[256];
    private static final boolean[] EYE_PARTS = new boolean[256];

    static {
        java.util.Arrays.fill(SHAPES, Shapes.empty());
        try (var stream =
                LeaperPartBlock.class.getResourceAsStream(
                        "/data/minecraft_machines/leaper/geometry.json")) {
            if (stream == null) throw new IllegalStateException("Missing Leaper geometry");
            var data =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var cells = new ArrayList<Cell>();
            for (var e : data.getAsJsonArray("cells")) {
                var c = e.getAsJsonObject();
                int index = c.get("index").getAsInt();
                var offset = c.getAsJsonArray("offset");
                var shape = Shapes.empty();
                for (var b : c.getAsJsonArray("boxes")) {
                    var a = b.getAsJsonArray();
                    shape =
                            Shapes.or(
                                    shape,
                                    Shapes.box(
                                            a.get(0).getAsDouble(),
                                            a.get(1).getAsDouble(),
                                            a.get(2).getAsDouble(),
                                            a.get(3).getAsDouble(),
                                            a.get(4).getAsDouble(),
                                            a.get(5).getAsDouble()));
                }
                SHAPES[index] = shape.optimize();
                cells.add(
                        new Cell(
                                index,
                                new BlockPos(
                                        offset.get(0).getAsInt(),
                                        offset.get(1).getAsInt(),
                                        offset.get(2).getAsInt()),
                                SHAPES[index]));
            }
            java.util.Arrays.fill(PRECISE_SHAPES, Shapes.empty());
            try (var exact =
                    LeaperPartBlock.class.getResourceAsStream(
                            "/data/minecraft_machines/leaper/collision_geometry.json")) {
                if (exact == null)
                    throw new IllegalStateException("Missing precise Leaper collision");
                var precise =
                        JsonParser.parseReader(new InputStreamReader(exact, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                for (var entry : precise.getAsJsonArray("cells")) {
                    var cell = entry.getAsJsonObject();
                    var shape = Shapes.empty();
                    for (var b : cell.getAsJsonArray("boxes")) {
                        var a = b.getAsJsonArray();
                        shape =
                                Shapes.or(
                                        shape,
                                        Shapes.box(
                                                a.get(0).getAsDouble(),
                                                a.get(1).getAsDouble(),
                                                a.get(2).getAsDouble(),
                                                a.get(3).getAsDouble(),
                                                a.get(4).getAsDouble(),
                                                a.get(5).getAsDouble()));
                    }
                    PRECISE_SHAPES[cell.get("index").getAsInt()] = shape.optimize();
                }
            }
            for (var e : data.getAsJsonObject("parts").entrySet()) {
                var list = new ArrayList<Cell>();
                for (var i : e.getValue().getAsJsonArray()) list.add(cells.get(i.getAsInt()));
                PARTS.put(e.getKey(), List.copyOf(list));
            }
            try (var eyes =
                    LeaperPartBlock.class.getResourceAsStream(
                            "/data/minecraft_machines/leaper/eye_cells.json")) {
                if (eyes == null) throw new IllegalStateException("Missing Leaper eye cells");
                var eyeData =
                        JsonParser.parseReader(new InputStreamReader(eyes, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                for (var index : eyeData.getAsJsonArray("cells"))
                    EYE_PARTS[index.getAsInt()] = true;
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public LeaperPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition
                        .any()
                        .setValue(PART, 0)
                        .setValue(PRECISE, false)
                        .setValue(EYE_MODE, EyeMode.PATROLLING));
    }

    public static boolean isEyePart(int part) {
        return part >= 0 && part < EYE_PARTS.length && EYE_PARTS[part];
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(PART, PRECISE, EYE_MODE);
    }

    @Override
    protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return (s.getValue(PRECISE) ? PRECISE_SHAPES : SHAPES)[s.getValue(PART)];
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return (s.getValue(PRECISE) ? PRECISE_SHAPES : SHAPES)[s.getValue(PART)];
    }
}
