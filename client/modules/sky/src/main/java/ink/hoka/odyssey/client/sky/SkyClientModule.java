package ink.hoka.odyssey.client.sky;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Своё небо карты (настройки — раздел sky: в map.yml, шлёт серверный модуль sky). Рисуется в начале основного прохода
 * мира (Fabric START_MAIN: ванильное небо уже нарисовано, мир — ещё нет) поверх ванильного неба, без глубины — мир
 * потом ложится сверху. Порядок проходов кадра (LevelRenderer.renderLevel, граф кадра): clear → sky → main
 * (START_MAIN — перед непрозрачной землёй) → частицы → облака → погода. Ванильные солнце/луна/звёзды скрыты тем, что
 * купол непрозрачный и закрывает всю сферу. Свой RenderPass открываем только когда всё в видеопамять уже записано:
 * любая запись в буфер (writeToBuffer, mapBuffer — в т.ч. writeTransform) при открытом проходе — IllegalStateException. Слои: купол-градиент (зенит/горизонт по времени суток, закат со стороны солнца), узор (полосы
 * или «кубики», медленно вращается), туманность и звёзды (ночью, вращаются с небом), кубическая луна с фазами,
 * кубическое солнце с квадратным ореолом, дымка у горизонта цветом тумана игры (чтобы дальний мир не «отрывался»).
 * Всё — свои RenderPipeline на ванильном шейдере position_color и GpuBuffer: узор/звёзды запекаются один раз
 * (в фоне), на кадр — только солнце и луна (~30 квадов) и ~8 вызовов отрисовки; купол и дымка — свой буфер, пересобирается
 * лишь при смене времени суток/погоды/цвета тумана (0.2.1).
 * 0.2: свой туман (цвет — горизонт купола по времени суток, край прорисовки тает в нём; см. раздел «туман»),
 * низ купола под горизонтом — цвет тумана (с высоты небо снизу не чернеет).
 * <pre>
 * S2C: 1 SKY  int n; n × (UTF key, UTF value)   — n = 0 / enabled=false / preset=vanilla: ванильное небо
 * </pre>
 */
public final class SkyClientModule implements ClientModule {

    private static final float R = 50f;                 // радиус неба (блоки от камеры; глубину не пишем — лишь бы < дальней плоскости)
    private static final float DEG = (float) (Math.PI / 180);

    private ClientContext ctx;
    /** Модуль жив: обработчик Fabric снять нельзя — после выгрузки он просто молчит. */
    private volatile boolean alive;
    private volatile Cfg cfg;
    private java.util.concurrent.ExecutorService gen;
    private final java.util.concurrent.atomic.AtomicInteger cfgVer = new java.util.concurrent.atomic.AtomicInteger();
    /** Запечённые в фоне слои — ждут заливки в видеопамять на потоке отрисовки. */
    private volatile Baked pending;
    private Baked baked;
    private final AtmosphericFogEnvironment fogEnv = new AtmosphericFogEnvironment();
    /** Обработчик Fabric повесили (снять нельзя — второй раз не вешаем). */
    private boolean registered;
    /** Ошибки отрисовки: не чаще лога раз в 10 с, после ошибки — пауза (растёт до 30 с), потом снова пробуем. */
    private long retryAt, lastLog, backoff;
    private int errors;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        alive = true;
        gen = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Hoka sky");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ctx.onMessage(this::onMessage);
        ctx.onTick(this::tick);
        if (!registered) { registered = true; WorldRenderEvents.START_MAIN.register(c -> { if (alive) render(); }); }
        retryAt = 0; backoff = 0; errors = 0;
    }

    @Override
    public void disable() {
        alive = false;
        cfg = null;
        cfgVer.incrementAndGet();
        clouds(true);
        if (gen != null) { gen.shutdownNow(); gen = null; }
        releaseAll();                                                       // поток клиента = поток отрисовки
    }

    private void releaseAll() {
        if (baked != null) { baked.release(); baked = null; }
        Baked p = pending;
        pending = null;
        if (p != null) p.release();
        dynUp.release();
        sphUp.release();
        sphOk = false;
    }

    // ───────────────────────── настройки ─────────────────────────

    private void onMessage(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readByte() != 1) return;
            int n = in.readInt();
            Map<String, String> kv = new HashMap<>();
            for (int i = 0; i < n; i++) kv.put(in.readUTF(), in.readUTF());
            Cfg c = n == 0 ? null : Cfg.parse(kv);
            int ver = cfgVer.incrementAndGet();
            Cfg old = cfg;
            cfg = c;
            clouds(c == null || c.clouds);
            if (c == null) return;
            if (old != null && old.sameBake(c) && (baked != null || pending != null)) return;   // узор тот же — не пересобирать
            var ex = gen;
            if (ex == null) return;
            ex.execute(() -> {
                if (cfgVer.get() != ver) return;
                Baked b = Baked.build(c);
                if (cfgVer.get() != ver) return;
                pending = b;                                                    // прежний ожидавший ещё не в видеопамяти — просто отбросить
            });
        } catch (Exception e) {
            if (ctx != null) ctx.logger().warn("небо: плохой пакет: {}", e.toString());
        }
    }

    private void tick() {
        Cfg c = cfg;
        if (c != null && !c.clouds) clouds(false);        // игрок мог включить облака в настройках — держим выключенными
    }

    // облака: выключаем значением настройки напрямую (через set игра сбросила бы пресет графики в «свой»), возвращаем как было
    private CloudStatus savedClouds;
    private static java.lang.reflect.Field cloudField;
    private static boolean cloudFieldTried;

    private void clouds(boolean show) {
        try {
            OptionInstance<CloudStatus> opt = Minecraft.getInstance().options.cloudStatus();
            java.lang.reflect.Field f = valueField();
            if (f == null) return;
            if (!show) {
                if (opt.get() != CloudStatus.OFF) { if (savedClouds == null) savedClouds = opt.get(); f.set(opt, CloudStatus.OFF); }
            } else if (savedClouds != null) {
                if (opt.get() == CloudStatus.OFF) f.set(opt, savedClouds);
                savedClouds = null;
            }
        } catch (Exception ignored) {
        }
    }

    /** Поле текущего значения OptionInstance: единственное не static, не final, типа Object (имена в игре — промежуточные). */
    private static java.lang.reflect.Field valueField() {
        if (cloudFieldTried) return cloudField;
        cloudFieldTried = true;
        java.lang.reflect.Field found = null;
        for (var f : OptionInstance.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(m) || java.lang.reflect.Modifier.isFinal(m) || f.getType() != Object.class) continue;
            if (found != null) return null;                                  // не одно — не угадываем
            found = f;
        }
        if (found != null) found.setAccessible(true);
        return cloudField = found;
    }

    // ───────────────────────── кадр ─────────────────────────

    private void render() {
        Cfg c = cfg;
        if (c == null) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lvl = mc.level;
        if (lvl == null || lvl.dimensionType().skybox() != DimensionType.Skybox.OVERWORLD) return;
        Camera cam = mc.gameRenderer.getMainCamera();
        if (cam.getFluidInCamera() != FogType.NONE) return;                // в воде/лаве — ванильное (в тумане)
        if (cam.entity() instanceof LivingEntity le && (le.hasEffect(MobEffects.BLINDNESS) || le.hasEffect(MobEffects.DARKNESS))) return;
        Baked p = pending;
        if (p != null) {
            pending = null;
            if (baked != null) baked.release();
            baked = p;
        }
        long now = System.currentTimeMillis();
        if (now < retryAt) return;                                          // после ошибки — пауза, на это время ванильное небо
        Baked b = baked;
        if (b != null && !b.uploaded) b.upload();
        try {
            draw(c, b, mc, lvl, cam);
            backoff = 0;
        } catch (Exception e) {
            errors++;
            backoff = backoff == 0 ? 1000 : Math.min(30_000, backoff * 2);
            retryAt = now + backoff;
            if (now - lastLog >= 10_000) {
                lastLog = now;
                ctx.logger().warn("небо: ошибка отрисовки (всего {}), повтор через {} мс", errors, backoff, e);
            }
        }
    }

    // текущие величины кадра
    private final Vector3f sun = new Vector3f(), moon = new Vector3f(), tmp = new Vector3f();
    private final Matrix4f celestial = new Matrix4f(), view = new Matrix4f(), mv = new Matrix4f(), rot = new Matrix4f();

    private void draw(Cfg c, Baked b, Minecraft mc, ClientLevel lvl, Camera cam) {
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = cam.attributeProbe();
        float sunA = probe.getValue(EnvironmentAttributes.SUN_ANGLE, pt) * DEG;
        float moonA = probe.getValue(EnvironmentAttributes.MOON_ANGLE, pt) * DEG;
        float starA = probe.getValue(EnvironmentAttributes.STAR_ANGLE, pt) * DEG;
        MoonPhase phase = probe.getValue(EnvironmentAttributes.MOON_PHASE, pt);
        float rain = lvl.getRainLevel(pt), thunder = lvl.getThunderLevel(pt);
        double time = (lvl.getGameTime() + pt) / 20.0;                    // секунды игры — для вращения узора

        // путь солнца как у игры (поворот Y −90°, затем X на угол), наклонённый на tilt к югу
        celestial.identity().rotateX(c.tilt * DEG).rotateY(-90 * DEG);
        celPos(celestial, sunA, sun);
        celPos(celestial, moonA, moon);

        float sunH = sun.y;
        float dayK = smooth(-0.18f, 0.22f, sunH);                           // 0 — ночь, 1 — день
        float twK = smooth(-0.34f, -0.02f, sunH) * (1 - smooth(0.06f, 0.45f, sunH));   // закат/рассвет
        float wet = Math.min(1, rain * 0.75f + thunder * 0.25f);

        // цвет тумана: свой (цвет горизонта купола) или ванильный, если fog: vanilla
        int fog;
        if (c.fogOn) {
            fog = fogColor(c, cam, dayK, twK, wet);
            applyFog(c, mc, lvl, cam, fog, pt);                                // до прохода: пишет в буферы
        } else fog = fogEnv.getBaseColor(lvl, cam, mc.options.getEffectiveRenderDistance(), pt) & 0xFFFFFF;
        lodFogColor(fog);                                                   // 0.2.2: дальняя прорисовка тает в тот же цвет

        // 0.2.1: купол и дымка — свой буфер в видеопамяти, пересобирается, только когда сменились их входы (время суток
        // с шагом ~1/1000, погода, цвет тумана, настройки): на кадр — лишь солнце/луна (~120 вершин) и униформы.
        // Было: ~3000 вершин купола считались (pow/sin/cos) и заливались каждый кадр — 3.8% главного потока.
        GpuBuffer sv = sphere(c, dayK, twK, wet, fog);
        if (sv == null) return;
        int domeQ = sphDomeQ, fogStart = sphFogStart, fogQ = sphFogQ;

        DynBuf d = dyn;
        d.n = 0;
        int moonStart = d.n;
        int moonQ = c.moonType.equals("none") ? 0 : body(d, moon, c.moonSize, c.moonColor, c.moonColor, (float) time * 0.15f, c.moonType,
                c.moonPhases ? phase.index() : 0, c.moonPhases, false);
        int moonHaloStart = d.n;
        int moonHaloQ = c.moonType.equals("none") ? 0 : halo(d, moon, c.moonSize, c.moonColor, 0.16f, (float) time * 0.05f, 2.6f);
        int sunStart = d.n;
        int sunCol = lerp(c.sunColor, c.glow, twK * 0.45f);
        int sunQ = c.sunType.equals("none") ? 0 : body(d, sun, c.sunSize, sunCol, c.sunHalo, (float) time * 0.35f * c.sunSpin, c.sunType, 0, false, true);
        int sunHaloStart = d.n;
        int sunHaloQ = 0;
        if (!c.sunType.equals("none")) {
            float spin = (float) time * 0.12f * c.sunSpin;
            sunHaloQ += halo(d, sun, c.sunSize, c.sunHalo, 0.55f, spin, 2.1f);
            sunHaloQ += halo(d, sun, c.sunSize, c.sunHalo, 0.30f, -spin + 45 * DEG, 3.6f);
            sunHaloQ += halo(d, sun, c.sunSize, lerp(c.sunHalo, c.glow, 0.5f), 0.14f + 0.12f * twK, spin * 0.3f, 9f);
        }

        // в видеопамять одним куском
        GpuBuffer vb = d.n > 0 ? dynUp.upload(d, "Hoka sky frame") : sv;
        if (vb == null) return;

        view.set(RenderSystem.getModelViewMatrix());
        var target = mc.getMainRenderTarget();
        var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : target.getColorTextureView();
        var idx = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        int maxQ = Math.max(d.n / 4, Math.max(domeQ, fogQ));
        if (b != null && b.uploaded) maxQ = Math.max(maxQ, Math.max(b.pattern.n, Math.max(b.nebula.n, b.stars.n)) / 4);
        GpuBuffer ib = idx.getBuffer(maxQ * 6);
        float day = dayK, night = 1 - dayK;

        // сначала все вызовы с их матрицами (writeTransform пишет в буфер — только вне прохода), потом один проход
        ops = 0;
        {
            // 1. купол
            drawQ(OPAQUE, sv, 0, domeQ, view, 1, 1, 1, 1);
            if (b != null && b.uploaded) {
                // 2. узор: медленно вращается вокруг своей наклонной оси
                if (b.pattern.vb != null && c.patStrength > 0) {
                    rot.identity().rotate((float) (time * 0.0105 * c.patSpeed), b.patAxis);
                    float a = c.patStrength * (0.6f + 0.4f * night) * (1 - wet * 0.8f);
                    drawQ(TRANS, b.pattern.vb, 0, b.pattern.n / 4, mv.set(view).mul(rot), 1, 1, 1, a);
                }
                // 3. туманность и звёзды — вращаются с небом (как ванильные звёзды)
                rot.set(celestial).rotateX(starA);
                mv.set(view).mul(rot);
                float clear = 1 - wet * 0.9f;
                if (b.nebula.vb != null && c.nebStrength > 0)
                    drawQ(TRANS, b.nebula.vb, 0, b.nebula.n / 4, mv, 1, 1, 1, c.nebStrength * (float) Math.pow(night, 1.5) * clear);
                float sa = (night * night + c.starDay * day) * clear;
                if (b.stars.vb != null && sa > 0.01f)
                    drawQ(ADD, b.stars.vb, 0, b.stars.n / 4, mv, 1, 1, 1, Math.min(1, sa));
            }
            // 4. луна и её свечение (днём бледная), 5. солнце и ореол
            float moonA2 = (0.35f + 0.65f * night) * (1 - wet * 0.7f);
            drawQ(TRANS, vb, moonStart, moonQ, view, 1, 1, 1, moonA2);
            drawQ(ADD, vb, moonHaloStart, moonHaloQ, view, 1, 1, 1, night * (1 - wet));
            drawQ(TRANS, vb, sunStart, sunQ, view, 1, 1, 1, 1 - wet * 0.85f);
            drawQ(ADD, vb, sunHaloStart, sunHaloQ, view, 1, 1, 1, (1 - wet * 0.9f) * smooth(-0.25f, 0.02f, sunH));
            // 6. дымка у горизонта цветом тумана игры
            drawQ(TRANS, sv, fogStart, fogQ, view, 1, 1, 1, 1);
        }
        if (ops == 0) return;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Hoka sky", color, OptionalInt.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setIndexBuffer(ib, idx.type());
            for (int i = 0; i < ops; i++) {
                Op o = opList[i];
                pass.setPipeline(o.pipe);
                pass.setUniform("DynamicTransforms", o.transform);
                pass.setVertexBuffer(0, o.vb);
                pass.drawIndexed(o.base, 0, o.quads * 6, 1);
                o.vb = null; o.transform = null;
            }
        }
    }

    // ── купол и дымка: постоянный буфер (0.2.1) ──
    private final DynBuf sph = new DynBuf();
    private final Upload sphUp = new Upload(), dynUp = new Upload();
    private boolean sphOk;
    private Cfg sphCfg;
    private int sphDomeQ, sphFogStart, sphFogQ;
    private int kDay, kTw, kWet, kSx, kSy, kSz, kFog;

    /** Буфер купола+дымки под текущие входы (пересобрать, если сменились); null — не залился. */
    private GpuBuffer sphere(Cfg c, float dayK, float twK, float wet, int fog) {
        int a = Math.round(dayK * 1024), b = Math.round(twK * 1024), w = Math.round(wet * 1024);
        int sx = Math.round(sun.x * 1024), sy = Math.round(sun.y * 1024), sz = Math.round(sun.z * 1024);
        GpuBuffer vb = sphUp.vb;
        if (sphOk && vb != null && !vb.isClosed() && c == sphCfg && a == kDay && b == kTw && w == kWet
                && sx == kSx && sy == kSy && sz == kSz && fog == kFog) return vb;
        DynBuf d = sph;
        d.n = 0;
        sphDomeQ = dome(d, c, dayK, twK, wet, fog);
        sphFogStart = d.n;
        sphFogQ = fogRing(d, fog, c.fogBlend, wet);
        vb = sphUp.upload(d, "Hoka sky dome");
        sphOk = vb != null;
        sphCfg = c; kDay = a; kTw = b; kWet = w; kSx = sx; kSy = sy; kSz = sz; kFog = fog;
        return vb;
    }

    /** Вызов отрисовки кадра (собираются до открытия прохода). */
    private static final class Op {
        RenderPipeline pipe;
        GpuBuffer vb;
        com.mojang.blaze3d.buffers.GpuBufferSlice transform;
        int base, quads;
    }

    private final Op[] opList = new Op[16];
    private int ops;
    private final Vector4f tint = new Vector4f();
    private static final Vector3f ZERO3 = new Vector3f();
    private static final Matrix4f IDENT = new Matrix4f();

    private static void celPos(Matrix4f celestial, float angle, Vector3f out) {
        // (0,1,0), повёрнутый на angle вокруг X: (0, cos, sin); затем небесный поворот
        out.set(0, (float) Math.cos(angle), (float) Math.sin(angle));
        celestial.transformDirection(out).normalize();
    }

    /** Запомнить вызов: матрица и цвет пишутся в буфер униформ сейчас (вне прохода), рисуется потом. */
    private void drawQ(RenderPipeline pipe, GpuBuffer vb, int baseVertex, int quads, Matrix4f mv,
                       float r, float g, float b, float a) {
        if (quads <= 0 || a <= 0.003f || ops >= opList.length) return;
        Op o = opList[ops];
        if (o == null) o = opList[ops] = new Op();
        o.transform = RenderSystem.getDynamicUniforms().writeTransform(mv, tint.set(r, g, b, Math.min(1, a)), ZERO3, IDENT);
        o.pipe = pipe; o.vb = vb; o.base = baseVertex; o.quads = quads;
        ops++;
    }

    // ───────────────────────── туман (0.2) ─────────────────────────
    //
    // Ванильный туман 1.21.11: GameRenderer.renderLevel → FogRenderer.setupFog пишет UBO «Fog» (цвет, env start/end —
    // сферическая дальность, renderDistance start/end — цилиндрическая, skyEnd, cloudEnd), его читают шейдеры сущностей,
    // частиц, блоков-сущностей. Sodium берёт те же числа в свой FogParameters (FogRendererMixin) и кладёт в
    // SodiumWorldRenderer.lastFogParameters в setupTerrain — до основного прохода; рисует землю уже в нём. Модулю
    // миксины недоступны, поэтому в START_MAIN (после setupFog и setupTerrain, до земли) переписываем оба: UBO текущего
    // кадра и lastFogParameters. cullDistance у Sodium оставляем прежний — иначе он каждый кадр перестраивал бы граф.

    /** Цвет тумана = цвет горизонта купола по времени суток (к закату — в сторону солнца теплее), или fog.color. */
    private int fogColor(Cfg c, Camera cam, float dayK, float twK, float wet) {
        float sx = sun.x, sz = sun.z, sl = (float) Math.sqrt(sx * sx + sz * sz);
        var f = cam.forwardVector();
        float fl = (float) Math.sqrt(f.x() * f.x() + f.z() * f.z());
        float side = sl > 1e-4f && fl > 1e-4f ? (float) Math.pow(Math.max(0, (f.x() * sx + f.z() * sz) / (sl * fl) * 0.5f + 0.5f), 3) : 0.5f;
        int col;
        if (c.fogColor < 0) col = lerp(lerp(c.nightH, c.dayH, dayK), c.setH, twK * (0.3f + 0.7f * side));
        else col = lerp(lerp(lerp(c.nightH, scale(c.fogColor, 0.3f), 0.5f), c.fogColor, dayK), c.setH, twK * 0.5f * side);
        if (wet > 0) col = lerp(col, scale(gray(col), 0.72f), wet * 0.65f);
        return col;
    }

    private final net.minecraft.client.renderer.fog.FogData fogData = new net.minecraft.client.renderer.fog.FogData();

    private void applyFog(Cfg c, Minecraft mc, ClientLevel lvl, Camera cam, int rgb, float pt) {
        float rd = lodFogChunks(mc.options.getEffectiveRenderDistance()) * 16f;   // 0.2.2: с дальней прорисовкой — до её края
        // ваниль (дождь, «туман босса») — чтобы погода по-прежнему сгущала
        FogDataHolder.fill(fogEnv, fogData, cam, lvl, rd, mc.getDeltaTracker());
        var probe = cam.attributeProbe();
        float baseS = probe.getValue(EnvironmentAttributes.FOG_START_DISTANCE, pt), baseE = probe.getValue(EnvironmentAttributes.FOG_END_DISTANCE, pt);
        // край прорисовки (по горизонтали): start..end доли дальности
        float rEnd = Math.max(16, rd * c.fogEnd), rStart = Math.min(rEnd - 8, rd * c.fogStart);
        // по сфере: учёт высоты — окно кубов вверх/вниз (и полоса гор), иначе — как край по горизонтали
        float vb = c.fogVertical ? verticalBlocks() : 0;
        float eEnd = (float) Math.sqrt(rEnd * rEnd + vb * vb), eStart = eEnd * c.fogStart;
        if (!c.fogVertical) { eEnd = Float.MAX_VALUE; eStart = Float.MAX_VALUE; }
        // дождь сдвигает ванильные start/end — тот же сдвиг нашим
        float dS = fogData.environmentalStart - baseS, dE = fogData.environmentalEnd - baseE;
        if (dS < 0 && eStart != Float.MAX_VALUE) eStart = Math.max(0, eStart + dS);
        if (dE < 0) { if (eEnd == Float.MAX_VALUE) { eEnd = fogData.environmentalEnd; eStart = Math.min(eStart, fogData.environmentalStart); } else eEnd = Math.max(Math.min(96, eEnd), eEnd + dE); }
        if (mc.gui.getBossOverlay().shouldCreateWorldFog()) { eStart = fogData.environmentalStart; eEnd = fogData.environmentalEnd; }
        if (eStart >= eEnd) eStart = eEnd - 1;
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f, a = c.fogDensity;
        writeVanillaFog(mc, r, g, b, a, eStart, eEnd, rStart, rEnd, fogData.skyEnd, fogData.cloudEnd);
        writeSodiumFog(r, g, b, a, eStart, eEnd, rStart, rEnd);
    }

    /** Вызов ванильного setupFog окружения (только числа — без записи в UBO). */
    private static final class FogDataHolder {
        static void fill(AtmosphericFogEnvironment env, net.minecraft.client.renderer.fog.FogData d, Camera cam, ClientLevel lvl,
                         float rd, net.minecraft.client.DeltaTracker dt) {
            d.environmentalStart = 0; d.environmentalEnd = 1024; d.skyEnd = rd; d.cloudEnd = 2048;
            env.setupFog(d, cam, lvl, rd, dt);
        }
    }

    /** Дальность тумана в чанках от ядра 0.64+ (дальняя прорисовка включена — её дальность); старое ядро — rd. */
    private static java.lang.reflect.Method lodFog;
    private static boolean lodTried;

    private static java.lang.reflect.Method lodColor;

    private static void lodFogColor(int rgb) {
        try {
            if (!lodTried) lodFogChunks(0);
            if (lodColor == null && lodFog != null) lodColor = lodFog.getDeclaringClass().getMethod("skyFogColor", int.class);
            if (lodColor != null) lodColor.invoke(null, rgb);
        } catch (Throwable t) {
            lodFog = null;
        }
    }

    private static int lodFogChunks(int rd) {
        try {
            if (!lodTried) {
                lodTried = true;
                Class<?> c = Class.forName("ink.hoka.odyssey.client.lod.LodRenderer", false, SkyClientModule.class.getClassLoader());
                lodFog = c.getMethod("fogChunks", int.class);
            }
            return lodFog == null ? rd : (Integer) lodFog.invoke(null, rd);
        } catch (Throwable t) {
            lodFog = null;
            return rd;
        }
    }

    /** Высота окна кубов (блоки) из ядра: max(«Дальность по высоте», «Горы целиком») × 16; нет ядра — 0. */
    private static java.lang.reflect.Method vvGet, vvSurf;
    private static boolean vvTried;

    private static float verticalBlocks() {
        try {
            if (!vvTried) {
                vvTried = true;
                Class<?> vv = Class.forName("ink.hoka.odyssey.client.VerticalView", false, SkyClientModule.class.getClassLoader());
                vvGet = vv.getMethod("get");
                try { vvSurf = vv.getMethod("surface"); } catch (NoSuchMethodException ignored) {}
            }
            if (vvGet == null) return 0;
            int v = (Integer) vvGet.invoke(null);
            int s = vvSurf == null ? 0 : (Integer) vvSurf.invoke(null);
            return Math.max(v, s) * 16f;
        } catch (Throwable t) {
            vvGet = null;
            return 0;
        }
    }

    // ванильный UBO: GameRenderer.fogRenderer → FogRenderer.regularBuffer (имена в игре промежуточные — ищем по типу)
    private static java.lang.reflect.Field fogRendererField, ringField;
    private static boolean vanillaTried, vanillaBroken;

    private void writeVanillaFog(Minecraft mc, float r, float g, float b, float a, float eS, float eE, float rS, float rE, float sky, float cloud) {
        if (vanillaBroken) return;
        try {
            if (!vanillaTried) {
                vanillaTried = true;
                for (var f : net.minecraft.client.renderer.GameRenderer.class.getDeclaredFields())
                    if (f.getType() == net.minecraft.client.renderer.fog.FogRenderer.class) { f.setAccessible(true); fogRendererField = f; }
                for (var f : net.minecraft.client.renderer.fog.FogRenderer.class.getDeclaredFields())
                    if (f.getType() == net.minecraft.client.renderer.MappableRingBuffer.class) { f.setAccessible(true); ringField = f; }
                if (fogRendererField == null || ringField == null) throw new IllegalStateException("нет полей FogRenderer");
            }
            Object fr = fogRendererField.get(mc.gameRenderer);
            var ring = (net.minecraft.client.renderer.MappableRingBuffer) ringField.get(fr);
            if (ring == null) return;
            GpuBuffer buf = ring.currentBuffer();
            // туман выключен (F3) — шейдеры смотрят в пустой буфер; наш писать незачем, но и вреда нет
            try (GpuBuffer.MappedView v = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buf, false, true)) {
                ByteBuffer bb = v.data();
                bb.position(0);
                com.mojang.blaze3d.buffers.Std140Builder.intoBuffer(bb).putVec4(r, g, b, a)
                        .putFloat(eS).putFloat(eE).putFloat(rS).putFloat(rE).putFloat(sky).putFloat(cloud);
            }
        } catch (Throwable t) {
            vanillaBroken = true;
            if (ctx != null) ctx.logger().warn("небо: свой туман для сущностей недоступен: {}", t.toString());
        }
    }

    // Sodium: SodiumWorldRenderer.instanceNullable().lastFogParameters = new FogParameters(9 чисел), cullDistance — прежний
    private static java.lang.reflect.Method sodInst, sodCull;
    private static java.lang.reflect.Field sodLast;
    private static java.lang.reflect.Constructor<?> sodCtor;
    private static boolean sodTried, sodBroken;

    private void writeSodiumFog(float r, float g, float b, float a, float eS, float eE, float rS, float rE) {
        if (sodBroken) return;
        try {
            if (!sodTried) {
                sodTried = true;
                ClassLoader cl = Minecraft.class.getClassLoader();
                Class<?> wr, fp;
                try {
                    wr = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", false, cl);
                    fp = Class.forName("net.caffeinemc.mods.sodium.client.util.FogParameters", false, cl);
                } catch (ClassNotFoundException e) { sodBroken = true; return; }   // без Sodium — хватает ванильного UBO
                sodInst = wr.getMethod("instanceNullable");
                sodLast = wr.getDeclaredField("lastFogParameters");
                sodLast.setAccessible(true);
                sodCull = fp.getMethod("cullDistance");
                sodCtor = fp.getConstructor(float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class);
            }
            Object w = sodInst.invoke(null);
            if (w == null) return;
            Object old = sodLast.get(w);
            if (old == null) return;
            float cull = (Float) sodCull.invoke(old);
            sodLast.set(w, sodCtor.newInstance(r, g, b, a, eS, eE, rS, rE, cull));
        } catch (Throwable t) {
            sodBroken = true;
            if (ctx != null) ctx.logger().warn("небо: свой туман для Sodium недоступен: {}", t.toString());
        }
    }

    // ───────────────────────── купол ─────────────────────────

    private static final float[] DOME_EL = {-90, -40, -15, -6, -2, 0, 2, 5, 9, 14, 20, 28, 38, 50, 64, 78, 90};
    private static final int AZ = 40;
    private final int[] domeCol = new int[DOME_EL.length * (AZ + 1)];

    private int dome(DynBuf d, Cfg c, float dayK, float twK, float wet, int fog) {
        int zen = lerp(c.nightZ, c.dayZ, dayK), hor = lerp(c.nightH, c.dayH, dayK);
        zen = lerp(zen, c.setZ, twK * 0.55f);
        float sx = sun.x, sz = sun.z, sl = (float) Math.sqrt(sx * sx + sz * sz);
        if (sl > 1e-4f) { sx /= sl; sz /= sl; }
        for (int r = 0; r < DOME_EL.length; r++) {
            float el = DOME_EL[r] * DEG, y = (float) Math.sin(el), h = (float) Math.cos(el);
            for (int a = 0; a <= AZ; a++) {
                float az = a * (float) (2 * Math.PI / AZ);
                float x = h * (float) Math.cos(az), z = h * (float) Math.sin(az);
                float toSun = (x * sx + z * sz) / Math.max(1e-4f, h);           // по азимуту: 1 — в сторону солнца
                if (h < 1e-4f) toSun = 0;
                float side = (float) Math.pow(Math.max(0, toSun * 0.5f + 0.5f), 3);
                int hc = lerp(hor, c.setH, twK * (0.3f + 0.7f * side));
                float t = (float) Math.pow(Math.max(0, y), 0.45);
                int col = lerp(hc, zen, t);
                float g = Math.max(0, x * sun.x + y * sun.y + z * sun.z);        // к солнцу
                float glow = (float) (Math.pow(g, 4) * (0.12f * dayK + 0.45f * twK));
                if (glow > 0.003f) col = add(col, c.glow, glow);
                if (wet > 0) col = lerp(col, scale(gray(col), 0.72f), wet * 0.65f);
                // 0.2: под горизонтом — цвет тумана (не темнее): с высоты низ неба = дымка, в которую уходит край мира
                if (y < 0) col = lerp(col, fog, smooth(0, -0.12f, y));
                domeCol[r * (AZ + 1) + a] = 0xFF000000 | col;
            }
        }
        return ring(d, DOME_EL, domeCol, 0);
    }

    private static final float[] FOG_EL = {-90, -30, -8, -2, 0, 2, 5, 9, 14, 20};
    private final int[] fogCol = new int[FOG_EL.length * (AZ + 1)];

    /** Дымка у горизонта: ниже горизонта — цвет тумана (как дальний мир в тумане), к ~20° тает. */
    private int fogRing(DynBuf d, int fog, float blend, float wet) {
        float k = Math.min(1, blend + wet * 0.2f);
        for (int r = 0; r < FOG_EL.length; r++) {
            float y = (float) Math.sin(FOG_EL[r] * DEG);
            float kk = y < 0 ? k + (1 - k) * smooth(0, -0.1f, y) : k;      // ниже горизонта — сплошь туман
            float a = kk * (1 - smooth(-0.02f, 0.34f, y));
            int ai = Math.round(Math.max(0, Math.min(1, a)) * 255);
            for (int i = 0; i <= AZ; i++) fogCol[r * (AZ + 1) + i] = ai << 24 | fog;
        }
        return ring(d, FOG_EL, fogCol, 0.995f);
    }

    /** Кольца сферы по высотам els (градусы), цвета в точках сетки; radiusK — чуть меньше радиуса (порядок не важен — без глубины). */
    private static int ring(DynBuf d, float[] els, int[] cols, float radiusK) {
        float rr = R * (radiusK == 0 ? 1 : radiusK);
        int q = 0;
        for (int r = 0; r + 1 < els.length; r++) {
            float e0 = els[r] * DEG, e1 = els[r + 1] * DEG;
            float y0 = (float) Math.sin(e0) * rr, h0 = (float) Math.cos(e0) * rr, y1 = (float) Math.sin(e1) * rr, h1 = (float) Math.cos(e1) * rr;
            for (int a = 0; a < AZ; a++) {
                float a0 = a * (float) (2 * Math.PI / AZ), a1 = (a + 1) * (float) (2 * Math.PI / AZ);
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                int i00 = r * (AZ + 1) + a, i01 = i00 + 1, i10 = i00 + AZ + 1, i11 = i10 + 1;
                if ((cols[i00] | cols[i01] | cols[i10] | cols[i11]) >>> 24 == 0) continue;
                d.v(h0 * c0, y0, h0 * s0, cols[i00]);
                d.v(h0 * c1, y0, h0 * s1, cols[i01]);
                d.v(h1 * c1, y1, h1 * s1, cols[i11]);
                d.v(h1 * c0, y1, h1 * s0, cols[i10]);
                q++;
            }
        }
        return q;
    }

    // ───────────────────────── солнце и луна ─────────────────────────

    private final Quaternionf q = new Quaternionf();
    private final Vector3f bu = new Vector3f(), bv = new Vector3f(), cc = new Vector3f(), nn = new Vector3f(), cn = new Vector3f();
    /** Углы грани куба в её плоскости (без отсечения граней — порядок обхода не важен). */
    private static final float[][] CORNERS = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};

    /**
     * Светило: куб (type cube) или квадрат лицом к нам (square), угловой размер sizeDeg, вращается (spin, рад).
     * Солнце (emissive) — светится само, грани к нам ярче; луна — освещена с одной стороны по фазе (0 — полная).
     */
    private int body(DynBuf d, Vector3f dir, float sizeDeg, int col, int edge, float spin, String type, int phase,
                     boolean phases, boolean emissive) {
        float dist = R * 0.8f;
        float h = dist * (float) Math.tan(sizeDeg * 0.5f * DEG);
        cc.set(dir).mul(dist);
        basis(dir);
        if (type.equals("square")) {                                          // плоский квадрат, как у игры
            int c1 = 0xFF000000 | col, c2 = 0xFF000000 | lerp(col, edge, 0.35f);
            quad(d, cc, bu, bv, h, spin, c1, c1, c2, c2);
            return 1;
        }
        // поворот куба: наклон (видно три грани) + вращение вокруг направления на светило
        q.identity().rotateAxis(spin, dir.x, dir.y, dir.z).rotateAxis(0.62f, bu.x, bu.y, bu.z).rotateAxis(0.5f, bv.x, bv.y, bv.z);
        // свет луны: полная — со стороны камеры, новая — из-за луны
        float th = phase / 8f * (float) (2 * Math.PI);
        Vector3f light = tmp.set(dir).mul(-(float) Math.cos(th)).add(bu.x * (float) Math.sin(th), bu.y * (float) Math.sin(th), bu.z * (float) Math.sin(th));
        int faces = 0;
        for (int face = 0; face < 6; face++) {
            int axis = face / 2;
            float sign = face % 2 == 0 ? 1 : -1;
            nn.set(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
            q.transform(nn);
            float facing = -(nn.x * dir.x + nn.y * dir.y + nn.z * dir.z);    // к камере (камера — в начале координат)
            if (facing <= 0.001f) continue;
            float k;
            int fc;
            if (emissive) { k = 0.82f + 0.18f * facing; fc = lerp(edge, col, 0.45f + 0.55f * facing); }
            else if (phases) { float l = Math.max(0, nn.dot(light)); k = 0.08f + 0.92f * l; fc = col; }
            else { k = 0.7f + 0.3f * facing; fc = col; }
            for (int v = 0; v < 4; v++) {
                float[] uv = CORNERS[v];
                cn.setComponent(axis, sign).setComponent((axis + 1) % 3, uv[0]).setComponent((axis + 2) % 3, uv[1]);
                q.transform(cn);
                // углы дальше от нас — чуть в цвет края (объём без освещения)
                float far = (cn.x * dir.x + cn.y * dir.y + cn.z * dir.z) * 0.5f + 0.5f;
                int vc = emissive ? lerp(fc, edge, far * 0.35f) : fc;
                d.v(cc.x + cn.x * h, cc.y + cn.y * h, cc.z + cn.z * h, 0xFF000000 | scale(vc, k));
            }
            faces++;
        }
        return faces;
    }

    /** Квадратный ореол вокруг светила: внутренний квадрат alpha → наружу 0; size — во сколько раз больше светила. */
    private int halo(DynBuf d, Vector3f dir, float sizeDeg, int col, float alpha, float rot, float size) {
        float dist = R * 0.8f + 0.5f;
        float h = dist * (float) Math.tan(sizeDeg * 0.5f * DEG);
        cc.set(dir).mul(dist);
        basis(dir);
        float rin = h * 0.9f, rout = h * size;
        int ci = Math.round(Math.min(1, alpha) * 255) << 24 | col, co = col;
        // центр и 4 трапеции
        quad(d, cc, bu, bv, rin, rot, ci, ci, ci, ci);
        float cs = (float) Math.cos(rot), sn = (float) Math.sin(rot);
        float[][] k = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; i++) {
            float[] a = k[i], b = k[(i + 1) % 4];
            vtx(d, a[0] * rin, a[1] * rin, cs, sn, ci);
            vtx(d, b[0] * rin, b[1] * rin, cs, sn, ci);
            vtx(d, b[0] * rout, b[1] * rout, cs, sn, co);
            vtx(d, a[0] * rout, a[1] * rout, cs, sn, co);
        }
        return 5;
    }

    private void vtx(DynBuf d, float u, float v, float cs, float sn, int col) {
        float ru = u * cs - v * sn, rv = u * sn + v * cs;
        d.v(cc.x + bu.x * ru + bv.x * rv, cc.y + bu.y * ru + bv.y * rv, cc.z + bu.z * ru + bv.z * rv, col);
    }

    private void quad(DynBuf d, Vector3f c, Vector3f u, Vector3f v, float h, float rot, int c0, int c1, int c2, int c3) {
        float cs = (float) Math.cos(rot), sn = (float) Math.sin(rot);
        vtx(d, -h, -h, cs, sn, c0);
        vtx(d, h, -h, cs, sn, c1);
        vtx(d, h, h, cs, sn, c2);
        vtx(d, -h, h, cs, sn, c3);
    }

    /** bu, bv — оси плоскости, перпендикулярной dir. */
    private void basis(Vector3f dir) {
        bu.set(0, 1, 0).cross(dir);
        if (bu.lengthSquared() < 1e-4f) bu.set(1, 0, 0).cross(dir);
        bu.normalize();
        bv.set(dir).cross(bu).normalize();
    }

    // ───────────────────────── буфер кадра ─────────────────────────

    /** Вершины кадра (позиция + ARGB), растёт при нехватке. */
    private static final class DynBuf {
        float[] p = new float[3 * 8192];
        int[] c = new int[8192];
        int n;

        void v(float x, float y, float z, int argb) {
            if (n == c.length) { p = java.util.Arrays.copyOf(p, p.length * 2); c = java.util.Arrays.copyOf(c, c.length * 2); }
            p[n * 3] = x; p[n * 3 + 1] = y; p[n * 3 + 2] = z;
            c[n++] = argb;
        }
    }

    private final DynBuf dyn = new DynBuf();

    /** Буфер в видеопамяти под DynBuf (растёт при нехватке) и его промежуточная память. */
    private static final class Upload {
        GpuBuffer vb;
        ByteBuffer mem;

        GpuBuffer upload(DynBuf d, String name) {
            int bytes = Math.max(16, d.n * 16);
            if (mem == null || mem.capacity() < bytes) {
                if (mem != null) MemoryUtil.memFree(mem);
                mem = MemoryUtil.memAlloc(bytes + bytes / 2);
            }
            ByteBuffer buf = mem;
            buf.clear();
            putVerts(buf, d.p, d.c, d.n);
            if (d.n == 0) buf.putLong(0).putLong(0);
            buf.flip();
            var dev = RenderSystem.getDevice();
            if (vb == null || vb.isClosed() || vb.size() < bytes) {
                if (vb != null) vb.close();
                vb = dev.createBuffer(() -> name, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes + bytes / 2);
            }
            dev.createCommandEncoder().writeToBuffer(vb.slice(0, buf.remaining()), buf);
            return vb;
        }

        void release() {
            if (vb != null) { vb.close(); vb = null; }
            if (mem != null) { MemoryUtil.memFree(mem); mem = null; }
        }
    }

    private static final boolean LE = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;

    /** POSITION_COLOR: 3 float + RGBA = 16 байт. 0.2.1: прямой записью в память (буфер — memAlloc, родной порядок). */
    private static void putVerts(ByteBuffer buf, float[] p, int[] c, int n) {
        if (buf.order() != java.nio.ByteOrder.nativeOrder() || buf.remaining() < n * 16) {
            for (int k = 0; k < n; k++) {
                int argb = c[k];
                buf.putFloat(p[k * 3]).putFloat(p[k * 3 + 1]).putFloat(p[k * 3 + 2])
                        .put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
            return;
        }
        long a = MemoryUtil.memAddress(buf);                               // с текущей позиции
        for (int k = 0; k < n; k++, a += 16) {
            int argb = c[k];
            int r = argb >> 16 & 255, g = argb >> 8 & 255, b = argb & 255, al = argb >>> 24;
            MemoryUtil.memPutFloat(a, p[k * 3]);
            MemoryUtil.memPutFloat(a + 4, p[k * 3 + 1]);
            MemoryUtil.memPutFloat(a + 8, p[k * 3 + 2]);
            MemoryUtil.memPutInt(a + 12, LE ? r | g << 8 | b << 16 | al << 24 : r << 24 | g << 16 | b << 8 | al);
        }
        buf.position(buf.position() + n * 16);
    }

    // ───────────────────────── запечённые слои ─────────────────────────

    /** Неизменные слои (узор, туманность, звёзды) — строятся один раз на настройки, в фоне. */
    private static final class Baked {
        final Mesh pattern = new Mesh(), nebula = new Mesh(), stars = new Mesh();
        final Vector3f patAxis = new Vector3f();
        boolean uploaded;

        void upload() {
            uploaded = true;
            try {
                pattern.upload("Hoka sky pattern");
                nebula.upload("Hoka sky nebula");
                stars.upload("Hoka sky stars");
            } catch (Exception e) {
                release();
            }
        }

        void release() {
            pattern.release(); nebula.release(); stars.release();
        }

        static Baked build(Cfg c) {
            Baked b = new Baked();
            java.util.Random rnd = new java.util.Random(c.seed * 0x9E3779B97F4A7C15L + 1);
            b.patAxis.set(rnd.nextFloat() - 0.5f, 1.5f, rnd.nextFloat() - 0.5f).normalize();
            Vector3f bandAxis = new Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() * 0.6f, rnd.nextFloat() - 0.5f).normalize();
            switch (c.pattern) {
                case "bands" -> sphere(b.pattern, 40, R * 0.99f, dv -> bands(dv, bandAxis, c));
                case "cells" -> cells(b.pattern, c);
                case "waves" -> sphere(b.pattern, 40, R * 0.99f, dv -> waves(dv, c));
                default -> {}
            }
            Vector3f gal = new Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize();
            if (c.nebStrength > 0) sphere(b.nebula, 44, R * 0.985f, dv -> nebula(dv, gal, c));
            stars(b.stars, c, gal, rnd);
            return b;
        }
    }

    private static final class Mesh {
        float[] p = new float[0];
        int[] c = new int[0];
        int n;
        GpuBuffer vb;

        void v(float x, float y, float z, int argb) {
            if (n == c.length) { int cap = Math.max(1024, c.length * 2); p = java.util.Arrays.copyOf(p, cap * 3); c = java.util.Arrays.copyOf(c, cap); }
            p[n * 3] = x; p[n * 3 + 1] = y; p[n * 3 + 2] = z;
            c[n++] = argb;
        }

        void upload(String name) {
            if (n < 4) return;
            ByteBuffer buf = MemoryUtil.memAlloc(n * 16);
            try {
                putVerts(buf, p, c, n);
                buf.flip();
                var dev = RenderSystem.getDevice();
                vb = dev.createBuffer(() -> name, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, n * 16L);
                dev.createCommandEncoder().writeToBuffer(vb.slice(0, buf.remaining()), buf);
            } finally {
                MemoryUtil.memFree(buf);
            }
            p = null; c = null;                                              // в куче больше не нужны
        }

        void release() {
            if (vb != null) { vb.close(); vb = null; }
        }
    }

    private interface Paint { int color(Vector3f dir); }

    /** Куб-сфера (6 граней × grid²) радиуса r; квады целиком прозрачные — пропускаем. */
    private static void sphere(Mesh m, int grid, float r, Paint paint) {
        int[] col = new int[(grid + 1) * (grid + 1)];
        float[] pos = new float[col.length * 3];
        Vector3f dv = new Vector3f();
        for (int face = 0; face < 6; face++) {
            int axis = face / 2;
            float sign = face % 2 == 0 ? 1 : -1;
            for (int i = 0; i <= grid; i++) for (int j = 0; j <= grid; j++) {
                float u = (float) Math.tan((-1 + 2f * i / grid) * Math.PI / 4), v = (float) Math.tan((-1 + 2f * j / grid) * Math.PI / 4);
                float[] f = new float[3];
                f[axis] = sign; f[(axis + 1) % 3] = u; f[(axis + 2) % 3] = v;
                dv.set(f[0], f[1], f[2]).normalize();
                int k = i * (grid + 1) + j;
                col[k] = paint.color(dv);
                pos[k * 3] = dv.x * r; pos[k * 3 + 1] = dv.y * r; pos[k * 3 + 2] = dv.z * r;
            }
            for (int i = 0; i < grid; i++) for (int j = 0; j < grid; j++) {
                int a = i * (grid + 1) + j, b = a + grid + 1, c2 = b + 1, d = a + 1;
                if (((col[a] >>> 24) | (col[b] >>> 24) | (col[c2] >>> 24) | (col[d] >>> 24)) < 3) continue;
                for (int k : new int[]{a, b, c2, d}) m.v(pos[k * 3], pos[k * 3 + 1], pos[k * 3 + 2], col[k]);
            }
        }
    }

    /** Мягкие полосы (как сияние): синус вдоль наклонной оси, изогнутый шумом. */
    private static int bands(Vector3f d, Vector3f axis, Cfg c) {
        float w = fbm(d.x * 2.2f, d.y * 2.2f, d.z * 2.2f, c.seed, 4);
        float t = d.dot(axis) * 5.5f * c.patScale + w * 1.6f;
        float s = 0.5f + 0.5f * (float) Math.sin(t * Math.PI * 2);
        float a = (float) Math.pow(s, 4) * smooth(-0.25f, 0.35f, fbm(d.x * 1.3f + 7, d.y * 1.3f, d.z * 1.3f, c.seed + 3, 3) + 0.15f);
        int col = lerp(c.patColor, c.patColor2, smooth(-0.3f, 0.3f, w));
        return alpha(a * 0.85f) | col;
    }

    /** Волны: концентрические кольца вокруг двух центров — интерференция. */
    private static int waves(Vector3f d, Cfg c) {
        float a1 = (float) Math.acos(Math.max(-1, Math.min(1, d.y))), a2 = (float) Math.acos(Math.max(-1, Math.min(1, d.x * 0.6f + d.z * 0.8f)));
        float s = (float) (Math.sin(a1 * 18 * c.patScale) + Math.sin(a2 * 14 * c.patScale)) * 0.25f + 0.5f;
        float a = (float) Math.pow(s, 3) * 0.7f;
        return alpha(a) | lerp(c.patColor, c.patColor2, s);
    }

    /** «Кубики»: мозаика мягких квадратов на куб-сфере (в духе кубического мира), яркость клеток — шум и случай. */
    private static void cells(Mesh m, Cfg c) {
        int grid = Math.max(6, Math.round(14 * c.patScale));
        float r = R * 0.99f, gap = 0.12f;
        java.util.Random rnd = new java.util.Random(c.seed * 31L + 7);
        Vector3f dv = new Vector3f();
        for (int face = 0; face < 6; face++) {
            int axis = face / 2;
            float sign = face % 2 == 0 ? 1 : -1;
            for (int i = 0; i < grid; i++) for (int j = 0; j < grid; j++) {
                float u0 = -1 + 2f * (i + gap) / grid, u1 = -1 + 2f * (i + 1 - gap) / grid;
                float v0 = -1 + 2f * (j + gap) / grid, v1 = -1 + 2f * (j + 1 - gap) / grid;
                float[] f = new float[3];
                f[axis] = sign; f[(axis + 1) % 3] = (u0 + u1) / 2; f[(axis + 2) % 3] = (v0 + v1) / 2;
                dv.set(f[0], f[1], f[2]).normalize();
                float n = fbm(dv.x * 2.4f, dv.y * 2.4f, dv.z * 2.4f, c.seed + 11, 3);
                float a = smooth(-0.05f, 0.35f, n) * (0.35f + 0.65f * rnd.nextFloat());
                if (a < 0.04f) continue;
                int col = alpha(a * 0.6f) | lerp(c.patColor, c.patColor2, rnd.nextFloat());
                float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
                for (float[] p : uv) {
                    f[axis] = sign; f[(axis + 1) % 3] = p[0]; f[(axis + 2) % 3] = p[1];
                    dv.set(f[0], f[1], f[2]).normalize().mul(r);
                    m.v(dv.x, dv.y, dv.z, col);
                }
            }
        }
    }

    /** Туманность: два цвета по шуму, гуще вдоль «млечного пути» (большой круг с осью gal). */
    private static int nebula(Vector3f d, Vector3f gal, Cfg c) {
        float g = d.dot(gal);
        float band = (float) Math.exp(-g * g / 0.06f);
        float n1 = fbm(d.x * 1.7f, d.y * 1.7f, d.z * 1.7f, c.seed + 21, 5);
        float n2 = fbm(d.x * 3.1f + 30, d.y * 3.1f, d.z * 3.1f, c.seed + 37, 4);
        float dens = smooth(0.05f, 0.6f, n1 * 0.9f + band * 0.55f - 0.12f);
        if (dens < 0.01f) return 0;
        int col = lerp(c.neb1, c.neb2, smooth(-0.25f, 0.25f, n2));
        col = add(col, 0xFFFFFF, smooth(0.45f, 0.8f, n1 + band * 0.35f) * 0.25f);
        return alpha(dens * 0.75f) | col;
    }

    /** Звёзды: ромбики разного размера и цвета, треть — вдоль млечного пути. */
    private static void stars(Mesh m, Cfg c, Vector3f gal, java.util.Random rnd) {
        Vector3f d = new Vector3f(), u = new Vector3f(), v = new Vector3f();
        float r = R * 0.98f;
        for (int i = 0; i < c.stars; i++) {
            d.set((float) rnd.nextGaussian(), (float) rnd.nextGaussian(), (float) rnd.nextGaussian()).normalize();
            if (i % 3 == 0) { d.sub(new Vector3f(gal).mul(d.dot(gal) * 0.85f)).normalize(); }   // ближе к полосе
            float big = rnd.nextFloat();
            float s = (0.05f + 0.13f * big * big * big) * c.starSize * r / 50f;
            if (rnd.nextFloat() < 0.012f) s *= 2.2f;                              // редкие яркие
            float t = rnd.nextFloat();
            int col = t < 0.15f ? 0x9FC3FF : t < 0.7f ? 0xFFFFFF : t < 0.9f ? 0xFFE7B0 : 0xFFB59A;
            int a = Math.round((0.45f + 0.55f * rnd.nextFloat()) * 255);
            u.set(0, 1, 0).cross(d);
            if (u.lengthSquared() < 1e-4f) u.set(1, 0, 0).cross(d);
            u.normalize();
            v.set(d).cross(u).normalize();
            float rot = rnd.nextFloat() * (float) Math.PI;
            float cs = (float) Math.cos(rot) * s, sn = (float) Math.sin(rot) * s;
            float cx = d.x * r, cy = d.y * r, cz = d.z * r;
            int argb = a << 24 | col;
            // ромб: 4 вершины по осям, повёрнутым на rot
            m.v(cx + u.x * cs + v.x * sn, cy + u.y * cs + v.y * sn, cz + u.z * cs + v.z * sn, argb);
            m.v(cx - u.x * sn + v.x * cs, cy - u.y * sn + v.y * cs, cz - u.z * sn + v.z * cs, argb);
            m.v(cx - u.x * cs - v.x * sn, cy - u.y * cs - v.y * sn, cz - u.z * cs - v.z * sn, argb);
            m.v(cx + u.x * sn - v.x * cs, cy + u.y * sn - v.y * cs, cz + u.z * sn - v.z * cs, argb);
        }
    }

    // ───────────────────────── отрисовка: свои пайплайны на ванильном шейдере ─────────────────────────

    private static RenderPipeline pipe(String name, BlendFunction blend) {
        var b = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("hoka_odyssey", "pipeline/" + name))
                .withVertexShader("core/position_color").withFragmentShader("core/position_color")
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false)
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS);
        return (blend == null ? b.withoutBlend() : b.withBlend(blend)).build();
    }

    private static final RenderPipeline OPAQUE = pipe("sky_dome", null);
    private static final RenderPipeline TRANS = pipe("sky_layer", BlendFunction.TRANSLUCENT);
    private static final RenderPipeline ADD = pipe("sky_glow", BlendFunction.LIGHTNING);

    // ───────────────────────── настройки карты ─────────────────────────

    /** Готовые наборы; ключи map.yml поверх них. */
    private static final Map<String, Map<String, String>> PRESETS = Map.of(
            "hoka", Map.ofEntries(
                    Map.entry("day.zenith", "#2c6bd8"), Map.entry("day.horizon", "#a6dcff"),
                    Map.entry("sunset.zenith", "#3b3a8f"), Map.entry("sunset.horizon", "#ff9a5c"),
                    Map.entry("night.zenith", "#050a1e"), Map.entry("night.horizon", "#18234f"),
                    Map.entry("glow", "#ffcf87"), Map.entry("pattern", "bands"),
                    Map.entry("pattern_color", "#ffffff"), Map.entry("pattern_color2", "#bfe6ff"), Map.entry("pattern_strength", "0.16"),
                    Map.entry("nebula_colors", "#7a4dff,#27c4ff"), Map.entry("nebula", "0.8"),
                    Map.entry("sun.color", "#fff4c8"), Map.entry("sun.halo", "#ffc861"),
                    Map.entry("moon.color", "#dfe8ff")),
            "dusk", Map.ofEntries(
                    Map.entry("day.zenith", "#5a5fd0"), Map.entry("day.horizon", "#ffc3a0"),
                    Map.entry("sunset.zenith", "#4a2a7a"), Map.entry("sunset.horizon", "#ff7a4a"),
                    Map.entry("night.zenith", "#0c0618"), Map.entry("night.horizon", "#3a1a4a"),
                    Map.entry("glow", "#ff9d6b"), Map.entry("pattern", "waves"),
                    Map.entry("pattern_color", "#ffd2e6"), Map.entry("pattern_color2", "#ffb07a"), Map.entry("pattern_strength", "0.14"),
                    Map.entry("nebula_colors", "#ff4fa0,#ffb347"), Map.entry("nebula", "0.85"),
                    Map.entry("sun.color", "#ffe2b0"), Map.entry("sun.halo", "#ff8f5a"),
                    Map.entry("moon.color", "#ffe6f0")),
            // космос: всегда ночь (map.yml time: {cycle: false, start: 18000}), без солнца/луны/облаков/тумана, густые звёзды
            // и млечный путь; солнце — настоящее (модуль planets, type: star)
            "space", Map.ofEntries(
                    Map.entry("day.zenith", "#02030a"), Map.entry("day.horizon", "#05071a"),
                    Map.entry("sunset.zenith", "#02030a"), Map.entry("sunset.horizon", "#05071a"),
                    Map.entry("night.zenith", "#010208"), Map.entry("night.horizon", "#040614"),
                    Map.entry("glow", "#000000"), Map.entry("pattern", "none"), Map.entry("pattern_strength", "0"),
                    Map.entry("nebula_colors", "#6a3cff,#1fb8ff"), Map.entry("nebula", "1.0"),
                    Map.entry("stars", "9000"), Map.entry("star_size", "0.85"), Map.entry("star_day", "1"),
                    Map.entry("sun", "none"), Map.entry("moon", "none"), Map.entry("clouds", "false"), Map.entry("fog", "false"),
                    Map.entry("sun.color", "#fff4c8"), Map.entry("sun.halo", "#ffc861"), Map.entry("moon.color", "#dfe8ff")),
            "alien", Map.ofEntries(
                    Map.entry("day.zenith", "#0f8f86"), Map.entry("day.horizon", "#b8ffe0"),
                    Map.entry("sunset.zenith", "#2a1f6b"), Map.entry("sunset.horizon", "#ff5fb0"),
                    Map.entry("night.zenith", "#02100f"), Map.entry("night.horizon", "#0f3a3a"),
                    Map.entry("glow", "#c8ff8a"), Map.entry("pattern", "cells"),
                    Map.entry("pattern_color", "#d0fff0"), Map.entry("pattern_color2", "#8affc8"), Map.entry("pattern_strength", "0.22"),
                    Map.entry("nebula_colors", "#00ffc8,#b05cff"), Map.entry("nebula", "0.9"),
                    Map.entry("sun.color", "#f4ffd0"), Map.entry("sun.halo", "#a8ff6a"),
                    Map.entry("moon.color", "#ffd0f0")));

    static final class Cfg {
        int seed;
        int dayZ, dayH, setZ, setH, nightZ, nightH, glow;
        float fogBlend, tilt;
        String pattern;
        int patColor, patColor2;
        float patStrength, patSpeed, patScale;
        int neb1, neb2;
        float nebStrength;
        int stars;
        float starSize, starDay;
        boolean clouds;
        String sunType, moonType;
        float sunSize, sunSpin, moonSize;
        int sunColor, sunHalo, moonColor;
        boolean moonPhases;
        // туман 0.2: fog: { color: auto|#rrggbb, start: 0.65, end: 1.0, density: 1.0, vertical: true } | fog: vanilla
        boolean fogOn, fogVertical;
        int fogColor;                                                         // −1 — auto (цвет горизонта)
        float fogStart, fogEnd, fogDensity;

        /** Слои запекания те же (меняются только цвета купола/солнца) — пересобирать не нужно. */
        boolean sameBake(Cfg o) {
            return seed == o.seed && pattern.equals(o.pattern) && patColor == o.patColor && patColor2 == o.patColor2
                    && patScale == o.patScale && neb1 == o.neb1 && neb2 == o.neb2 && (nebStrength > 0) == (o.nebStrength > 0)
                    && stars == o.stars && starSize == o.starSize;
        }

        static Cfg parse(Map<String, String> kv) {
            String preset = kv.getOrDefault("preset", "hoka").toLowerCase();
            if (preset.equals("vanilla") || preset.equals("off") || kv.getOrDefault("enabled", "true").equals("false")) return null;
            Map<String, String> m = new HashMap<>(PRESETS.getOrDefault(preset, PRESETS.get("hoka")));
            m.putAll(kv);
            Cfg c = new Cfg();
            c.seed = (int) num(m, "seed", 23);
            c.dayZ = col(m, "day.zenith", 0x2c6bd8); c.dayH = col(m, "day.horizon", 0xa6dcff);
            c.setZ = col(m, "sunset.zenith", 0x3b3a8f); c.setH = col(m, "sunset.horizon", 0xff9a5c);
            c.nightZ = col(m, "night.zenith", 0x050a1e); c.nightH = col(m, "night.horizon", 0x18234f);
            c.glow = col(m, "glow", 0xffcf87);
            c.fogBlend = clamp01((float) num(m, "fog_blend", 0.85));
            c.tilt = (float) num(m, "tilt", 0);
            c.pattern = m.getOrDefault("pattern", "bands").toLowerCase();
            c.patColor = col(m, "pattern_color", 0xffffff);
            c.patColor2 = col(m, "pattern_color2", c.patColor);
            c.patStrength = clamp01((float) num(m, "pattern_strength", 0.16));
            c.patSpeed = (float) num(m, "pattern_speed", 1);
            c.patScale = Math.max(0.2f, (float) num(m, "pattern_scale", 1));
            String[] nc = m.getOrDefault("nebula_colors", "#7a4dff,#27c4ff").split(",");
            c.neb1 = parseCol(nc[0], 0x7a4dff);
            c.neb2 = parseCol(nc.length > 1 ? nc[1] : nc[0], c.neb1);
            String neb = m.getOrDefault("nebula", "0.8");
            c.nebStrength = neb.equals("false") ? 0 : neb.equals("true") ? 0.8f : clamp01((float) num(m, "nebula", 0.8));
            c.stars = (int) Math.max(0, Math.min(20000, num(m, "stars", 2200)));
            c.starSize = Math.max(0.1f, (float) num(m, "star_size", 1));
            c.starDay = clamp01((float) num(m, "star_day", 0));
            c.clouds = !m.getOrDefault("clouds", "true").equals("false");
            c.sunType = m.getOrDefault("sun.type", m.getOrDefault("sun", "cube")).toLowerCase();   // и короткая форма sun: cube
            c.sunSize = Math.max(0.5f, Math.min(40, (float) num(m, "sun.size", 7)));
            c.sunColor = col(m, "sun.color", 0xfff4c8);
            c.sunHalo = col(m, "sun.halo", 0xffc861);
            c.sunSpin = (float) num(m, "sun.spin", 1);
            c.moonType = m.getOrDefault("moon.type", m.getOrDefault("moon", "cube")).toLowerCase();
            c.moonSize = Math.max(0.5f, Math.min(40, (float) num(m, "moon.size", 5)));
            c.moonColor = col(m, "moon.color", 0xdfe8ff);
            c.moonPhases = !m.getOrDefault("moon.phases", "true").equals("false");
            if (c.sunType.equals("vanilla")) c.sunType = "square";               // «как у игры» — плоский квадрат
            if (c.moonType.equals("vanilla")) c.moonType = "square";
            if (c.sunType.equals("false") || c.sunType.equals("off")) c.sunType = "none";   // sun: false / off
            if (c.moonType.equals("false") || c.moonType.equals("off")) c.moonType = "none";
            String fog = m.getOrDefault("fog", "true").trim().toLowerCase();       // короткая форма fog: false / vanilla
            c.fogOn = !(fog.equals("false") || fog.equals("off") || fog.equals("vanilla"))
                    && !m.getOrDefault("fog.enabled", "true").trim().equalsIgnoreCase("false");
            String fc = m.getOrDefault("fog.color", "auto").trim();
            c.fogColor = fc.equalsIgnoreCase("auto") ? -1 : parseCol(fc, -1);
            c.fogEnd = Math.max(0.2f, Math.min(1.5f, (float) num(m, "fog.end", 1.0)));
            c.fogStart = Math.max(0, Math.min(c.fogEnd - 0.02f, (float) num(m, "fog.start", 0.65)));
            c.fogDensity = clamp01((float) num(m, "fog.density", 1.0));
            c.fogVertical = !m.getOrDefault("fog.vertical", "true").trim().equalsIgnoreCase("false");
            return c;
        }

        private static double num(Map<String, String> m, String k, double def) {
            try { return Double.parseDouble(m.getOrDefault(k, "").trim()); } catch (NumberFormatException e) { return def; }
        }

        private static int col(Map<String, String> m, String k, int def) { return parseCol(m.get(k), def); }

        private static int parseCol(String s, int def) {
            if (s == null) return def;
            s = s.trim().replace("#", "").replace("0x", "");
            try { return s.length() == 6 ? Integer.parseInt(s, 16) : def; } catch (NumberFormatException e) { return def; }
        }
    }

    // ───────────────────────── цвет и шум ─────────────────────────

    private static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }

    private static int alpha(float a) { return Math.round(clamp01(a) * 255) << 24; }

    private static float smooth(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    private static int lerp(int a, int b, float t) {
        t = clamp01(t);
        int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    private static int add(int a, int b, float k) {
        int r = Math.min(255, (a >> 16 & 255) + (int) ((b >> 16 & 255) * k));
        int g = Math.min(255, (a >> 8 & 255) + (int) ((b >> 8 & 255) * k));
        int bl = Math.min(255, (a & 255) + (int) ((b & 255) * k));
        return r << 16 | g << 8 | bl;
    }

    private static int scale(int c, float k) {
        int r = Math.min(255, (int) ((c >> 16 & 255) * k)), g = Math.min(255, (int) ((c >> 8 & 255) * k)), b = Math.min(255, (int) ((c & 255) * k));
        return r << 16 | g << 8 | b;
    }

    private static int gray(int c) {
        int l = ((c >> 16 & 255) * 3 + (c >> 8 & 255) * 6 + (c & 255)) / 10;
        return l << 16 | l << 8 | l;
    }

    private static float fbm(float x, float y, float z, int seed, int oct) {
        float s = 0, a = 0.5f;
        for (int o = 0; o < oct; o++) { s += a * noise(x, y, z, seed + o * 1013); x *= 2.03f; y *= 2.03f; z *= 2.03f; a *= 0.5f; }
        return s;
    }

    private static float noise(float x, float y, float z, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        float xf = x - xi, yf = y - yi, zf = z - zi;
        float u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf), w = zf * zf * (3 - 2 * zf);
        float r = 0;
        for (int k = 0; k < 8; k++) {
            int dx = k & 1, dy = k >> 1 & 1, dz = k >> 2;
            float wt = (dx == 1 ? u : 1 - u) * (dy == 1 ? v : 1 - v) * (dz == 1 ? w : 1 - w);
            r += wt * hash(xi + dx, yi + dy, zi + dz, seed);
        }
        return r;
    }

    private static float hash(int x, int y, int z, int seed) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177 + seed * 974634421;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }
}
