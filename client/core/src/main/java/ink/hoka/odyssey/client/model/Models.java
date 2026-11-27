package ink.hoka.odyssey.client.model;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import ink.hoka.odyssey.client.api.ModelHandle;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Движок моделей ядра: .bbmodel из файлов сервера (разбор, текстуры сразу в видеокарту), экземпляры в мире
 * (от сервера — на сущности-носители; от модулей — {@link ModelHandle}), анимации, рисование каждый кадр после
 * сущностей. Модель на сервере поменяли → файл пришёл → модель пересобрана, у всех экземпляров сразу новая.
 */
public final class Models {

    private Models() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey/models");

    /** Где берём байты модели (ядро подставляет FileStore) и как подписаться на её изменения. */
    public interface Source {
        byte[] get(String path);
        void listen(String path, java.util.function.Consumer<byte[]> fn);
    }

    static Source source;

    public static void init(Source s) { source = s; }

    // ───────────────────────── библиотека ─────────────────────────

    private static final class Lib {
        BbModel model;
        Identifier[] tex = new Identifier[0];
        float radius;
        final float[] center = new float[3];   // середина габарита (пиксели) — для моделей в руке
    }

    private static final Map<String, Lib> LIBS = new HashMap<>();
    private static final Set<String> LISTENING = new HashSet<>();

    private static Lib lib(String path) {
        Lib l = LIBS.get(path);
        if (l != null) return l.model == null ? null : l;
        l = new Lib();
        LIBS.put(path, l);
        if (LISTENING.add(path)) source.listen(path, bytes -> reload(path, bytes));   // и сразу, если файл уже есть
        return l.model == null ? null : l;
    }

    private static void reload(String path, byte[] bytes) {
        Lib l = LIBS.computeIfAbsent(path, k -> new Lib());
        release(l);
        l.model = null;
        if (bytes == null) return;
        try {
            BbModel m = BbModel.parse(path, JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
            var tm = Minecraft.getInstance().getTextureManager();
            Identifier[] ids = new Identifier[m.texturePngs.size()];
            String base = "bbm/" + sanitize(path) + "/" + Integer.toHexString(System.identityHashCode(m)) + "/";
            for (int i = 0; i < ids.length; i++) {
                byte[] png = m.texturePngs.get(i);
                if (png.length == 0) continue;
                try {
                    Identifier id = Identifier.fromNamespaceAndPath("hoka_odyssey", base + i);
                    tm.register(id, new DynamicTexture(id::toString, NativeImage.read(png)));
                    ids[i] = id;
                } catch (Exception e) {
                    LOG.warn("модель {}: текстура {} не прочитана", path, i);
                }
            }
            m.texturePngs.clear();
            float r = 0;
            float[] lo = {1e9f, 1e9f, 1e9f}, hi = {-1e9f, -1e9f, -1e9f};
            Matrix4f[] rest = new Matrix4f[m.bones.size()];
            Pose.compute(m, null, -1, rest);
            Vector3f tp = new Vector3f();
            for (BbModel.Bone b : m.bones) for (BbModel.Face f : b.faces) for (int i = 0; i < 4; i++) {
                rest[b.index].transformPosition(f.xyz()[i * 3], f.xyz()[i * 3 + 1], f.xyz()[i * 3 + 2], tp);
                lo[0] = Math.min(lo[0], tp.x); lo[1] = Math.min(lo[1], tp.y); lo[2] = Math.min(lo[2], tp.z);
                hi[0] = Math.max(hi[0], tp.x); hi[1] = Math.max(hi[1], tp.y); hi[2] = Math.max(hi[2], tp.z);
            }
            for (int k = 0; k < 3; k++) l.center[k] = lo[k] < hi[k] ? (lo[k] + hi[k]) / 2 : 0;
            for (BbModel.Bone b : m.bones) for (BbModel.Face f : b.faces)
                for (int i = 0; i < 4; i++) r = Math.max(r, Math.max(Math.abs(f.xyz()[i * 3]), Math.max(Math.abs(f.xyz()[i * 3 + 1]), Math.abs(f.xyz()[i * 3 + 2]))));
            l.model = m;
            l.tex = ids;
            l.radius = r * 1.8f / 16f + 1f;
            for (Inst in : all()) if (in.path.equals(path)) in.pose = null;      // кости могли поменяться
            LOG.info("модель {}: костей {}, анимаций {}", path, m.bones.size(), m.animations.size());
        } catch (Exception e) {
            LOG.error("модель {} не разобрана: {}", path, e.toString());
        }
    }

    private static void release(Lib l) {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Identifier id : l.tex) if (id != null) tm.release(id);
        l.tex = new Identifier[0];
    }

    // ───────────────────────── экземпляры ─────────────────────────

    static final class Inst implements ModelHandle {
        final String owner;
        final String path;
        double x, y, z;
        float yaw, pitch, roll, scale = 1;
        int entity = -1;
        String anim;
        float speed = 1;
        long start;
        Matrix4f[] pose;
        final Set<String> hidden = new HashSet<>();
        boolean removed;

        Inst(String owner, String path) { this.owner = owner; this.path = path; }

        @Override public ModelHandle at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        @Override public ModelHandle rotate(float yaw, float pitch, float roll) { this.yaw = yaw; this.pitch = pitch; this.roll = roll; return this; }
        @Override public ModelHandle scale(float s) { scale = s; return this; }
        @Override public ModelHandle follow(int entityId) { entity = entityId; return this; }
        @Override public ModelHandle play(String a, float sp) { anim = a; speed = sp; start = System.currentTimeMillis(); return this; }
        @Override public ModelHandle stop() { anim = null; return this; }
        @Override public ModelHandle bone(String name, boolean visible) { if (visible) hidden.remove(name); else hidden.add(name); return this; }
        @Override public void remove() { removed = true; LOCAL.remove(this); }
    }

    private static final Map<Integer, Inst> SERVER = new HashMap<>();             // носитель (id сущности) → модель
    private static final List<Inst> LOCAL = new ArrayList<>();

    private static List<Inst> all() {
        List<Inst> l = new ArrayList<>(SERVER.values());
        l.addAll(LOCAL);
        return l;
    }

    public static ModelHandle create(String owner, String path) {
        Inst i = new Inst(owner, path);
        LOCAL.add(i);
        lib(path);
        return i;
    }

    public static void removeOwner(String owner) {
        LOCAL.removeIf(i -> i.owner.equals(owner));
    }

    /** Выход с сервера: всё убрать, текстуры освободить. */
    public static void clear() {
        SERVER.clear();
        LOCAL.clear();
        for (Lib l : LIBS.values()) release(l);
        LIBS.clear();
        LISTENING.clear();
    }

    /**
     * Команды сервера (S2C op 6): 1 ATTACH int сущность, UTF путь, float масштаб · 2 ANIM int сущность, UTF анимация
     * ("" — стоп), float скорость · 3 DETACH int сущность · 4 CLEAR.
     */
    public static void handle(DataInputStream in) throws IOException {
        int sub = in.readUnsignedByte();
        switch (sub) {
            case 1 -> {
                int id = in.readInt();
                String path = in.readUTF();
                float scale = in.readFloat();
                Inst i = new Inst("server", path);
                i.entity = id;
                i.scale = scale;
                Inst old = SERVER.put(id, i);
                if (old != null && old.path.equals(path)) { i.anim = old.anim; i.speed = old.speed; i.start = old.start; }
                lib(path);
            }
            case 2 -> {
                int id = in.readInt();
                String a = in.readUTF();
                float sp = in.readFloat();
                Inst i = SERVER.get(id);
                if (i != null) { if (a.isEmpty()) i.stop(); else i.play(a, sp); }
            }
            case 3 -> SERVER.remove(in.readInt());
            case 4 -> SERVER.clear();
            default -> LOG.warn("неизвестная команда моделей {}", sub);
        }
    }

    // ───────────────────────── рисование ─────────────────────────

    private static Vec3 cam = Vec3.ZERO;
    private static Frustum frustum;

    public static Vec3 camera() { return cam; }

    public static Frustum frustum() { return frustum; }

    public static void extract(WorldExtractionContext ctx) {
        cam = ctx.camera().position();
        frustum = ctx.frustum();
    }

    public static void render(WorldRenderContext ctx) {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null || (SERVER.isEmpty() && LOCAL.isEmpty())) return;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long now = System.currentTimeMillis();
        MultiBufferSource src = ctx.consumers();
        // по текстуре — одна пачка (смена типа рисования завершает прошлую)
        Map<Identifier, List<Runnable>> byTex = new HashMap<>();
        for (Inst in : all()) {
            if (in.removed) continue;
            Lib l = lib(in.path);
            if (l == null) continue;
            double px = in.x, py = in.y, pz = in.z;
            float yaw = in.yaw;
            if (in.entity >= 0) {
                Entity e = level.getEntity(in.entity);
                if (e == null) continue;
                Vec3 p = e.getPosition(pt);
                px += p.x;
                py += p.y;
                pz += p.z;
                yaw += Mth.rotLerp(pt, e.yRotO, e.getYRot());
            }
            float r = l.radius * Math.max(in.scale, 0.01f);
            if (frustum != null && !frustum.isVisible(new AABB(px - r, py - r, pz - r, px + r, py + r, pz + r))) continue;
            BbModel m = l.model;
            if (in.pose == null || in.pose.length != m.bones.size()) in.pose = new Matrix4f[m.bones.size()];
            BbAnimation a = in.anim == null ? null : m.animations.get(in.anim);
            float t = a == null ? -1 : a.localTime((now - in.start) / 1000f * in.speed);
            Pose.compute(m, a, t, in.pose);
            Matrix4f base = new Matrix4f(ctx.matrices().last().pose()).translate((float) (px - cam.x), (float) (py - cam.y), (float) (pz - cam.z))
                    .rotateY((float) Math.toRadians(180 - yaw))
                    .rotateX((float) Math.toRadians(-in.pitch))
                    .rotateZ((float) Math.toRadians(in.roll))
                    .scale(in.scale / 16f);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(px, py + 0.5, pz));
            Set<Integer> hiddenBones = hiddenIndexes(m, in.hidden);
            for (BbModel.Bone b : m.bones) {
                if (b.faces.isEmpty() || hiddenBones.contains(b.index) || "hitbox".equals(b.name)) continue;
                Matrix4f mat = new Matrix4f(base).mul(in.pose[b.index]);
                for (BbModel.Face f : b.faces) {
                    Identifier tex = f.texture() < l.tex.length ? l.tex[f.texture()] : null;
                    if (tex == null) continue;
                    byTex.computeIfAbsent(tex, k -> new ArrayList<>()).add(() -> quad(currentVc, mat, f, light));
                }
            }
        }
        for (var e : byTex.entrySet()) {
            RenderType type = RenderTypes.entityCutoutNoCull(e.getKey());
            currentVc = src.getBuffer(type);
            for (Runnable r : e.getValue()) r.run();
            if (src instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
        }
        currentVc = null;
    }

    private static VertexConsumer currentVc;

    /** Названия анимаций модели (пусто — модели ещё нет). */
    public static java.util.List<String> animations(String path) {
        Lib l = lib(path);
        return l == null ? java.util.List.of() : new ArrayList<>(new java.util.TreeSet<>(l.model.animations.keySet()));
    }

    /**
     * Нарисовать модель в текущей точке PoseStack (предмет в руке и т.п.): середина модели — в точке, 1 пиксель
     * модели = 1 единица (масштаб — у вызывающего). Анимация идёт по общим часам. false — модели ещё нет.
     */
    public static boolean submit(String path, String anim, float speed, com.mojang.blaze3d.vertex.PoseStack ps,
                                 net.minecraft.client.renderer.SubmitNodeCollector snc, int light) {
        Lib l = lib(path);
        if (l == null) return false;
        BbModel m = l.model;
        Matrix4f[] pose = new Matrix4f[m.bones.size()];
        BbAnimation a = anim == null ? null : m.animations.get(anim);
        float t = a == null ? -1 : a.localTime((System.currentTimeMillis() % 3_600_000L) / 1000f * speed);
        Pose.compute(m, a, t, pose);
        ps.pushPose();
        ps.translate(-l.center[0], -l.center[1], -l.center[2]);
        for (int ti = 0; ti < l.tex.length; ti++) {
            Identifier tex = l.tex[ti];
            if (tex == null) continue;
            final int tIndex = ti;
            snc.submitCustomGeometry(ps, RenderTypes.entityCutoutNoCull(tex), (p, vc) -> {
                for (BbModel.Bone b : m.bones) {
                    if (b.faces.isEmpty() || "hitbox".equals(b.name)) continue;
                    Matrix4f mat = new Matrix4f(p.pose()).mul(pose[b.index]);
                    for (BbModel.Face f : b.faces) if (f.texture() == tIndex) quad(vc, mat, f, light);
                }
            });
        }
        ps.popPose();
        return true;
    }

    private static Set<Integer> hiddenIndexes(BbModel m, Set<String> names) {
        if (names.isEmpty()) return Set.of();
        Set<Integer> out = new HashSet<>();
        for (String n : names) {
            BbModel.Bone b = m.bone(n);
            if (b != null) mark(b, out);
        }
        return out;
    }

    private static void mark(BbModel.Bone b, Set<Integer> out) {
        out.add(b.index);
        for (BbModel.Bone c : b.children) mark(c, out);
    }

    private static final Vector3f P0 = new Vector3f(), P1 = new Vector3f(), P2 = new Vector3f(), P3 = new Vector3f(), N = new Vector3f();

    private static void quad(VertexConsumer vc, Matrix4f m, BbModel.Face f, int light) {
        float[] p = f.xyz(), uv = f.uv();
        m.transformPosition(p[0], p[1], p[2], P0);
        m.transformPosition(p[3], p[4], p[5], P1);
        m.transformPosition(p[6], p[7], p[8], P2);
        m.transformPosition(p[9], p[10], p[11], P3);
        N.set(P1).sub(P0).cross(new Vector3f(P3).sub(P0)).normalize();
        if (!Float.isFinite(N.x)) N.set(0, 1, 0);
        Vector3f[] pts = {P0, P1, P2, P3};
        for (int i = 0; i < 4; i++) {
            vc.addVertex(pts[i].x, pts[i].y, pts[i].z).setColor(-1).setUv(uv[i * 2], uv[i * 2 + 1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(N.x, N.y, N.z);
        }
    }

    private static String sanitize(String p) {
        StringBuilder sb = new StringBuilder(p.length());
        for (char c : p.toLowerCase().toCharArray()) sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        return sb.toString();
    }
}
