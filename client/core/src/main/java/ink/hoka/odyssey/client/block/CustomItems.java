package ink.hoka.odyssey.client.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.item.BlockModelWrapper;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Свои предметы. Сервер метит предмет в custom_data: {@code hoka_item} — свой предмет из {@value #DEFS},
 * {@code hoka_block} — предмет своего блока. Мод рисует его сам — в руке, в инвентаре, на земле, в рамке:
 * плоская иконка из PNG (с объёмом, как ванильные предметы) или модель .bbmodel; у блока — сам блок. Без мода
 * игрок видит обычный ванильный предмет. Текстуры — в тех же ячейках атласа, что у блоков (без перезагрузок).
 */
public final class CustomItems {

    private CustomItems() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey/items");
    public static final String DEFS = "items/items.json";

    /** Как выглядит в одном виде: квады (координаты блока 0..1), положения в руке/инвентаре, свет, слой. */
    record Visual(List<BakedQuad> quads, ItemTransforms transforms, boolean blockLight, TextureAtlasSprite particle,
                  RenderType type, Vector3fc[] extents, Object identity) {}

    /** Предмет: вид в инвентаре и вид в мире (руки, земля, рамка). */
    public record Item(Visual gui, Visual world) {}

    private static volatile Map<String, Item> ITEMS = Map.of(), BLOCK_ITEMS = Map.of();

    static void set(Map<String, Item> items, Map<String, Item> blockItems) {
        ITEMS = Map.copyOf(items);
        BLOCK_ITEMS = Map.copyOf(blockItems);
        if (!items.isEmpty() || !blockItems.isEmpty()) LOG.info("свои предметы: {}, предметы блоков: {}", items.size(), blockItems.size());
    }

    static boolean any() { return !ITEMS.isEmpty() || !BLOCK_ITEMS.isEmpty(); }

    static Item find(ItemStack stack) {
        if (ITEMS.isEmpty() && BLOCK_ITEMS.isEmpty()) return null;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return null;
        String[] m = MARKS.get(cd);
        if (m == null) {
            var tag = cd.copyTag();
            m = new String[]{tag.getStringOr("hoka_item", ""), tag.getStringOr("hoka_block", "")};
            MARKS.put(cd, m);
        }
        if (!m[0].isEmpty()) {
            Item it = ITEMS.get(m[0]);
            if (it != null) return it;
        }
        return m[1].isEmpty() ? null : BLOCK_ITEMS.get(m[1]);
    }

    // метки предмета: разбор custom_data раз на объект (рисуется каждый кадр)
    private static final Map<CustomData, String[]> MARKS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** id своего блока у предмета (метка hoka_block) или null. */
    public static String blockId(ItemStack stack) {
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return null;
        String v = cd.copyTag().getStringOr("hoka_block", "");
        return v.isEmpty() ? null : v;
    }

    /** Вместо ванильной модели предмета (миксин ItemModelResolver). false — предмет не наш. */
    public static boolean update(ItemStackRenderState state, ItemStack stack, ItemDisplayContext ctx) {
        Item it = find(stack);
        if (it == null) return false;
        Visual v = ctx == ItemDisplayContext.GUI ? it.gui : it.world;
        state.appendModelIdentityElement(v.identity);
        ItemStackRenderState.LayerRenderState layer = state.newLayer();
        if (stack.hasFoil()) layer.setFoilType(ItemStackRenderState.FoilType.STANDARD);
        layer.setRenderType(v.type);
        layer.setUsesBlockLight(v.blockLight);
        Vector3fc[] ext = v.extents;
        layer.setExtents(() -> ext);
        layer.setParticleIcon(v.particle);
        layer.setTransform(v.transforms.getTransform(ctx));
        layer.prepareQuadList().addAll(v.quads);
        return true;
    }

    // ───────────────────────── сборка ─────────────────────────

    /** Предмет своего блока — сам блок (поворот 0), как ванильный блок в руке. */
    static Item blockItem(String id, List<BakedQuad>[] lists, TextureAtlasSprite particle) {
        List<BakedQuad> all = new ArrayList<>();
        for (List<BakedQuad> l : lists) all.addAll(l);
        Visual v = visual(all, BLOCK, true, particle, Sheets.cutoutBlockSheet());
        return new Item(v, v);
    }

    static Map<String, Item> build(JsonObject root) {
        Map<String, Item> out = new HashMap<>();
        if (!root.has("items")) return out;
        for (JsonElement el : root.getAsJsonArray("items")) {
            JsonObject d = el.getAsJsonObject();
            String id = d.get("id").getAsString();
            try {
                String display = d.has("display") ? d.get("display").getAsString() : null;
                float scale = d.has("scale") ? d.get("scale").getAsFloat() : 1f;
                RenderType type = "translucent".equals(d.has("layer") ? d.get("layer").getAsString() : "")
                        ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockSheet();
                Visual icon = null, model = null;
                if (d.has("icon")) {
                    String path = d.get("icon").getAsString();
                    BlockSlots.Slot s = CustomBlocks.texture(path);
                    if (s != null) icon = visual(flat(s, CustomBlocks.file(path)),
                            preset(display == null ? "item" : display, scale), false, s.sprite(), type);
                }
                if (d.has("model")) {
                    String path = d.get("model").getAsString();
                    CustomBlocks.Built b = CustomBlocks.fromModel(path, 0, BlockForm.CUBE, null, false);
                    if (b != null) {
                        List<BakedQuad> all = new ArrayList<>();
                        for (List<BakedQuad> l : b.quads()) all.addAll(l);
                        // положения: из конфига (display), иначе — из самой модели (вкладка Display в Blockbench), иначе как блок
                        ItemTransforms tr = display != null ? preset(display, scale) : fromBbDisplay(CustomBlocks.file(path), scale);
                        model = visual(all, tr, true, b.particle(), type);
                    }
                }
                if (icon == null && model == null) { LOG.warn("{}: ни иконки, ни модели", id); continue; }
                out.put(id, new Item(icon != null ? icon : model, model != null ? model : icon));
            } catch (Exception e) {
                LOG.warn("{}: не собран: {}", id, e.toString());
            }
        }
        return out;
    }

    private static Visual visual(List<BakedQuad> q, ItemTransforms t, boolean blockLight, TextureAtlasSprite particle, RenderType type) {
        List<BakedQuad> quads = List.copyOf(q);
        return new Visual(quads, t, blockLight, particle, type, BlockModelWrapper.computeExtents(quads), new Object());
    }

    // ───────────────────────── плоская иконка с объёмом ─────────────────────────

    private static final float Z0 = 7.5f / 16f, Z1 = 8.5f / 16f;

    /** Как ванильный item/generated: лицо и изнанка + торцы по краю непрозрачных пикселей. */
    private static List<BakedQuad> flat(BlockSlots.Slot s, byte[] png) {
        List<BakedQuad> out = new ArrayList<>();
        out.add(box(Direction.SOUTH, 0, 0, Z0, 1, 1, Z1, s, 0, 0, 1, 1));
        out.add(box(Direction.NORTH, 0, 0, Z0, 1, 1, Z1, s, 1, 0, 0, 1));
        NativeImage img;
        try {
            img = NativeImage.read(png);
        } catch (Exception e) {
            return out;
        }
        try {
            int w = img.getWidth(), h = img.getHeight();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (!solid(img, x, y)) continue;
                float x0 = x / (float) w, x1 = (x + 1) / (float) w, y0 = 1 - (y + 1) / (float) h, y1 = 1 - y / (float) h;
                float u0 = x / (float) w, u1 = (x + 1) / (float) w, v0 = y / (float) h, v1 = (y + 1) / (float) h;
                if (!solid(img, x - 1, y)) out.add(box(Direction.WEST, x0, y0, Z0, x0, y1, Z1, s, u0, v0, u1, v1));
                if (!solid(img, x + 1, y)) out.add(box(Direction.EAST, x1, y0, Z0, x1, y1, Z1, s, u0, v0, u1, v1));
                if (!solid(img, x, y - 1)) out.add(box(Direction.UP, x0, y1, Z0, x1, y1, Z1, s, u0, v0, u1, v1));
                if (!solid(img, x, y + 1)) out.add(box(Direction.DOWN, x0, y0, Z0, x1, y0, Z1, s, u0, v0, u1, v1));
            }
        } finally {
            img.close();
        }
        return out;
    }

    private static boolean solid(NativeImage img, int x, int y) {
        return x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && (img.getPixel(x, y) >>> 24) != 0;
    }

    /** Грань коробки (углы — в ванильном порядке), текстура — участок u0..u1 × v0..v1 картинки. */
    private static BakedQuad box(Direction d, float x0, float y0, float z0, float x1, float y1, float z1,
                                 BlockSlots.Slot s, float u0, float v0, float u1, float v1) {
        float[][] c = CustomBlocks.corners(d);
        Vector3f[] p = new Vector3f[4];
        for (int i = 0; i < 4; i++) p[i] = new Vector3f(c[i][0] == 0 ? x0 : x1, c[i][1] == 0 ? y0 : y1, c[i][2] == 0 ? z0 : z1);
        float[][] uv = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
        long[] puv = new long[4];
        for (int i = 0; i < 4; i++) puv[i] = UVPair.pack(s.u(uv[i][0]), s.v(uv[i][1]));
        return new BakedQuad(p[0], p[1], p[2], p[3], puv[0], puv[1], puv[2], puv[3], -1, d, s.sprite(), true, 0);
    }

    // ───────────────────────── положения (как у ванильных моделей) ─────────────────────────

    private static ItemTransform t(float rx, float ry, float rz, float tx, float ty, float tz, float s) {
        return new ItemTransform(new Vector3f(rx, ry, rz), new Vector3f(tx / 16f, ty / 16f, tz / 16f), new Vector3f(s, s, s));
    }

    // порядок: 3-е лицо левая/правая, 1-е лицо левая/правая, голова, инвентарь, земля, рамка, рамка снизу
    static final ItemTransforms BLOCK = new ItemTransforms(
            t(75, 45, 0, 0, 2.5f, 0, 0.375f), t(75, 45, 0, 0, 2.5f, 0, 0.375f),
            t(0, 225, 0, 0, 0, 0, 0.4f), t(0, 45, 0, 0, 0, 0, 0.4f),
            ItemTransform.NO_TRANSFORM, t(30, 225, 0, 0, 0, 0, 0.625f), t(0, 0, 0, 0, 3, 0, 0.25f),
            t(0, 0, 0, 0, 0, 0, 0.5f), t(0, 0, 0, 0, 0, 0, 0.5f));
    static final ItemTransforms GENERATED = new ItemTransforms(
            t(0, 0, 0, 0, 3, 1, 0.55f), t(0, 0, 0, 0, 3, 1, 0.55f),
            t(0, -90, 25, 1.13f, 3.2f, 1.13f, 0.68f), t(0, -90, 25, 1.13f, 3.2f, 1.13f, 0.68f),
            t(0, 180, 0, 0, 13, 7, 1), ItemTransform.NO_TRANSFORM, t(0, 0, 0, 0, 2, 0, 0.5f),
            t(0, 180, 0, 0, 0, 0, 1), t(0, 180, 0, 0, 0, 0, 1));
    static final ItemTransforms HANDHELD = new ItemTransforms(
            t(0, 90, -55, 0, 4, 0.5f, 0.85f), t(0, -90, 55, 0, 4, 0.5f, 0.85f),
            t(0, 90, -25, 1.13f, 3.2f, 1.13f, 0.68f), t(0, -90, 25, 1.13f, 3.2f, 1.13f, 0.68f),
            t(0, 180, 0, 0, 13, 7, 1), ItemTransform.NO_TRANSFORM, t(0, 0, 0, 0, 2, 0, 0.5f),
            t(0, 180, 0, 0, 0, 0, 1), t(0, 180, 0, 0, 0, 0, 1));

    private static ItemTransforms preset(String name, float scale) {
        ItemTransforms base = switch (name) {
            case "block" -> BLOCK;
            case "handheld" -> HANDHELD;
            default -> GENERATED;
        };
        if (scale == 1f) return base;
        return new ItemTransforms(sc(base.thirdPersonLeftHand(), scale), sc(base.thirdPersonRightHand(), scale),
                sc(base.firstPersonLeftHand(), scale), sc(base.firstPersonRightHand(), scale), sc(base.head(), scale),
                sc(base.gui(), scale), sc(base.ground(), scale), sc(base.fixed(), scale), sc(base.fixedFromBottom(), scale));
    }

    /** Положения из .bbmodel (раздел display — как в ванильной модели); нет его — как у блока. */
    private static ItemTransforms fromBbDisplay(byte[] bbmodel, float scale) {
        JsonObject disp = null;
        try {
            JsonObject j = com.google.gson.JsonParser.parseString(new String(bbmodel, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (j.has("display") && j.get("display").isJsonObject()) disp = j.getAsJsonObject("display");
        } catch (Exception ignored) { }
        if (disp == null || disp.isEmpty()) return preset("block", scale);
        ItemTransform tpr = disp(disp, "thirdperson_righthand", scale), fpr = disp(disp, "firstperson_righthand", scale);
        ItemTransform tpl = disp.has("thirdperson_lefthand") ? disp(disp, "thirdperson_lefthand", scale) : tpr;
        ItemTransform fpl = disp.has("firstperson_lefthand") ? disp(disp, "firstperson_lefthand", scale) : fpr;
        ItemTransform fixed = disp(disp, "fixed", scale);
        return new ItemTransforms(tpl, tpr, fpl, fpr, disp(disp, "head", scale), disp(disp, "gui", scale),
                disp(disp, "ground", scale), fixed, disp.has("on_shelf") ? disp(disp, "on_shelf", scale) : fixed);
    }

    private static ItemTransform disp(JsonObject disp, String key, float scale) {
        if (!disp.has(key) || !disp.get(key).isJsonObject()) return scale == 1f ? ItemTransform.NO_TRANSFORM : sc(ItemTransform.NO_TRANSFORM, scale);
        JsonObject o = disp.getAsJsonObject(key);
        Vector3f r = vec(o, "rotation", 0), t = vec(o, "translation", 0), s = vec(o, "scale", 1);
        t.set(Math.clamp(t.x, -80, 80) / 16f, Math.clamp(t.y, -80, 80) / 16f, Math.clamp(t.z, -80, 80) / 16f);
        s.set(Math.clamp(s.x, -4, 4) * scale, Math.clamp(s.y, -4, 4) * scale, Math.clamp(s.z, -4, 4) * scale);
        return new ItemTransform(r, t, s);
    }

    private static Vector3f vec(JsonObject o, String k, float def) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return new Vector3f(def);
        var a = o.getAsJsonArray(k);
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    private static ItemTransform sc(ItemTransform t, float k) {
        return new ItemTransform(t.rotation(), t.translation(), new Vector3f(t.scale()).mul(k));
    }
}
