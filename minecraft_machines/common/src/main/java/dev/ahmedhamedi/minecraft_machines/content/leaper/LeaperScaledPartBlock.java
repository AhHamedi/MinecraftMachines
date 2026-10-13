package dev.ahmedhamedi.minecraft_machines.content.leaper;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual enlarged voxel cells, with identical server collision and client mesh boundaries. */
public final class LeaperScaledPartBlock extends Block {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 511);
    public static final Map<String, List<LeaperPartBlock.Cell>> PARTS = new HashMap<>();
    private static final VoxelShape[] SHAPES = new VoxelShape[512];
    private static final boolean[] EYES = new boolean[512];
    static {
        Arrays.fill(SHAPES, Shapes.empty());
        try (var stream = LeaperScaledPartBlock.class.getResourceAsStream(
                "/data/minecraft_machines/leaper/scaled_geometry.json")) {
            if (stream == null) throw new IllegalStateException("Missing scaled geometry");
            var data = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var cells = new ArrayList<LeaperPartBlock.Cell>();
            for (var entry : data.getAsJsonArray("cells")) {
                var cell = entry.getAsJsonObject();
                int index = cell.get("index").getAsInt();
                var shape = Shapes.empty();
                for (var box : cell.getAsJsonArray("boxes")) {
                    var a = box.getAsJsonArray();
                    shape = Shapes.or(shape, Shapes.box(a.get(0).getAsDouble(), a.get(1).getAsDouble(),
                            a.get(2).getAsDouble(), a.get(3).getAsDouble(), a.get(4).getAsDouble(), a.get(5).getAsDouble()));
                }
                SHAPES[index] = shape.optimize();
                EYES[index] = cell.get("eye").getAsBoolean();
                var offset = cell.getAsJsonArray("offset");
                cells.add(new LeaperPartBlock.Cell(index, new BlockPos(offset.get(0).getAsInt(),
                        offset.get(1).getAsInt(), offset.get(2).getAsInt()), SHAPES[index]));
            }
            for (var part : data.getAsJsonObject("parts").entrySet()) {
                var list = new ArrayList<LeaperPartBlock.Cell>();
                for (var index : part.getValue().getAsJsonArray()) list.add(cells.get(index.getAsInt()));
                PARTS.put(part.getKey(), List.copyOf(list));
            }
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    public LeaperScaledPartBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(PART, 0)
                .setValue(LeaperPartBlock.PRECISE, true)
                .setValue(LeaperPartBlock.EYE_MODE, LeaperPartBlock.EyeMode.PATROLLING));
    }
    public static boolean isEyePart(int index) { return EYES[index]; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(PART, LeaperPartBlock.PRECISE, LeaperPartBlock.EYE_MODE);
    }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return SHAPES[s.getValue(PART)];
    }
    @Override protected VoxelShape getCollisionShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) {
        return SHAPES[s.getValue(PART)];
    }
}
