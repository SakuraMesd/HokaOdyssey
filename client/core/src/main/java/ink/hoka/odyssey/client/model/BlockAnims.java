package ink.hoka.odyssey.client.model;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.84 Анимированные модели блоков (поле {@code animation} своего блока). Неподвижные кости модели лежат в меше
 * чанка, как у всех своих блоков; подвижные (у которых в анимации есть ключи, и их потомки) рисуются здесь каждый
 * кадр — конвейером моделей ({@link Pose}, текстуры {@link Models}), в позиции блока с его поворотом. Сущностей нет.
 * <p>
 * Время анимации общее по миру (игровое время сервера) — у всех игроков и всех блоков одинаковое. Ближе
 * {@link #ANIM_RANGE} блоков — анимация, до {@link #SCAN_RANGE} — подвижные кости в позе по умолчанию, дальше — только
 * меш (мелкие подвижные детали не рисуются). Блоки ищутся раз в {@link #SCAN_TICKS} тиков по палитрам секций рядом
 * с игроком; на кадр объекты не создаются (позы и матрицы — заранее).
 * <p>0.89: блоки сгруппированы по (модель, анимация) — поза считается один раз на группу за кадр, хоть у группы
 * четыре поворота и сотни блоков (трубы ставят сотнями); проход по блокам — только своей группы.</p>
 */
public final class BlockAnims {

    private BlockAnims() {}

    public static final int ANIM_RANGE = 48, SCAN_RANGE = 64, SCAN_TICKS = 10;

    /** Вид анимированного блока: модель, анимация и переход «пиксели модели → клетка блока» (поворот, сдвиг, 1/16). */
    public record Type(String path, String anim, Matrix4f local) {}

    private static volatile Map<BlockState, Type> TYPES = Map.of();

    /** Состояние вида на кадр: модель (меняется, если файл обновили), позы, подвижные кости. */
    private static final class Live {
        BbModel model;
        BbAnimation anim;
        boolean[] moving;
        Matrix4f[] pose = new Matrix4f[0], rest = new Matrix4f[0];
        Identifier[] tex = new Identifier[0];
        boolean ready;
    }

    private static final class At {
        final BlockPos pos;
        final BlockState state;
        final Type type;
        final AABB box;
        At(BlockPos pos, BlockState state, Type type) {
            this.pos = pos;
            this.state = state;
            this.type = type;
            this.box = new AABB(pos).inflate(1.5);                                // модель больше клетки (2×2 и т.п.)
        }
    }

    /** Блоки одной модели с одной анимацией (повороты — разные состояния, поза общая). */
    private static final class Group {
        final String path, anim;
        final List<At> ats = new ArrayList<>();
        Group(String path, String anim) { this.path = path; this.anim = anim; }
    }

    private static final Map<String, Live> LIVE = new HashMap<>();             // «модель#анимация» → поза
    private static List<Group> found = List.of();
    private static int ticks;
    private static final Pose.Scratch SCRATCH = new Pose.Scratch();
    private static final Matrix4f BASE = new Matrix4f(), M = new Matrix4f();

    /** Новый список своих блоков: какие состояния анимированные (главные клетки). */
    public static void set(Map<BlockState, Type> types) {
        TYPES = types;
        LIVE.clear();
        found = List.of();
        COLS.clear();
        SCANNED.clear();
        DIRTY.clear();
        ticks = 0;
    }

    public static boolean any() { return !TYPES.isEmpty(); }

    // ───────────────────────── поиск ─────────────────────────

    /**
     * 0.90: поиск — по столбцам с бюджетом, без полного прохода: столбец, где сменился блок ({@link #dirty} —
     * из LevelRenderer.setSectionDirty), — в ближайший тик; новый (вошёл в дальность) — тоже; остальные — перепроверка
     * раз в {@link #RESCAN_TICKS} (на случай пропущенной пометки). За тик — не больше {@link #COLS_PER_TICK} столбцов.
     * Раньше всё рядом перебиралось дважды в секунду: на базе с сотнями видов блоков (общая палитра секций) — 13%
     * времени кадра и рывки.
     */
    public static void tick() {
        Map<BlockState, Type> types = TYPES;
        var mc = Minecraft.getInstance();
        if (types.isEmpty() || mc.level == null || mc.player == null) {
            if (!found.isEmpty() || !COLS.isEmpty()) { found = List.of(); COLS.clear(); SCANNED.clear(); }
            DIRTY.clear();
            return;
        }
        ticks++;
        var lvl = mc.level;
        int r = SCAN_RANGE >> 4;
        BlockPos pp = mc.player.blockPosition();
        int pcx = pp.getX() >> 4, pcy = pp.getY() >> 4, pcz = pp.getZ() >> 4;
        boolean changed = false;
        if (ticks % 20 == 0) {                                                  // ушедшие из дальности — забыть
            changed = COLS.keySet().removeIf(k -> Math.abs(colX(k) - pcx) > r || Math.abs(colZ(k) - pcz) > r);
            SCANNED.keySet().removeIf(k -> Math.abs(colX(k) - pcx) > r || Math.abs(colZ(k) - pcz) > r);
        }
        int budget = COLS_PER_TICK;
        // 1) столбцы со сменой блоков
        if (!DIRTY.isEmpty()) {
            var it = DIRTY.iterator();
            while (it.hasNext() && budget > 0) {
                long key = it.next();
                it.remove();
                if (Math.abs(colX(key) - pcx) > r || Math.abs(colZ(key) - pcz) > r) continue;
                changed |= rescan(lvl, key, pcy, r, types);
                budget--;
            }
        }
        // 2) новые столбцы и 3) давно не проверенные — по кругу от игрока
        for (int cx = pcx - r; cx <= pcx + r && budget > 0; cx++) for (int cz = pcz - r; cz <= pcz + r && budget > 0; cz++) {
            long key = key(cx, cz);
            Integer at = SCANNED.get(key);
            if (at != null && ticks - at < RESCAN_TICKS) continue;
            changed |= rescan(lvl, key, pcy, r, types);
            budget--;
        }
        if (!changed) return;
        Map<String, Group> out = new LinkedHashMap<>();
        for (List<At> col : COLS.values()) for (At at : col)
            out.computeIfAbsent(at.type.path + "#" + at.type.anim, k -> new Group(at.type.path, at.type.anim)).ats.add(at);
        found = List.copyOf(out.values());
    }

    static final int COLS_PER_TICK = 3, RESCAN_TICKS = 100;
    /** Когда столбец проверяли (тик). */
    private static final Map<Long, Integer> SCANNED = new HashMap<>();
    /** Столбцы со сменой блоков (главный поток). */
    private static final java.util.LinkedHashSet<Long> DIRTY = new java.util.LinkedHashSet<>();

    /** Секция перестраивается (смена блоков, пришёл чанк/куб) — перепроверить её столбец. Главный поток. */
    public static void dirty(int sx, int sz) {
        if (!TYPES.isEmpty()) DIRTY.add(key(sx, sz));
    }

    private static long key(int cx, int cz) { return (((long) cx) << 32) | (cz & 0xFFFFFFFFL); }

    private static boolean rescan(net.minecraft.client.multiplayer.ClientLevel lvl, long key, int pcy, int r, Map<BlockState, Type> types) {
        SCANNED.put(key, ticks);
        List<At> col = scanColumn(lvl, colX(key), colZ(key), pcy, r, types);
        List<At> was = COLS.get(key);
        if (col.isEmpty()) return was != null && COLS.remove(key) != null;
        if (was == null || !same(was, col)) { COLS.put(key, col); return true; }
        return false;
    }

    /** Найденные блоки по столбцам: (x << 32 | z) → блоки. */
    private static final Map<Long, List<At>> COLS = new HashMap<>();

    private static int colX(long k) { return (int) (k >> 32); }
    private static int colZ(long k) { return (int) k; }

    private static boolean same(List<At> a, List<At> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).pos.equals(b.get(i).pos) || a.get(i).state != b.get(i).state) return false;
        return true;
    }

    private static List<At> scanColumn(net.minecraft.client.multiplayer.ClientLevel lvl, int cx, int cz, int pcy, int r, Map<BlockState, Type> types) {
        if (!(lvl.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) instanceof LevelChunk ch)) return List.of();
        LevelChunkSection[] secs = ch.getSections();
        List<At> out = null;
        for (int sy = pcy - r; sy <= pcy + r; sy++) {
            int idx = ch.getSectionIndexFromSectionY(sy);
            if (idx < 0 || idx >= secs.length) continue;
            LevelChunkSection sec = secs[idx];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(types::containsKey)) continue;
            // есть ли нужный блок: count() — один проход по 4096 номерам с хэш-счётчиком (getAll строит IntArraySet —
            // на общей палитре в сотни видов он квадратичный и сам медленнее перебора клеток)
            boolean[] has = {false};
            sec.getStates().count((st, n) -> { if (types.containsKey(st)) has[0] = true; });
            if (!has[0]) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState st = sec.getBlockState(x, y, z);
                Type t = types.get(st);
                if (t == null) continue;
                if (out == null) out = new ArrayList<>();
                out.add(new At(new BlockPos((cx << 4) + x, (sy << 4) + y, (cz << 4) + z), st, t));
            }
        }
        return out == null ? List.of() : out;
    }

    /** Сколько анимированных блоков найдено рядом (отладка, тесты). */
    public static int count() {
        int n = 0;
        for (Group g : found) n += g.ats.size();
        return n;
    }

    // ───────────────────────── рисование ─────────────────────────

    private static Live live(Group g) {
        Live l = LIVE.computeIfAbsent(g.path + "#" + g.anim, k -> new Live());
        BbModel m = Models.model(g.path);
        if (m == null) { l.ready = false; return l; }
        if (m != l.model) {                                                       // модель пришла или обновилась
            l.model = m;
            l.anim = m.animations.get(g.anim);
            l.moving = l.anim != null ? l.anim.moving(m) : new boolean[m.bones.size()];
            l.pose = new Matrix4f[m.bones.size()];
            l.rest = new Matrix4f[m.bones.size()];
            Pose.compute(m, null, -1, l.rest, SCRATCH);
            l.tex = Models.textures(g.path);
        }
        l.ready = l.anim != null;
        return l;
    }

    public static void render(WorldRenderContext ctx) {
        List<Group> groups = found;
        if (groups.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return;
        Vec3 cam = Models.camera();
        Frustum frustum = Models.frustum();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float sec = ((level.getGameTime() % 1_728_000L) + pt) / 20f;           // общее время мира (сутки по 24 000 тиков × 72)
        MultiBufferSource src = ctx.consumers();
        Matrix4f view = ctx.matrices().last().pose();
        double range2 = (double) ANIM_RANGE * ANIM_RANGE;
        for (Group g : groups) {
            Live l = live(g);
            if (!l.ready) continue;
            Pose.compute(l.model, l.anim, l.anim.localTime(sec), l.pose, SCRATCH);   // поза на кадр — одна на группу
            for (int ti = 0; ti < l.tex.length; ti++) {
                Identifier tex = l.tex[ti];
                if (tex == null) continue;
                RenderType type = RenderTypes.entityCutoutNoCull(tex);
                VertexConsumer vc = null;
                for (At at : g.ats) {
                    if (frustum != null && !frustum.isVisible(at.box)) continue;
                    if (level.getBlockState(at.pos) != at.state) continue;          // сломали (до следующего поиска)
                    double dx = at.pos.getX() + 0.5 - cam.x, dy = at.pos.getY() + 0.5 - cam.y, dz = at.pos.getZ() + 0.5 - cam.z;
                    Matrix4f[] pose = dx * dx + dy * dy + dz * dz <= range2 ? l.pose : l.rest;
                    if (vc == null) vc = src.getBuffer(type);
                    int light = LevelRenderer.getLightColor(level, at.pos);
                    BASE.set(view).translate((float) (at.pos.getX() - cam.x), (float) (at.pos.getY() - cam.y), (float) (at.pos.getZ() - cam.z))
                            .mul(at.type.local);
                    draw(vc, l.model, l.moving, pose, BASE, ti, light);
                }
                if (vc != null && src instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
            }
        }
    }

    /** Подвижные кости модели с текстурой ti в позе pose (base — блок в мире). Без созданий объектов. */
    static void draw(VertexConsumer vc, BbModel model, boolean[] moving, Matrix4f[] pose, Matrix4f base, int ti, int light) {
        for (BbModel.Bone b : model.bones) {
            if (!moving[b.index] || b.faces.isEmpty() || "hitbox".equals(b.name)) continue;
            M.set(base).mul(pose[b.index]);
            for (BbModel.Face f : b.faces) if (f.texture() == ti) quad(vc, M, f, light);
        }
    }

    private static final Vector3f P0 = new Vector3f(), P1 = new Vector3f(), P2 = new Vector3f(), P3 = new Vector3f(),
            N = new Vector3f(), T = new Vector3f();

    private static void quad(VertexConsumer vc, Matrix4f m, BbModel.Face f, int light) {
        float[] p = f.xyz(), uv = f.uv();
        m.transformPosition(p[0], p[1], p[2], P0);
        m.transformPosition(p[3], p[4], p[5], P1);
        m.transformPosition(p[6], p[7], p[8], P2);
        m.transformPosition(p[9], p[10], p[11], P3);
        N.set(P1).sub(P0).cross(T.set(P3).sub(P0)).normalize();
        if (!Float.isFinite(N.x)) N.set(0, 1, 0);
        vertex(vc, P0, uv[0], uv[1], light);
        vertex(vc, P1, uv[2], uv[3], light);
        vertex(vc, P2, uv[4], uv[5], light);
        vertex(vc, P3, uv[6], uv[7], light);
    }

    private static void vertex(VertexConsumer vc, Vector3f p, float u, float v, int light) {
        vc.addVertex(p.x, p.y, p.z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(N.x, N.y, N.z);
    }
}
