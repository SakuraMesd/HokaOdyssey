package ink.hoka.odyssey.client.ships;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Блоки корабля и их отрисовка. Система корабля: блок (dx, dy, dz) от клетки пилота занимает
 * (dx − 0.5 … dx + 0.5, dy − seatH … dy − seatH + 1, dz − 0.5 … dz + 0.5); перед отрисовкой блоки мира поворачиваются
 * на курс взлёта (turn), чтобы нос смотрел на +Z — дальше ориентация корабля (курс, наклон, крен).
 */
final class ShipMesh {

    /** Квад блока со сдвигом и цветом (оттенок травы/листвы). */
    private record Q(BakedQuad quad, float x, float y, float z, float r, float g, float b) {}

    int[][] parts;                       // dx, dy, dz, stateId (меняется: двери, стройка)
    final float seatH, turn;
    /** Внешние клетки (есть пустой сосед) — центры в системе корабля после поворота turn: для столкновений. */
    final List<float[]> hull = new ArrayList<>();
    /** Радиус корабля от точки пилота. */
    float radius;
    /** Коробка всех блоков в системе корабля (до поворота turn): углы min и max. */
    final float[] lo = new float[3], hi = new float[3];
    /** Клетка (dx, dy, dz) → id состояния блока: для столкновений. */
    final Map<Long, Integer> cells = new HashMap<>();
    /** Коробка столкновения состояния (0..1) или null — сквозь него проходят. */
    private static final Map<Integer, net.minecraft.world.phys.AABB> BOUNDS = new HashMap<>();
    private Map<RenderType, List<Q>> mesh;
    /** Блоки-сущности корабля: сдвиг клетки и сама сущность (рисует её рендер игры). */
    private record BE(float x, float y, float z, net.minecraft.world.level.block.entity.BlockEntity be) {}
    private List<BE> entities = List.of();

    ShipMesh(int[][] parts, float seatH, float turn) {
        this.parts = parts;
        this.seatH = seatH;
        this.turn = turn;
        recompute();
    }

    /** Клетка изменилась (дверь открылась, блок поставили/сломали): id 0 — пусто. Меш перестроится при отрисовке. */
    void update(int dx, int dy, int dz, int id) {
        List<int[]> list = new ArrayList<>(parts.length + 1);
        for (int[] p : parts) if (p[0] != dx || p[1] != dy || p[2] != dz) list.add(p);
        if (id != 0) list.add(new int[]{dx, dy, dz, id});
        parts = list.toArray(new int[0][]);
        recompute();
        mesh = null;
        entities = List.of();
    }

    /** Формы для кликов (как рамка выделения у игры) — в системе корабля, или null. */
    net.minecraft.world.phys.shapes.VoxelShape outline(int dx, int dy, int dz) {
        Integer id = cells.get(key(dx, dy, dz));
        if (id == null) return null;
        var base = OUTLINES.computeIfAbsent(id, k -> {
            BlockState st = Block.BLOCK_STATE_REGISTRY.byId(k);
            if (st == null || st.isAir()) return net.minecraft.world.phys.shapes.Shapes.empty();
            return st.getShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        });
        return base.isEmpty() ? null : base.move(dx - 0.5, dy - seatH, dz - 0.5);
    }

    private static final Map<Integer, net.minecraft.world.phys.shapes.VoxelShape> OUTLINES = new HashMap<>();

    /** Состояние блока в клетке или null. */
    BlockState state(int dx, int dy, int dz) {
        Integer id = cells.get(key(dx, dy, dz));
        return id == null ? null : Block.BLOCK_STATE_REGISTRY.byId(id);
    }

    private void recompute() {
        cells.clear();
        hull.clear();
        for (int[] p : parts) cells.put(key(p[0], p[1], p[2]), p[3]);
        double t = Math.toRadians(turn), c = Math.cos(t), s = Math.sin(t);
        float r = 1;
        for (int[] p : parts) {
            boolean outer = false;
            for (Direction d : Direction.values())
                if (!cells.containsKey(key(p[0] + d.getStepX(), p[1] + d.getStepY(), p[2] + d.getStepZ()))) { outer = true; break; }
            float cy = p[1] + 0.5f - seatH;
            // поворот turn вокруг Y (как Ry(+turn) у JOML): x' = x·cos + z·sin, z' = −x·sin + z·cos
            float cx = (float) (p[0] * c + p[2] * s), cz = (float) (-p[0] * s + p[2] * c);
            if (outer) hull.add(new float[]{cx, cy, cz});
            r = Math.max(r, (float) Math.sqrt(cx * cx + cy * cy + cz * cz) + 1);
        }
        radius = r;
        if (parts.length > 0) {
            int[] mn = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, mx = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int[] p : parts) for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], p[i]); mx[i] = Math.max(mx[i], p[i]); }
            lo[0] = mn[0] - 0.5f; lo[1] = mn[1] - seatH; lo[2] = mn[2] - 0.5f;
            hi[0] = mx[0] + 0.5f; hi[1] = mx[1] - seatH + 1; hi[2] = mx[2] + 0.5f;
        }
    }

    /** Форма столкновения блока в клетке — в системе корабля (клетка (dx,dy,dz) = [dx−0.5, dy−seatH, dz−0.5] + 0..1). */
    net.minecraft.world.phys.shapes.VoxelShape shape(int dx, int dy, int dz) {
        Integer id = cells.get(key(dx, dy, dz));
        if (id == null) return null;
        var base = SHAPES.computeIfAbsent(id, k -> {
            BlockState st = Block.BLOCK_STATE_REGISTRY.byId(k);
            if (st == null || st.isAir()) return net.minecraft.world.phys.shapes.Shapes.empty();
            return st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        });
        return base.isEmpty() ? null : base.move(dx - 0.5, dy - seatH, dz - 0.5);
    }

    private static final Map<Integer, net.minecraft.world.phys.shapes.VoxelShape> SHAPES = new HashMap<>();

    /** Коробка столкновения блока в клетке (0..1) или null. */
    net.minecraft.world.phys.AABB bounds(int dx, int dy, int dz) {
        Integer id = cells.get(key(dx, dy, dz));
        if (id == null) return null;
        return BOUNDS.computeIfAbsent(id, k -> {
            BlockState st = Block.BLOCK_STATE_REGISTRY.byId(k);
            if (st == null || st.isAir()) return null;
            var shape = st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return shape.isEmpty() ? null : shape.bounds();
        });
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /** Квады всех блоков; грани, закрытые соседним блоком корабля, выброшены. Строится при первой отрисовке. */
    private void build() {
        Minecraft mc = Minecraft.getInstance();
        Map<Long, BlockState> cells = new HashMap<>();
        for (int[] p : parts) {
            BlockState st = Block.BLOCK_STATE_REGISTRY.byId(p[3]);
            if (st != null && !st.isAir()) cells.put(key(p[0], p[1], p[2]), st);
        }
        Map<RenderType, List<Q>> m = new LinkedHashMap<>();
        RandomSource rnd = RandomSource.create();
        BlockPos at = BlockPos.containing(mc.player != null ? mc.player.position() : Vec3.ZERO);
        for (int[] p : parts) {
            BlockState st = cells.get(key(p[0], p[1], p[2]));
            if (st == null) continue;
            RenderType type = switch (ItemBlockRenderTypes.getChunkRenderType(st)) {
                case SOLID -> Sheets.solidBlockSheet();
                case TRANSLUCENT -> Sheets.translucentBlockItemSheet();
                default -> Sheets.cutoutBlockSheet();
            };
            List<Q> out = m.computeIfAbsent(type, k -> new ArrayList<>());
            float ox = p[0] - 0.5f, oy = p[1] - seatH, oz = p[2] - 0.5f;
            rnd.setSeed(key(p[0], p[1], p[2]));
            for (BlockModelPart part : mc.getBlockRenderer().getBlockModel(st).collectParts(rnd)) {
                for (Direction d : Direction.values()) {
                    BlockState n = cells.get(key(p[0] + d.getStepX(), p[1] + d.getStepY(), p[2] + d.getStepZ()));
                    if (n != null && !Block.shouldRenderFace(st, n, d)) continue;   // закрыта соседом (по форме граней, как в игре)
                    for (BakedQuad q : part.getQuads(d)) out.add(quad(mc, st, q, at, ox, oy, oz));
                }
                for (BakedQuad q : part.getQuads(null)) out.add(quad(mc, st, q, at, ox, oy, oz));
            }
        }
        mesh = m;
        // блоки-сущности: свой экземпляр на клетку (для рендера игры), позиция — где корабль (для света)
        List<BE> ents = new ArrayList<>();
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        for (int[] p : parts) {
            BlockState st = cells.get(key(p[0], p[1], p[2])) == null ? null : Block.BLOCK_STATE_REGISTRY.byId(p[3]);
            if (st == null || !(st.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb)) continue;
            try {
                var be = eb.newBlockEntity(at, st);
                if (be == null || dispatcher.getRenderer(be) == null) continue;
                if (mc.level != null) be.setLevel(mc.level);
                ents.add(new BE(p[0] - 0.5f, p[1] - seatH, p[2] - 0.5f, be));
            } catch (Exception ignored) {
            }
        }
        entities = ents;
    }

    /** Блоки-сущности: PoseStack уже в точке пилота и с ориентацией корабля. */
    void drawEntities(PoseStack ps, ink.hoka.odyssey.client.api.ClientContext.WorldFrame f) {
        if (mesh == null || entities.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        net.minecraft.client.renderer.state.CameraRenderState cam;
        try {
            cam = f.cameraState();
        } catch (LinkageError e) {                                              // ядро старше 0.15 — без сундуков
            entities = List.of();
            return;
        }
        ps.pushPose();
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(turn));
        for (BE e : entities) {
            try {
                if (mc.level != null && e.be.getLevel() != mc.level) e.be.setLevel(mc.level);
                var state = dispatcher.tryExtractRenderState(e.be, f.partialTick(), null);
                if (state == null) continue;
                ps.pushPose();
                ps.translate(e.x, e.y, e.z);
                dispatcher.submit(state, ps, f.submits(), cam);
                ps.popPose();
            } catch (Exception ignored) {
            }
        }
        ps.popPose();
    }

    private static Q quad(Minecraft mc, BlockState st, BakedQuad q, BlockPos at, float x, float y, float z) {
        float cr = 1, cg = 1, cb = 1;
        if (q.isTinted() && mc.level != null) {
            int c = mc.getBlockColors().getColor(st, mc.level, at, q.tintIndex());
            cr = (c >> 16 & 255) / 255f;
            cg = (c >> 8 & 255) / 255f;
            cb = (c & 255) / 255f;
        }
        return new Q(q, x, y, z, cr, cg, cb);
    }

    /** Нарисовать: PoseStack уже в точке пилота и с ориентацией корабля; здесь — поворот turn и блоки. */
    void draw(PoseStack ps, MultiBufferSource buffers, int light) {
        if (mesh == null) build();
        ps.pushPose();
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(turn));
        for (var g : mesh.entrySet()) {
            var vc = buffers.getBuffer(g.getKey());
            for (Q q : g.getValue()) {
                ps.pushPose();
                ps.translate(q.x, q.y, q.z);
                vc.putBulkData(ps.last(), q.quad, q.r, q.g, q.b, 1f, light, OverlayTexture.NO_OVERLAY);
                ps.popPose();
            }
        }
        ps.popPose();
    }
}
