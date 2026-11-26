package ink.hoka.odyssey.client.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.odyssey.client.model.BbModel;
import ink.hoka.odyssey.client.model.Pose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Свои блоки. Сервер присылает файл {@value #DEFS} — список блоков: какое ванильное состояние-носитель (нотный
 * блок, листва, растяжка — их физику и звук игра знает сама) и как его рисовать: 6 текстур куба или модель
 * .bbmodel. Мод подменяет модель носителя при отрисовке чанков (работает и с Sodium), текстуры кладёт в ячейки
 * атласа ({@link BlockSlots}). Добавили блок или сменили текстуру на сервере — у игрока сразу, без загрузки
 * ресурсов: чанки только перестраиваются.
 */
public final class CustomBlocks {

    private CustomBlocks() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey/blocks");
    public static final String DEFS = "blocks/blocks.json";

    /** Где файлы (ядро подставляет хранилище файлов с сервера). */
    public interface Source {
        byte[] get(String path);
        void listen(String path, Consumer<byte[]> fn);
    }

    private static Source source;
    private static final Set<String> LISTENING = new HashSet<>();
    private static boolean dirty;

    // читаются потоками сборки чанков (Sodium) — только целиком заменяемые неизменяемые карты
    private static volatile Map<BlockState, BlockStateModel> MODELS = Map.of();
    private static volatile Map<BlockState, ChunkSectionLayer> LAYERS = Map.of();
    private static volatile Map<BlockState, net.minecraft.world.level.block.SoundType> SOUNDS = Map.of();
    private static volatile Map<String, Map<Integer, BlockState>> PLACE = Map.of();       // id блока → поворот → носитель

    public static void init(Source s) { source = s; }

    /** Подключились к серверу: следить за списком блоков. */
    public static void connect() {
        listen(DEFS);
        listen(CustomItems.DEFS);
    }

    /** Ушли с сервера: блоки снова ванильные. */
    public static void clear() {
        boolean had = !MODELS.isEmpty();
        MODELS = Map.of();
        LAYERS = Map.of();
        SOUNDS = Map.of();
        PLACE = Map.of();
        CustomItems.set(Map.of(), Map.of());
        LISTENING.clear();
        dirty = false;
        if (had) remesh();
    }

    public static BlockStateModel model(BlockState s) {
        Map<BlockState, BlockStateModel> m = MODELS;
        return m.isEmpty() ? null : m.get(s);
    }

    public static ChunkSectionLayer layer(BlockState s) {
        Map<BlockState, ChunkSectionLayer> m = LAYERS;
        return m.isEmpty() ? null : m.get(s);
    }

    /** Звуки своего блока (шаги, ломание, установка…) или null — как у носителя. */
    public static net.minecraft.world.level.block.SoundType soundType(BlockState s) {
        Map<BlockState, net.minecraft.world.level.block.SoundType> m = SOUNDS;
        return m.isEmpty() ? null : m.get(s);
    }

    /**
     * Какое состояние поставить предметом своего блока (предсказание клиента — то же, что решит сервер): без
     * мелькания носителя и с правильным звуком установки. null — не наш.
     */
    public static BlockState placeState(String id, float yaw) {
        Map<Integer, BlockState> m = PLACE.get(id);
        if (m == null || m.isEmpty()) return null;
        int rot = Math.floorMod(Math.round(yaw / 90f) * 90, 360);
        BlockState st = m.get(rot);
        return st != null ? st : m.getOrDefault(0, m.values().iterator().next());
    }

    public static boolean isCustom(BlockState s) {
        Map<BlockState, BlockStateModel> m = MODELS;
        return !m.isEmpty() && m.containsKey(s);
    }

    /**
     * Обычный блок (не наш предмет) встал бы в состояние-носитель — тогда как сервер (BlocksModule.foreign):
     * состояние по умолчанию (листва persistent=false, нотный блок «арфа», нить без связей). Иначе — как есть.
     */
    public static BlockState foreign(BlockState s) {
        if (s == null || !isCustom(s)) return s;
        BlockState d = s.getBlock().defaultBlockState();
        return isCustom(d) ? s : d;
    }

    static void listen(String path) {
        if (LISTENING.add(path)) source.listen(path, b -> dirty = true);
    }

    /** Каждый тик клиента: пересобрать, если файлы сменились или атлас пересоздан. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (source == null || mc.level == null) return;
        if (!dirty && (!MODELS.isEmpty() || CustomItems.any()) && BlockSlots.stale(atlas())) dirty = true;
        if (!dirty) return;
        dirty = false;
        try {
            rebuild();
        } catch (Exception e) {
            LOG.error("свои блоки не собраны", e);
        }
    }

    private static TextureAtlas atlas() {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
    }

    // ───────────────────────── сборка ─────────────────────────

    private static void rebuild() {
        byte[] defs = source.get(DEFS), itemDefs = source.get(CustomItems.DEFS);
        if (defs == null && itemDefs == null) {
            CustomItems.set(Map.of(), Map.of());
            if (!MODELS.isEmpty()) { MODELS = Map.of(); LAYERS = Map.of(); remesh(); }
            return;
        }
        if (!BlockSlots.begin(atlas())) {
            LOG.error("в атласе нет ячеек hoka_odyssey:block/s16_* — ресурсы мода не загружены?");
            return;
        }
        JsonObject root = defs == null ? new JsonObject() : JsonParser.parseString(new String(defs, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!root.has("blocks")) root.add("blocks", new com.google.gson.JsonArray());
        Map<String, CustomItems.Item> blockItems = new java.util.HashMap<>();
        Map<BlockState, BlockStateModel> models = new IdentityHashMap<>();
        Map<BlockState, ChunkSectionLayer> layers = new IdentityHashMap<>();
        Map<BlockState, net.minecraft.world.level.block.SoundType> sounds = new IdentityHashMap<>();
        Map<String, Map<Integer, BlockState>> place = new java.util.HashMap<>();
        int ok = 0, bad = 0;
        for (JsonElement el : root.getAsJsonArray("blocks")) {
            JsonObject d = el.getAsJsonObject();
            String id = d.has("id") ? d.get("id").getAsString() : "?";
            try {
                BlockState st = state(d.getAsJsonObject("state"));
                if (st == null) { LOG.warn("{}: носитель не найден", id); bad++; continue; }
                Built b = d.has("model") ? fromModel(d.get("model").getAsString(), d.get("rot").getAsInt())
                        : fromCube(d.getAsJsonObject("faces"), d.get("rot").getAsInt());
                if (b == null) { bad++; continue; }
                TextureAtlasSprite particle = b.particle;
                if (d.has("particle")) {
                    String p = d.get("particle").getAsString();
                    listen(p);
                    BlockSlots.Slot s = BlockSlots.slot(p, source.get(p));
                    if (s != null) particle = s.sprite();
                }
                boolean ao = !d.has("ao") || d.get("ao").getAsBoolean();
                models.put(st, new Model(new Part(b.quads, particle, ao)));
                int rot = d.get("rot").getAsInt();
                place.computeIfAbsent(id, k -> new java.util.HashMap<>()).put(rot, st);
                if (d.has("sounds")) sounds.put(st, BlockSounds.of(d.getAsJsonObject("sounds"), st.getBlock().defaultBlockState()));
                if (rot == 0 || !blockItems.containsKey(id)) blockItems.put(id, CustomItems.blockItem(id, b.quads, particle));
                layers.put(st, switch (d.has("layer") ? d.get("layer").getAsString() : "cutout") {
                    case "solid" -> ChunkSectionLayer.SOLID;
                    case "translucent" -> ChunkSectionLayer.TRANSLUCENT;
                    default -> ChunkSectionLayer.CUTOUT;
                });
                ok++;
            } catch (Exception e) {
                LOG.warn("{}: не собран: {}", id, e.toString());
                bad++;
            }
        }
        Map<String, CustomItems.Item> items = Map.of();
        if (itemDefs != null) {
            try {
                items = CustomItems.build(JsonParser.parseString(new String(itemDefs, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (Exception e) {
                LOG.error("свои предметы не собраны", e);
            }
        }
        BlockSlots.end();
        CustomItems.set(items, blockItems);
        SOUNDS = Collections.unmodifiableMap(sounds);
        PLACE = Map.copyOf(place);
        MODELS = Collections.unmodifiableMap(models);
        LAYERS = Collections.unmodifiableMap(layers);
        LOG.info("свои блоки: {} собрано{}", ok, bad > 0 ? ", с ошибками " + bad : "");
        remesh();
    }

    private static void remesh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.levelRenderer != null) mc.levelRenderer.allChanged();
    }

    private static BlockState state(JsonObject s) {
        Optional<Block> ob = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(s.get("block").getAsString()));
        if (ob.isEmpty()) return null;
        BlockState st = ob.get().defaultBlockState();
        if (s.has("props")) for (var e : s.getAsJsonObject("props").entrySet()) {
            Property<?> p = ob.get().getStateDefinition().getProperty(e.getKey());
            if (p == null) return null;
            st = with(st, p, e.getValue().getAsString());
            if (st == null) return null;
        }
        return st;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState st, Property<T> p, String v) {
        return p.getValue(v).map(val -> st.setValue(p, val)).orElse(null);
    }

    record Built(List<BakedQuad>[] quads, TextureAtlasSprite particle) {}

    @SuppressWarnings("unchecked")
    private static List<BakedQuad>[] lists() {
        List<BakedQuad>[] l = new List[7];
        for (int i = 0; i < 7; i++) l[i] = new ArrayList<>();
        return l;
    }

    private static final String[] FACE = {"down", "up", "north", "south", "west", "east"};   // порядок Direction

    /** Куб на весь блок: 6 граней, у каждой своя текстура (путь файла). */
    private static Built fromCube(JsonObject faces, int rot) {
        List<BakedQuad>[] q = lists();
        TextureAtlasSprite particle = null;
        Matrix4f m = rotation(rot);
        for (Direction dir : Direction.values()) {
            String path = faces.has(FACE[dir.ordinal()]) ? faces.get(FACE[dir.ordinal()]).getAsString() : null;
            if (path == null) continue;
            listen(path);
            BlockSlots.Slot s = BlockSlots.slot(path, source.get(path));
            if (s == null) continue;
            if (particle == null || dir == Direction.NORTH) particle = s.sprite();
            float[][] c = corners(dir);
            float[] xyz = new float[12];
            for (int i = 0; i < 4; i++) { xyz[i * 3] = c[i][0]; xyz[i * 3 + 1] = c[i][1]; xyz[i * 3 + 2] = c[i][2]; }
            add(q, m, xyz, new float[]{0, 0, 0, 1, 1, 1, 1, 0}, s);
        }
        return particle == null ? null : new Built(q, particle);
    }

    /** Углы грани куба 0..1 — тот же порядок, что у ванили (и у {@link BbModel}). */
    static float[][] corners(Direction d) {
        return switch (d) {
            case NORTH -> new float[][]{{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}};
            case SOUTH -> new float[][]{{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}};
            case EAST -> new float[][]{{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}};
            case WEST -> new float[][]{{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}};
            case UP -> new float[][]{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}};
            case DOWN -> new float[][]{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
        };
    }

    /** Файл с сервера (и следить за ним). */
    static byte[] file(String path) {
        listen(path);
        return source.get(path);
    }

    /** Текстура файла в ячейке атласа (и следить за файлом). */
    static BlockSlots.Slot texture(String path) {
        return BlockSlots.slot(path, file(path));
    }

    /** Модель .bbmodel в позе по умолчанию. Блок Blockbench «Java Block» — 0..16; прочие форматы — центр в 0. */
    static Built fromModel(String path, int rot) {
        listen(path);
        byte[] bytes = source.get(path);
        if (bytes == null) return null;
        JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        boolean javaBlock = json.has("meta") && json.getAsJsonObject("meta").has("model_format")
                && "java_block".equals(json.getAsJsonObject("meta").get("model_format").getAsString());
        BbModel bb = BbModel.parse(path, json);
        BlockSlots.Slot[] slots = new BlockSlots.Slot[bb.texturePngs.size()];
        TextureAtlasSprite particle = null;
        for (int i = 0; i < slots.length; i++) {
            slots[i] = BlockSlots.slot(path + "#" + i, bb.texturePngs.get(i));
            if (particle == null && slots[i] != null) particle = slots[i].sprite();
        }
        if (particle == null) { LOG.warn("{}: у модели нет текстур", path); return null; }
        Matrix4f[] pose = new Matrix4f[bb.bones.size()];
        Pose.compute(bb, null, -1, pose);
        float off = javaBlock ? 0 : 8;
        Matrix4f base = rotation(rot).translate(off / 16f, 0, off / 16f).scale(1 / 16f);
        List<BakedQuad>[] q = lists();
        for (BbModel.Bone b : bb.bones) {
            if ("hitbox".equals(b.name)) continue;
            Matrix4f m = new Matrix4f(base).mul(pose[b.index]);
            for (BbModel.Face f : b.faces) {
                BlockSlots.Slot s = f.texture() < slots.length ? slots[f.texture()] : null;
                if (s != null) add(q, m, f.xyz(), f.uv(), s);
            }
        }
        return new Built(q, particle);
    }

    /** Поворот блока вокруг центра по курсу (как у Minecraft: 0 — «перед» модели смотрит на север, к игроку, глядящему на юг). */
    private static Matrix4f rotation(int rot) {
        return new Matrix4f().translate(0.5f, 0, 0.5f).rotateY((float) Math.toRadians(-rot)).translate(-0.5f, 0, -0.5f);
    }

    private static final float EPS = 1e-4f;

    private static void add(List<BakedQuad>[] q, Matrix4f m, float[] xyz, float[] uv, BlockSlots.Slot s) {
        Vector3f[] p = new Vector3f[4];
        for (int i = 0; i < 4; i++) p[i] = m.transformPosition(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2], new Vector3f());
        Vector3f n = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[3]).sub(p[0]));
        if (n.lengthSquared() < 1e-12f) n = new Vector3f(p[2]).sub(p[1]).cross(new Vector3f(p[0]).sub(p[1]));
        if (n.lengthSquared() < 1e-12f) return;                                   // вырожденная грань
        n.normalize();
        Direction dir = Direction.getApproximateNearest(n.x, n.y, n.z);
        long[] puv = new long[4];
        for (int i = 0; i < 4; i++) puv[i] = UVPair.pack(s.u(uv[i * 2]), s.v(uv[i * 2 + 1]));
        BakedQuad quad = new BakedQuad(p[0], p[1], p[2], p[3], puv[0], puv[1], puv[2], puv[3], -1, dir, s.sprite(), true, 0);
        q[onBorder(p, dir) ? dir.ordinal() : 6].add(quad);
    }

    /** Грань лежит на стороне блока (её можно не рисовать, когда сосед закрывает). */
    private static boolean onBorder(Vector3f[] p, Direction d) {
        for (Vector3f v : p) {
            float c = switch (d.getAxis()) { case X -> v.x; case Y -> v.y; case Z -> v.z; };
            float want = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            if (Math.abs(c - want) > EPS) return false;
        }
        return true;
    }

    // ───────────────────────── модель для игры ─────────────────────────

    record Part(List<BakedQuad>[] quads, TextureAtlasSprite particle, boolean ao) implements BlockModelPart {
        @Override public List<BakedQuad> getQuads(Direction dir) { return quads[dir == null ? 6 : dir.ordinal()]; }
        @Override public boolean useAmbientOcclusion() { return ao; }
        @Override public TextureAtlasSprite particleIcon() { return particle; }
    }

    record Model(Part part) implements BlockStateModel {
        @Override public void collectParts(RandomSource random, List<BlockModelPart> out) { out.add(part); }
        @Override public TextureAtlasSprite particleIcon() { return part.particle(); }
    }
}
