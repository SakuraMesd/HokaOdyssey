package ink.hoka.odyssey.client.ships;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Блоки корабля и их отрисовка. Система корабля: блок (dx, dy, dz) от клетки пилота занимает
 * (dx − 0.5 … dx + 0.5, dy − seatH … dy − seatH + 1, dz − 0.5 … dz + 0.5); перед отрисовкой блоки мира поворачиваются
 * на курс взлёта (turn), чтобы нос смотрел на +Z — дальше ориентация корабля (курс, наклон, крен).
 * <p>Сетка — кусками 16³, как мир у игры: смена клетки пересобирает только её куски (и те, где из-за неё поменялся
 * свет), в фоне; непрозрачное каждого куска лежит в видеопамяти; куски вне кадра не рисуются.
 */
final class ShipMesh {

    /** Готовые вершины одного слоя (в системе корабля до поворота turn): x,y,z,u,v,nx,ny,nz + цвет, свет. */
    private static final class Layer {
        float[] f = new float[8 * 256];
        int[] i = new int[2 * 256];
        int n;

        void add(float x, float y, float z, int color, float u, float v, int light, float nx, float ny, float nz) {
            if (n * 8 + 8 > f.length) { f = java.util.Arrays.copyOf(f, f.length * 2); i = java.util.Arrays.copyOf(i, i.length * 2); }
            int k = n * 8;
            f[k] = x; f[k + 1] = y; f[k + 2] = z; f[k + 3] = u; f[k + 4] = v; f[k + 5] = nx; f[k + 6] = ny; f[k + 7] = nz;
            i[n * 2] = color; i[n * 2 + 1] = light;
            n++;
        }
    }

    /** Небо там, где корабль, на момент сборки сетки (сменилось — пересобрать всё: свет запечён в вершины). */
    private int builtSky = -1;

    final float seatH, turn;
    /** Где лежит корабль в верфи (клетка пилота, настоящие блоки) или null — старый корабль без верфи. */
    int[] yard;
    /** Радиус корабля от точки пилота. */
    float radius;
    /** Коробка всех блоков в системе корабля (до поворота turn): углы min и max. */
    final float[] lo = new float[3], hi = new float[3];
    /** Клетка (dx, dy, dz) → id состояния блока — правда о блоках корабля (меняется по клетке: двери, стройка). */
    final Map<Long, Integer> cells = new HashMap<>();
    /** 0.27.1: те же клетки без упаковки — снимок для фоновой сборки копируется массивом (clone), а не по записи. */
    private final Long2IntOpenHashMap ids = new Long2IntOpenHashMap();
    /** Коробка столкновения состояния (0..1) или null — сквозь него проходят. */
    private static final Map<Integer, net.minecraft.world.phys.AABB> BOUNDS = new HashMap<>();
    /** Блоки-сущности корабля: сдвиг клетки и сама сущность (рисует её рендер игры). */
    private static final class BE {
        final float x, y, z;
        final net.minecraft.world.level.block.entity.BlockEntity be;
        /** 0.27.1: состояние рендера — одно на сущность (не создавать каждый кадр); renderer — для кого создано. */
        Object renderer;
        net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state;

        BE(float x, float y, float z, net.minecraft.world.level.block.entity.BlockEntity be) { this.x = x; this.y = y; this.z = z; this.be = be; }
    }
    /** Блоки-сущности по клеткам (меняются поштучно). */
    private final Map<Long, BE> bes = new HashMap<>();

    /** Крышка сундука в клетке: n смотрят (0 — закрыть) — как BlockEvent у игры; анимацию двигает tickLids. */
    void lid(int dx, int dy, int dz, int n) {
        lidAt(dx, dy, dz, n);
        BlockState st = state(dx, dy, dz);                                     // двойной сундук — и вторая половина
        if (st != null && st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && st.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            Direction d = net.minecraft.world.level.block.ChestBlock.getConnectedDirection(st);
            lidAt(dx + d.getStepX(), dy, dz + d.getStepZ(), n);
        }
    }

    private void lidAt(int dx, int dy, int dz, int n) {
        BE e = bes.get(key(dx, dy, dz));
        if (e != null) e.be.triggerEvent(1, n);
    }

    /** Каждый тик: крышки сундуков корабля (как у игры — плавно открываются/закрываются). */
    void tickLids() {
        for (BE e : bes.values())
            if (e.be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c && e.be.getLevel() != null)
                net.minecraft.world.level.block.entity.ChestBlockEntity.lidAnimateTick(e.be.getLevel(), e.be.getBlockPos(), e.be.getBlockState(), c);
    }

    ShipMesh(int[][] parts, float seatH, float turn) {
        this.seatH = seatH;
        this.turn = turn;
        for (int[] p : parts) if (p[3] != 0) { cells.put(key(p[0], p[1], p[2]), p[3]); ids.put(key(p[0], p[1], p[2]), p[3]); }
        bounds();
        Minecraft mc = Minecraft.getInstance();
        for (var e : cells.entrySet()) makeBe(mc, e.getKey(), e.getValue());
    }

    /** Блоки корабля списком {dx, dy, dz, id}. */
    int[][] parts() {
        int[][] out = new int[cells.size()][];
        int i = 0;
        for (var e : cells.entrySet()) { long k = e.getKey(); out[i++] = new int[]{kx(k), ky(k), kz(k), e.getValue()}; }
        return out;
    }

    /**
     * Клетка изменилась (дверь открылась, блок поставили/сломали): id 0 — пусто. Пересобираются только куски 16³,
     * которых касается клетка (отсечение граней и затенение углов берут соседей), и те, где поменялся свет — в фоне;
     * блок-сущность — только этой клетки.
     */
    void update(int dx, int dy, int dz, int id) {
        long k = key(dx, dy, dz);
        Integer was = id == 0 ? cells.remove(k) : cells.put(k, id);
        if (was != null && was == id) return;
        if (was == null && id == 0) return;
        if (id == 0) ids.remove(k); else ids.put(k, id);
        if (id != 0) grow(dx, dy, dz);
        else if (dx - 0.5f <= lo[0] || dx + 0.5f >= hi[0] || dy - seatH <= lo[1] || dy - seatH + 1 >= hi[1]
                || dz - 0.5f <= lo[2] || dz + 0.5f >= hi[2]) bounds();          // сломали крайний — коробка/радиус заново
        hullDirty = true;
        BE old = bes.remove(k);
        if (old != null) old.be.setRemoved();
        if (id != 0) makeBe(Minecraft.getInstance(), k, id);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            dirty.add(sec((dx + x) >> 4, (dy + y) >> 4, (dz + z) >> 4));
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

    /** Коробка и радиус по всем клеткам (при создании; дальше только расширяются — grow). */
    private void bounds() {
        lo[0] = lo[1] = lo[2] = Float.MAX_VALUE;
        hi[0] = hi[1] = hi[2] = -Float.MAX_VALUE;
        radius = 1;
        for (long k : cells.keySet()) grow(kx(k), ky(k), kz(k));
        if (cells.isEmpty()) { lo[0] = lo[1] = lo[2] = 0; hi[0] = hi[1] = hi[2] = 0; }
    }

    private void grow(int x, int y, int z) {
        lo[0] = Math.min(lo[0], x - 0.5f); lo[1] = Math.min(lo[1], y - seatH); lo[2] = Math.min(lo[2], z - 0.5f);
        hi[0] = Math.max(hi[0], x + 0.5f); hi[1] = Math.max(hi[1], y - seatH + 1); hi[2] = Math.max(hi[2], z + 0.5f);
        float cy = y + 0.5f - seatH;
        radius = Math.max(radius, (float) Math.sqrt((double) x * x + (double) cy * cy + (double) z * z) + 1);
    }

    /**
     * Где встать, слезая с кресла пилота (клетка пилота — 0,0,0): одна из 8 клеток вокруг кресла, где свободно ногам и
     * голове и есть пол (сначала по бокам, потом по углам; пол на уровне кресла или на ступень выше). Вернёт клетку
     * ног {dx, dy, dz} (стоять на её дне) или null — вокруг тесно.
     */
    int[] seatExit() {
        int[][] ring = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int up = 0; up <= 1; up++)
            for (int[] r : ring) {
                int x = r[0], y = up, z = r[1];
                if (shape(x, y, z) == null && shape(x, y + 1, z) == null && shape(x, y - 1, z) != null) return new int[]{x, y, z};
            }
        return null;
    }

    /**
     * Внешние клетки (есть пустой сосед) — центры в системе корабля после поворота turn: для столкновений пилота.
     * 0.27.1: плоским массивом x,y,z подряд (без float[] на клетку) и с коробкой hullLo/hullHi (грубая проверка).
     */
    private float[] hull = new float[0];
    final float[] hullLo = new float[3], hullHi = new float[3];
    private boolean hullDirty = true;

    float[] hull() {
        if (!hullDirty) return hull;
        hullDirty = false;
        it.unimi.dsi.fastutil.floats.FloatArrayList h = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        double t = Math.toRadians(turn), c = Math.cos(t), s = Math.sin(t);
        float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (long k : ids.keySet()) {
            int x = kx(k), y = ky(k), z = kz(k);
            boolean outer = false;
            for (Direction d : Direction.values())
                if (!ids.containsKey(key(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()))) { outer = true; break; }
            if (!outer) continue;
            // поворот turn вокруг Y (как Ry(+turn) у JOML): x' = x·cos + z·sin, z' = −x·sin + z·cos
            float px = (float) (x * c + z * s), py = y + 0.5f - seatH, pz = (float) (-x * s + z * c);
            h.add(px); h.add(py); h.add(pz);
            mn[0] = Math.min(mn[0], px); mn[1] = Math.min(mn[1], py); mn[2] = Math.min(mn[2], pz);
            mx[0] = Math.max(mx[0], px); mx[1] = Math.max(mx[1], py); mx[2] = Math.max(mx[2], pz);
        }
        if (h.isEmpty()) { mn[0] = mn[1] = mn[2] = 0; mx[0] = mx[1] = mx[2] = 0; }
        System.arraycopy(mn, 0, hullLo, 0, 3);
        System.arraycopy(mx, 0, hullHi, 0, 3);
        return hull = h.toFloatArray();
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

    static int kx(long k) { return (int) (k << 1 >> 43); }
    static int ky(long k) { return (int) (k << 22 >> 43); }
    static int kz(long k) { return (int) (k << 43 >> 43); }

    /** Кусок 16³ (по клеткам корабля от пилота). */
    static long sec(int sx, int sy, int sz) { return key(sx, sy, sz); }

    // ───────────────────────── сетка кусками 16³ ─────────────────────────

    /** Кусок сетки: непрозрачное — в видеопамяти, прозрачное (стекло, вода) — вершинами (с позой каждый кадр). */
    private static final class Sec {
        final List<Gpu> gpu = new ArrayList<>();
        Map<RenderType, Layer> cpu = Map.of();
        /** Угол куска в системе корабля (до turn). */
        final float x0, y0, z0;

        Sec(float x0, float y0, float z0) { this.x0 = x0; this.y0 = y0; this.z0 = z0; }

        void release() { for (Gpu g : gpu) g.vb.close(); gpu.clear(); }
    }

    private final Map<Long, Sec> secs = new HashMap<>();
    /** Куски, которые надо пересобрать (клетки менялись). */
    private final Set<Long> dirty = new HashSet<>();
    /** Свет корабля прошлой сборки: по нему видно, где свет поменялся. */
    private Light light;
    private boolean building, everBuilt;
    /** Поколение: release() его меняет — сборка, начатая раньше, ничего не загружает (иначе буферы утекли бы). */
    private int gen;
    /** После ошибки сборки — пауза (не пересобирать каждый кадр). */
    private long retryAt;
    /** Небо сменилось: пересобрать, только если новое держится (у скал и под навесами оно скачет). */
    private int skySeen = -1;
    private long skySince;

    /**
     * Сетка корабля штатным отрисовщиком блоков игры (ModelBlockRenderer — как у обычных блоков мира): затенение в
     * углах, отсечение граней соседями, оттенки травы/листвы. Свет — свой, по блокам корабля (Light). Собирается в
     * фоне: при смене клеток — их куски и те, где поменялся свет; при смене неба — все. Пока куски собираются,
     * рисуются прежние.
     */
    private void kick(int sky) {
        if (building || System.nanoTime() < retryAt) return;
        int worldSky = builtSky;
        if (!everBuilt || builtSky < 0) worldSky = sky;
        else if (sky != builtSky) {
            if (sky != skySeen) { skySeen = sky; skySince = System.nanoTime(); }
            if (System.nanoTime() - skySince > 500_000_000L) worldSky = sky;    // держится полсекунды — пересобрать
        }
        boolean all = !everBuilt || worldSky != builtSky || builtSky < 0;
        if (!all && dirty.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        building = true;
        Set<Long> want = all ? null : new HashSet<>(dirty);
        dirty.clear();
        Long2IntOpenHashMap snap = ids.clone();                                // 0.27.1: копия массивом (было — 39 тыс. записей по одной, фриз ~44 мс)
        Light prev = light;
        int skyNow = worldSky, myGen = gen;
        builtSky = worldSky;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> compute(mc, snap, skyNow, want, prev), net.minecraft.util.Util.backgroundExecutor())
                .thenAcceptAsync(b -> apply(b, myGen), mc)
                .exceptionally(e -> {
                    mc.execute(() -> {
                        if (myGen != gen) return;
                        building = false;
                        builtSky = -1;                                          // пересобрать всё — но не раньше паузы
                        retryAt = System.nanoTime() + 3_000_000_000L;
                        org.slf4j.LoggerFactory.getLogger("HokaOdyssey").warn("корабль: сборка сетки не удалась: {}", e.toString());
                    });
                    return null;
                });
    }

    private record Built(Light light, boolean all, Map<Long, Map<RenderType, Layer>> secs) {}

    /** Фоновый поток: свет всего корабля, какие куски пересобрать, их вершины. */
    private Built compute(Minecraft mc, Long2IntOpenHashMap snap, int worldSky, Set<Long> want, Light prev) {
        Long2ObjectOpenHashMap<BlockState> st = new Long2ObjectOpenHashMap<>(snap.size());
        Map<Long, List<Long>> bySec = new HashMap<>();
        for (var e : snap.long2IntEntrySet()) {
            BlockState s = Block.BLOCK_STATE_REGISTRY.byId(e.getIntValue());
            if (s == null || s.isAir()) continue;
            long k = e.getLongKey();
            st.put(k, s);
            bySec.computeIfAbsent(sec(kx(k) >> 4, ky(k) >> 4, kz(k) >> 4), q -> new ArrayList<>()).add(k);
        }
        Light lt = new Light(st, worldSky);
        boolean all = want == null || prev == null || !lt.sameBox(prev);
        Set<Long> todo = new HashSet<>();
        if (all) todo.addAll(bySec.keySet());
        else {
            todo.addAll(want);
            lt.changedSections(prev, todo);                                     // где из-за изменения поменялся свет
        }
        ShipView view = new ShipView(st, lt, mc.level);
        Map<Long, Map<RenderType, Layer>> out = new HashMap<>();
        RandomSource rnd = RandomSource.create();
        PoseStack ps = new PoseStack();
        var renderer = mc.getBlockRenderer().getModelRenderer();
        for (long sk : todo) {
            Map<RenderType, Layer> m = new LinkedHashMap<>();
            for (long k : bySec.getOrDefault(sk, List.of())) tesselate(mc, view, st, k, m, rnd, ps, renderer);
            out.put(sk, m);
        }
        return new Built(lt, all, out);
    }

    private void tesselate(Minecraft mc, ShipView view, Long2ObjectOpenHashMap<BlockState> cs, long k, Map<RenderType, Layer> m,
                           RandomSource rnd, PoseStack ps, net.minecraft.client.renderer.block.ModelBlockRenderer renderer) {
        BlockState st = cs.get(k);
        if (st == null) return;
        int x = kx(k), y = ky(k), z = kz(k);
        var fluid = st.getFluidState();
        if (!fluid.isEmpty()) {                                                // вода/лава (и затопленные блоки) — штатно, как в мире
            RenderType ft = switch (ItemBlockRenderTypes.getRenderLayer(fluid)) {
                case SOLID -> Sheets.solidBlockSheet();
                case TRANSLUCENT -> Sheets.translucentBlockItemSheet();
                default -> Sheets.cutoutBlockSheet();
            };
            Layer fo = m.computeIfAbsent(ft, q -> new Layer());
            // отрисовщик жидкости кладёт вершины от угла секции (x & 15) — сдвигаем в клетку корабля
            float ox = x - 0.5f - (x & 15), oy = y - seatH - (y & 15), oz = z - 0.5f - (z & 15);
            try {
                mc.getBlockRenderer().renderLiquid(new BlockPos(x, y, z), view, new Shift(new Capture(fo), ox, oy, oz), st, fluid);
            } catch (Exception ignored) {
            }
        }
        if (st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return;
        RenderType type = switch (ItemBlockRenderTypes.getChunkRenderType(st)) {
            case SOLID -> Sheets.solidBlockSheet();
            case TRANSLUCENT -> Sheets.translucentBlockItemSheet();
            default -> Sheets.cutoutBlockSheet();
        };
        Layer out = m.computeIfAbsent(type, q -> new Layer());
        BlockPos pos = new BlockPos(x, y, z);
        rnd.setSeed(st.getSeed(pos));
        var model = mc.getBlockRenderer().getBlockModel(st);
        List<BlockModelPart> partsList = new ArrayList<>();
        model.collectParts(rnd, partsList);
        ps.pushPose();
        ps.translate(x - 0.5f, y - seatH, z - 0.5f);
        try {
            renderer.tesselateBlock(view, partsList, st, pos, ps, new Capture(out), true, OverlayTexture.NO_OVERLAY);
        } catch (Exception ignored) {
        }
        ps.popPose();
    }

    /** Готовые куски, ждущие заливки в видеопамять (0.27.1: по бюджету времени за кадр, а не все разом). */
    private final LinkedHashMap<Long, Map<RenderType, Layer>> pendingUp = new LinkedHashMap<>();

    /** Главный поток: готовые куски — в очередь заливки (прежние рисуются, пока их не заменят). */
    private void apply(Built b, int myGen) {
        if (myGen != gen) return;                                               // сетку уже освободили — не загружать
        everBuilt = true;
        light = b.light;
        if (b.all) {
            for (var e : secs.entrySet()) if (!b.secs.containsKey(e.getKey())) e.getValue().release();
            secs.keySet().retainAll(b.secs.keySet());
        }
        pendingUp.putAll(b.secs);
        drainUploads();                                                         // building снимется, когда очередь опустеет
    }

    /** Залить в видеопамять часть готовых кусков: ~1.5 мс за вызов (не меньше одного куска); всё залито — сборка окончена. */
    private void drainUploads() {
        if (pendingUp.isEmpty()) return;
        long until = System.nanoTime() + 1_500_000L;
        int done = 0;
        var it = pendingUp.entrySet().iterator();
        while (it.hasNext()) {
            if (done > 0 && System.nanoTime() > until) break;
            var e = it.next();
            it.remove();
            done++;
            long k = e.getKey();
            Sec old = secs.remove(k);
            if (old != null) old.release();
            Map<RenderType, Layer> m = e.getValue();
            if (m.isEmpty()) continue;
            Sec s = new Sec(kx(k) * 16 - 0.5f, ky(k) * 16 - seatH, kz(k) * 16 - 0.5f);
            upload(s, m, Sheets.solidBlockSheet(), net.minecraft.client.renderer.RenderPipelines.ENTITY_SOLID);
            upload(s, m, Sheets.cutoutBlockSheet(), net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT);
            s.cpu = m;
            secs.put(k, s);
        }
        if (pendingUp.isEmpty()) building = false;
    }

    /** Блок-сущность клетки (свой экземпляр для рендера игры), позиция — где корабль (для света). */
    private void makeBe(Minecraft mc, long k, int id) {
        BlockState st = Block.BLOCK_STATE_REGISTRY.byId(id);
        if (st == null || !(st.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb)) return;
        try {
            var be = eb.newBlockEntity(BlockPos.containing(mc.player != null ? mc.player.position() : Vec3.ZERO), st);
            if (be == null || mc.getBlockEntityRenderDispatcher().getRenderer(be) == null) return;
            if (mc.level != null) be.setLevel(mc.level);
            bes.put(k, new BE(kx(k) - 0.5f, ky(k) - seatH, kz(k) - 0.5f, be));
        } catch (Exception ignored) {
        }
    }

    /** Блоки-сущности: PoseStack уже в точке пилота и с ориентацией корабля. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void drawEntities(PoseStack ps, ink.hoka.odyssey.client.api.ClientContext.WorldFrame f, int light) {
        if (!everBuilt || bes.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        net.minecraft.client.renderer.state.CameraRenderState cam;
        try {
            cam = f.cameraState();
        } catch (LinkageError e) {                                              // ядро старше 0.15 — без сундуков
            bes.clear();
            return;
        }
        ps.pushPose();
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(turn));
        org.joml.Matrix4f m = ps.last().pose();
        Vec3 camPos = f.camera();
        float pt = f.partialTick();
        for (BE e : bes.values()) {
            try {
                if (mc.level != null && e.be.getLevel() != mc.level) e.be.setLevel(mc.level);
                // в обход проверки игры «далеко ли от камеры» по позиции сущности: она условная (корабль не в мире), иначе
                // сундуки пропадали, стоило отойти от места, где корабль впервые нарисовался. 0.27.1: своя проверка —
                // по настоящему месту: дальше дальности рендера (как у игры, 64) или вне кадра — не рисуем
                net.minecraft.client.renderer.blockentity.BlockEntityRenderer r = dispatcher.getRenderer(e.be);
                if (r == null) continue;
                m.transformPosition(e.x + 0.5f, e.y + 0.5f, e.z + 0.5f, beV);      // относительно камеры
                double vd = r.getViewDistance();
                if ((double) beV.x * beV.x + (double) beV.y * beV.y + (double) beV.z * beV.z > vd * vd) continue;
                double wx = beV.x + camPos.x, wy = beV.y + camPos.y, wz = beV.z + camPos.z;
                if (!f.visible(new net.minecraft.world.phys.AABB(wx - 1.5, wy - 1.5, wz - 1.5, wx + 1.5, wy + 1.5, wz + 1.5))) continue;
                if (e.state == null || e.renderer != r) {                            // состояние — одно на сущность
                    e.state = (net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState) r.createRenderState();
                    e.renderer = r;
                }
                var state = e.state;
                r.extractRenderState(e.be, state, pt, camPos, null);
                state.lightCoords = light;                                      // свет — где корабль
                ps.pushPose();
                ps.translate(e.x, e.y, e.z);
                dispatcher.submit(state, ps, f.submits(), cam);
                ps.popPose();
            } catch (Exception ignored) {
            }
        }
        ps.popPose();
    }

    private final org.joml.Vector3f beV = new org.joml.Vector3f();

    // ───────────────────────── видеопамять ─────────────────────────

    /** Слой в видеопамяти: вершины (формат сущностей, как Sheets), число индексов, конвейер. */
    private record Gpu(com.mojang.blaze3d.buffers.GpuBuffer vb, int indexCount, com.mojang.blaze3d.pipeline.RenderPipeline pipe) {}

    /** Непрозрачный слой куска — в видеопамять ОДИН раз: дальше видеокарте каждый кадр только поза корабля. */
    private static void upload(Sec s, Map<RenderType, Layer> m, RenderType type, com.mojang.blaze3d.pipeline.RenderPipeline pipe) {
        Layer l = m.get(type);
        if (l == null) return;
        if (l.n < 4) { m.remove(type); return; }
        int quads = l.n / 4;
        try (var bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(quads * 4 * 36)) {
            var bb = new com.mojang.blaze3d.vertex.BufferBuilder(bytes, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY);
            float[] f = l.f;
            int[] ii = l.i;
            for (int v = 0; v < quads * 4; v++) {
                int k = v * 8;
                bb.addVertex(f[k], f[k + 1], f[k + 2]).setColor(ii[v * 2]).setUv(f[k + 3], f[k + 4])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(ii[v * 2 + 1]).setNormal(f[k + 5], f[k + 6], f[k + 7]);
            }
            try (var md = bb.buildOrThrow()) {
                var vb = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Hoka ship", 32, md.vertexBuffer());
                s.gpu.add(new Gpu(vb, md.drawState().indexCount(), pipe));
                m.remove(type);                                                 // в видеопамяти — вершинами больше не рисовать
            }
        } catch (Exception ignored) {
            // не вышло — останется вершинами (рисуется по-старому)
        }
    }

    /** Освободить видеопамять (корабль убран / модуль выгружен). */
    void release() {
        gen++;
        building = false;
        pendingUp.clear();
        for (Sec s : secs.values()) s.release();
        secs.clear();
        everBuilt = false;
        builtSky = -1;
        light = null;
    }

    /** Видимые куски этого кадра. */
    private final List<Sec> visible = new ArrayList<>();

    private void drawGpu(PoseStack.Pose pose) {
        boolean any = false;
        for (Sec s : visible) if (!s.gpu.isEmpty()) { any = true; break; }
        if (!any) return;
        Minecraft mc = Minecraft.getInstance();
        org.joml.Matrix4f mv = new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix()).mul(pose.pose());
        var transform = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms()
                .writeTransform(mv, new org.joml.Vector4f(1, 1, 1, 1), new org.joml.Vector3f(), new org.joml.Matrix4f());
        var target = mc.getMainRenderTarget();
        var atlas = mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        var clamp = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.LINEAR);
        var color = com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride != null
                ? com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        var depth = com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride != null
                ? com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        try (var pass = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                .createRenderPass(() -> "Hoka ship", color, java.util.OptionalInt.empty(), depth, java.util.OptionalDouble.empty())) {
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
            pass.bindTexture("Sampler1", mc.gameRenderer.overlayTexture().getTextureView(), clamp);
            pass.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), clamp);
            // 0.27.1: по конвейерам — сначала всё SOLID, потом всё CUTOUT (setPipeline и индексы — раз на конвейер,
            // а не на каждый кусок: раньше слои шли вперемешку и программа менялась на каждом вызове)
            int maxIdx = 0;
            for (Sec s : visible) for (Gpu g : s.gpu) maxIdx = Math.max(maxIdx, g.indexCount);
            var idx = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
            var ib = idx.getBuffer(maxIdx);
            for (int pi = 0; pi < 2; pi++) {
                var pipe = pi == 0 ? net.minecraft.client.renderer.RenderPipelines.ENTITY_SOLID : net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT;
                boolean set = false;
                for (Sec s : visible)
                    for (Gpu g : s.gpu) {
                        if (g.pipe != pipe) continue;
                        if (!set) { pass.setPipeline(pipe); pass.setIndexBuffer(ib, idx.type()); set = true; }
                        pass.setVertexBuffer(0, g.vb);
                        pass.drawIndexed(0, 0, g.indexCount, 1);
                    }
            }
        }
    }

    /** Нарисовать (без отсечения кусков по кадру). */
    void draw(PoseStack ps, MultiBufferSource buffers, int light) { draw(ps, buffers, light, null); }

    /**
     * Нарисовать: PoseStack уже в точке пилота (относительно камеры) и с ориентацией корабля; здесь — поворот turn и
     * куски. f — для отсечения кусков, которых нет в кадре (null — рисовать все).
     */
    void draw(PoseStack ps, MultiBufferSource buffers, int light, ink.hoka.odyssey.client.api.ClientContext.WorldFrame f) {
        drainUploads();                                                         // 0.27.1: заливка кусков по кадрам
        kick(net.minecraft.client.renderer.LightTexture.sky(light));
        if (secs.isEmpty()) return;                                             // первая сборка ещё идёт
        ps.pushPose();
        ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees(turn));
        PoseStack.Pose pose = ps.last();
        visible.clear();
        org.joml.Matrix4f m = pose.pose();
        Vec3 cam = f != null ? f.camera() : null;
        org.joml.Vector3f v = new org.joml.Vector3f();
        for (Sec s : secs.values()) {
            if (f != null) {                                                    // коробка куска → мир → видна ли в кадре
                double ax = Double.MAX_VALUE, ay = ax, az = ax, bx = -ax, by = -ax, bz = -ax;
                for (int c = 0; c < 8; c++) {
                    m.transformPosition(s.x0 + ((c & 1) != 0 ? 17 : -1), s.y0 + ((c & 2) != 0 ? 17 : -1), s.z0 + ((c & 4) != 0 ? 17 : -1), v);
                    ax = Math.min(ax, v.x); ay = Math.min(ay, v.y); az = Math.min(az, v.z);
                    bx = Math.max(bx, v.x); by = Math.max(by, v.y); bz = Math.max(bz, v.z);
                }
                if (!f.visible(new net.minecraft.world.phys.AABB(ax + cam.x, ay + cam.y, az + cam.z, bx + cam.x, by + cam.y, bz + cam.z))) continue;
            }
            visible.add(s);
        }
        drawGpu(pose);                                                          // непрозрачное — из видеопамяти
        for (Sec s : visible)
            for (var g : s.cpu.entrySet()) {                                    // прозрачное — вершинами
                var vc = buffers.getBuffer(g.getKey());
                Layer l = g.getValue();
                float[] fl = l.f;
                int[] ii = l.i;
                for (int q = 0; q < l.n; q++) {
                    int k = q * 8;
                    vc.addVertex(pose, fl[k], fl[k + 1], fl[k + 2]).setColor(ii[q * 2]).setUv(fl[k + 3], fl[k + 4])
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(ii[q * 2 + 1]).setNormal(pose, fl[k + 5], fl[k + 6], fl[k + 7]);
                }
            }
        ps.popPose();
    }

    /** Сдвиг вершин (жидкости: отрисовщик кладёт их от угла секции, нам — от клетки корабля). */
    private record Shift(com.mojang.blaze3d.vertex.VertexConsumer to, float dx, float dy, float dz) implements com.mojang.blaze3d.vertex.VertexConsumer {
        @Override public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) { to.addVertex(x + dx, y + dy, z + dz); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { to.setColor(r, g, b, a); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) { to.setColor(argb); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { to.setUv(u, v); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { to.setUv1(u, v); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { to.setUv2(u, v); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { to.setNormal(x, y, z); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float w) { return this; }
    }

    /**
     * Запись вершин отрисовщика блоков: он отдаёт уже сдвинутые вершины (PoseStack с переводом в клетку) — копим их
     * в слой как есть, рисуем потом с позой корабля.
     */
    private static final class Capture implements com.mojang.blaze3d.vertex.VertexConsumer {
        private final Layer out;
        private float x, y, z, u, v, nx, ny = 1, nz;
        private int color = -1, light;
        private boolean open;

        Capture(Layer out) { this.out = out; }

        private void flush() { if (open) { out.add(x, y, z, color, u, v, light, nx, ny, nz); open = false; } }

        @Override public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            flush();
            this.x = x; this.y = y; this.z = z; color = -1; light = 0xF000F0; nx = 0; ny = 1; nz = 0;
            open = true;
            return this;
        }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { color = a << 24 | r << 16 | g << 8 | b; return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) { color = argb; return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { light = (v & 0xFFFF) << 16 | (u & 0xFFFF); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { nx = x; ny = y; nz = z; flush(); return this; }
        @Override public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float w) { return this; }
    }

    /**
     * Свет корабля: небо — сверху вниз до непрозрачных блоков (под крышей темнее, вбок −1, как у игры), но не ярче неба
     * там, где корабль; блоки — от источников света корабля (факелы, лампы), −1 за шаг, через непрозрачные не проходит.
     */
    private static final class Light {
        final int x0, y0, z0, sx, sy, sz, worldSky;
        final byte[] sky, block;
        private final Long2ObjectOpenHashMap<BlockState> cells;

        Light(Long2ObjectOpenHashMap<BlockState> cells, int worldSky) {
            this.cells = cells;
            this.worldSky = worldSky;
            int[] mn = {0, 0, 0}, mx = {0, 0, 0};
            boolean first = true;
            for (long k : cells.keySet()) {
                int[] p = {kx(k), ky(k), kz(k)};
                if (first) { mn = p.clone(); mx = p.clone(); first = false; continue; }
                for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], p[i]); mx[i] = Math.max(mx[i], p[i]); }
            }
            x0 = mn[0] - 1; y0 = mn[1] - 1; z0 = mn[2] - 1;
            sx = mx[0] - mn[0] + 3; sy = mx[1] - mn[1] + 3; sz = mx[2] - mn[2] + 3;
            sky = new byte[sx * sy * sz];
            block = new byte[sx * sy * sz];
            it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue q = new it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue();
            for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) {        // небо: сверху вниз без потерь до непрозрачного
                for (int y = sy - 1; y >= 0; y--) {
                    if (opaque(x, y, z)) break;
                    sky[idx(x, y, z)] = (byte) worldSky;
                    q.enqueue(idx(x, y, z));
                }
            }
            spread(sky, q);
            for (var e : cells.long2ObjectEntrySet()) {
                int em = e.getValue().getLightEmission();
                if (em <= 0) continue;
                long k = e.getLongKey();
                int i = idx(kx(k) - x0, ky(k) - y0, kz(k) - z0);
                block[i] = (byte) em;
                q.enqueue(i);
            }
            spread(block, q);
        }

        boolean sameBox(Light o) {
            return o.x0 == x0 && o.y0 == y0 && o.z0 == z0 && o.sx == sx && o.sy == sy && o.sz == sz && o.worldSky == worldSky;
        }

        /** Куски, где свет не такой, как в прошлой сборке (и соседние — сглаженный свет берёт соседей). */
        void changedSections(Light o, Set<Long> out) {
            for (int i = 0; i < sky.length; i++) {
                if (sky[i] == o.sky[i] && block[i] == o.block[i]) continue;
                int x = i % sx + x0, z = (i / sx) % sz + z0, y = i / (sx * sz) + y0;
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int c = -1; c <= 1; c++)
                    out.add(sec((x + a) >> 4, (y + b) >> 4, (z + c) >> 4));
            }
        }

        private int idx(int x, int y, int z) { return (y * sz + z) * sx + x; }

        private boolean opaque(int x, int y, int z) {
            BlockState st = cells.get(key(x + x0, y + y0, z + z0));
            return st != null && st.canOcclude();
        }

        private void spread(byte[] lv, it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue q) {
            while (!q.isEmpty()) {
                int i = q.dequeueInt(), l = lv[i];
                if (l <= 1) continue;
                int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
                for (int d = 0; d < 6; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0), nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz || opaque(nx, ny, nz)) continue;
                    int j = idx(nx, ny, nz);
                    if (lv[j] >= l - 1) continue;
                    lv[j] = (byte) (l - 1);
                    q.enqueue(j);
                }
            }
        }

        int at(boolean skyLayer, BlockPos p) {
            int x = p.getX() - x0, y = p.getY() - y0, z = p.getZ() - z0;
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return skyLayer ? worldSky : 0;
            return (skyLayer ? sky : block)[idx(x, y, z)];
        }
    }

    /** Корабль как кусок мира для отрисовщика блоков: клетки — блоки (в системе корабля), снаружи воздух; свет — Light. */
    private static final class ShipView implements net.minecraft.world.level.BlockAndTintGetter {
        private final Long2ObjectOpenHashMap<BlockState> cells;
        private final Light light;
        private final net.minecraft.world.level.Level level;

        ShipView(Long2ObjectOpenHashMap<BlockState> cells, Light light, net.minecraft.world.level.Level level) {
            this.cells = cells;
            this.light = light;
            this.level = level;
        }

        @Override public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos p) {
            return light.at(layer == net.minecraft.world.level.LightLayer.SKY, p);
        }

        @Override public float getShade(Direction d, boolean shade) {
            return level != null ? level.getShade(d, shade) : 1f;
        }

        @Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() {
            return level != null ? level.getLightEngine() : null;
        }

        @Override public int getBlockTint(BlockPos p, net.minecraft.world.level.ColorResolver r) {
            if (level == null) return -1;
            var mc = Minecraft.getInstance();
            BlockPos at = mc.player != null ? mc.player.blockPosition() : BlockPos.ZERO;
            return level.getBlockTint(at, r);                                 // биом там, где корабль (где игрок)
        }

        @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p) { return null; }

        @Override public BlockState getBlockState(BlockPos p) {
            BlockState st = cells.get(key(p.getX(), p.getY(), p.getZ()));
            return st != null ? st : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }

        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }

        @Override public int getHeight() { return 4096; }

        @Override public int getMinY() { return -2048; }
    }
}
