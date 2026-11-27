package ink.hoka.odyssey.client.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 0.69 Предметы-модели в руке (Аксиом и т.п.): .bbmodel с анимацией вместо обычного предмета — от первого лица со
 * своими руками, от третьего — в руке и со своей позой рук. Настройки — файл сервера {@value #FILE} (по id метки
 * hoka_item), правятся админом в окне {@link HeldEditor} и сразу видны всем.
 */
public final class HeldModels {

    private HeldModels() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey/held");
    public static final String FILE = "items/held.json";

    /** Настройки одного предмета. Массивы: сдвиг x,y,z (пиксели), поворот x,y,z (градусы), [масштаб]. */
    public static final class Def {
        public String model = "", anim = "", arms = "both";
        public float speed = 1, range = 0;
        public float[] fpModel = {0, 0, 0, 0, 0, 0, 0.2f}, fpArm1 = {0, 0, 0, 0, 0, 0, 1, 1, 1}, fpArm2 = {0, 0, 0, 0, 0, 0, 1, 1, 1};
        public float[] tpModel = {0, 0, 0, 0, 0, 0, 0.12f}, tpArm1 = {-80, -10, 0, 1, 1, 1}, tpArm2 = {-80, 30, 0, 1, 1, 1};
        public boolean tpPose = true;

        boolean both() { return "both".equals(arms); }
    }

    private static volatile Map<String, Def> DEFS = Map.of();
    private static boolean listening;

    public static void init() {
        if (listening) return;
        listening = true;
        if (Models.source != null) Models.source.listen(FILE, HeldModels::load);
    }

    private static void load(byte[] bytes) {
        Map<String, Def> out = new HashMap<>();
        if (bytes != null) try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : root.entrySet()) out.put(e.getKey(), fromJson(e.getValue().getAsJsonObject()));
        } catch (Exception e) {
            LOG.warn("{} не разобран: {}", FILE, e.toString());
        }
        DEFS = out;
        if (HeldEditor.editing != null) DEFS.put(HeldEditor.editingId, HeldEditor.editing);   // правку не терять
    }

    public static Def get(String id) { return DEFS.get(id); }

    /** Подменить (окно настройки: видно сразу, до сохранения). */
    static void put(String id, Def d) {
        Map<String, Def> m = new HashMap<>(DEFS);
        m.put(id, d);
        DEFS = m;
    }

    public static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return null;
        String v = cd.copyTag().getStringOr("hoka_item", "");
        return v.isEmpty() ? null : v;
    }

    public static Def find(ItemStack stack) {
        if (DEFS.isEmpty()) return null;
        String id = idOf(stack);
        return id == null ? null : DEFS.get(id);
    }

    // ───────────────────────── рисование ─────────────────────────

    /** Модель по настройке: сдвиг (пиксели/16), поворот XYZ, масштаб (1/16 — пиксели модели). */
    private static void apply(PoseStack ps, float[] t, float mirror) {
        ps.translate(mirror * t[0] / 16f, t[1] / 16f, t[2] / 16f);
        if (t[5] != 0) ps.mulPose(Axis.ZP.rotationDegrees(mirror * t[5]));
        if (t[4] != 0) ps.mulPose(Axis.YP.rotationDegrees(mirror * t[4]));
        if (t[3] != 0) ps.mulPose(Axis.XP.rotationDegrees(t[3]));
    }

    private static void model(Def d, float[] t, float mirror, PoseStack ps, SubmitNodeCollector snc, int light) {
        ps.pushPose();
        apply(ps, t, mirror);
        float s = t[6] / 16f;
        ps.scale(s, s, s);
        Models.submit(d.model, d.anim.isEmpty() ? null : d.anim, d.speed, ps, snc, light);
        ps.popPose();
    }

    /** От первого лица (вместо ванильной руки с предметом). */
    public static void firstPerson(AbstractClientPlayer player, Def d, float equip, PoseStack ps, SubmitNodeCollector snc, int light) {
        HumanoidArm main = player.getMainArm();
        float h = main == HumanoidArm.RIGHT ? 1 : -1;
        ps.pushPose();
        ps.translate(h * 0.56f, -0.52f + equip * -0.6f, -0.72f);
        model(d, d.fpModel, h, ps, snc, light);
        ps.popPose();
        if (player.isInvisible() || "none".equals(d.arms)) return;
        arm(player, main, d.fpArm1, equip, ps, snc, light);
        if (d.both()) arm(player, main.getOpposite(), d.fpArm2, equip, ps, snc, light);
    }

    /** Рука игрока как у ванильной пустой руки + своя поправка (сдвиг/поворот у кисти). */
    private static void arm(AbstractClientPlayer player, HumanoidArm side, float[] t, float equip, PoseStack ps, SubmitNodeCollector snc, int light) {
        float h = side == HumanoidArm.RIGHT ? 1 : -1;
        ps.pushPose();
        ps.translate(h * 0.64f, -0.6f + equip * -0.6f, -0.72f);
        ps.mulPose(Axis.YP.rotationDegrees(h * 45));
        apply(ps, t, h);
        ps.translate(h * -1.0f, 3.6f, 3.5f);
        ps.mulPose(Axis.ZP.rotationDegrees(h * 120));
        ps.mulPose(Axis.XP.rotationDegrees(200));
        ps.mulPose(Axis.YP.rotationDegrees(h * -135));
        ps.translate(h * 5.6f, 0, 0);
        // растяжение руки (x, y — вдоль руки, z) — от плеча: пивот руки игрока (∓5, 2, 0) пикселей
        if (t[6] != 1 || t[7] != 1 || t[8] != 1) {
            float px = -h * 5 / 16f, py = 2 / 16f;
            ps.translate(px, py, 0);
            ps.scale(t[6], t[7], t[8]);
            ps.translate(-px, -py, 0);
        }
        AvatarRenderer<AbstractClientPlayer> r = Minecraft.getInstance().getEntityRenderDispatcher().getPlayerRenderer(player);
        var skin = player.getSkin().body().texturePath();
        if (h > 0) r.renderRightHand(ps, snc, light, skin, player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
        else r.renderLeftHand(ps, snc, light, skin, player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
        ps.popPose();
    }

    /** От третьего лица: PoseStack уже у кисти (как у ванильного предмета в руке). */
    public static void thirdPerson(Def d, boolean left, PoseStack ps, SubmitNodeCollector snc, int light) {
        // рука могла быть растянута (масштаб части руки): кисть — на конце руки, а модель — без искажения
        float[] a = d.tpArm1;
        boolean st = d.tpPose;
        float sx = st ? Math.max(0.05f, a[3]) : 1, sy = st ? Math.max(0.05f, a[4]) : 1, sz = st ? Math.max(0.05f, a[5]) : 1;
        float ox = (left ? -1 : 1) / 16f;
        ps.translate(-ox * sx, 0.625f * sy, -0.125f * sz);
        ps.scale(1 / sx, 1 / sy, 1 / sz);
        ps.mulPose(Axis.XP.rotationDegrees(-90));
        ps.mulPose(Axis.YP.rotationDegrees(180));
        model(d, d.tpModel, left ? -1 : 1, ps, snc, light);
    }

    /** Поза рук от третьего лица (после ванильной): руки держат предмет и следуют за взглядом. */
    public static void pose(HumanoidModel<?> m, HumanoidRenderState s) {
        Def d = find(s.getMainHandItemStack());
        if (d == null || !d.tpPose) return;
        boolean right = s.mainArm == HumanoidArm.RIGHT;
        float mir = right ? 1 : -1;
        set(right ? m.rightArm : m.leftArm, d.tpArm1, mir, m.head.xRot, m.head.yRot);
        if (d.both()) set(right ? m.leftArm : m.rightArm, d.tpArm2, mir, m.head.xRot, m.head.yRot);
    }

    private static void set(net.minecraft.client.model.geom.ModelPart arm, float[] r, float mir, float headX, float headY) {
        arm.xRot = (float) Math.toRadians(r[0]) + headX;
        arm.yRot = (float) Math.toRadians(mir * r[1]) + headY;
        arm.zRot = (float) Math.toRadians(mir * r[2]);
        arm.xScale = Math.max(0.05f, r[3]);
        arm.yScale = Math.max(0.05f, r[4]);
        arm.zScale = Math.max(0.05f, r[5]);
    }

    // ───────────────────────── json ─────────────────────────

    static Def fromJson(JsonObject o) {
        Def d = new Def();
        d.model = str(o, "model", "");
        d.anim = str(o, "anim", "");
        d.arms = str(o, "arms", "both");
        d.speed = o.has("speed") ? o.get("speed").getAsFloat() : 1;
        d.range = o.has("range") ? o.get("range").getAsFloat() : 0;
        d.tpPose = !o.has("tpPose") || o.get("tpPose").getAsBoolean();
        arr(o, "fpModel", d.fpModel);
        arr(o, "fpArm1", d.fpArm1);
        arr(o, "fpArm2", d.fpArm2);
        arr(o, "tpModel", d.tpModel);
        arr(o, "tpArm1", d.tpArm1);
        arr(o, "tpArm2", d.tpArm2);
        return d;
    }

    static JsonObject toJson(Def d) {
        JsonObject o = new JsonObject();
        o.addProperty("model", d.model);
        o.addProperty("anim", d.anim);
        o.addProperty("arms", d.arms);
        o.addProperty("speed", d.speed);
        o.addProperty("range", d.range);
        o.addProperty("tpPose", d.tpPose);
        o.add("fpModel", json(d.fpModel));
        o.add("fpArm1", json(d.fpArm1));
        o.add("fpArm2", json(d.fpArm2));
        o.add("tpModel", json(d.tpModel));
        o.add("tpArm1", json(d.tpArm1));
        o.add("tpArm2", json(d.tpArm2));
        return o;
    }

    static Def copy(Def d) { return fromJson(toJson(d)); }

    static String text(Def d) { return new Gson().toJson(toJson(d)); }

    private static String str(JsonObject o, String k, String def) { return o.has(k) ? o.get(k).getAsString() : def; }

    private static void arr(JsonObject o, String k, float[] out) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return;
        JsonArray a = o.getAsJsonArray(k);
        for (int i = 0; i < Math.min(a.size(), out.length); i++) out[i] = a.get(i).getAsFloat();
    }

    private static JsonArray json(float[] v) {
        JsonArray a = new JsonArray();
        for (float f : v) a.add(Math.round(f * 1000) / 1000f);
        return a;
    }
}
