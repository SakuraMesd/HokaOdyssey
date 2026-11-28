package ink.hoka.odyssey.client.jetpack;

import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import ink.hoka.odyssey.client.api.ModelHandle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Реактивный ранец (клиент). Модель {@value #MODEL} (файл с сервера) рисуется на спине у каждого игрока, у которого в
 * слоте нагрудника ранец (метка hoka_item = jetpack или hoka_jetpack = pack): по повороту тела, с наклоном в приседе;
 * себе от первого лица — не рисуется. Анимация fly и пламя из сопел — у тех, кто сейчас летит (сервер рассылает).
 * <p>
 * Полёт своего игрока: пробел в воздухе — тяга вверх и разгон по WASD, Shift в воздухе — плавный спуск. Только при
 * топливе (сервер шлёт запас), не в творческом полёте, не верхом и не в невесомости (гравитация игрока ≈ 0 — там своё
 * управление модуля gravity). Состояние шлём серверу при смене: он списывает топливо.
 */
public final class JetpackClientModule implements ClientModule {

    static final String MODEL = "jetpack/jetpack.bbmodel";
    /** Сопла (пиксели модели: x — вбок, y — вверх от ног, z — назад). */
    private static final float[][] NOZZLES = {{7.15f, 12.6f, 3.4f}, {-7.15f, 12.6f, 3.4f}};

    private ClientContext ctx;
    private float upAccel = 0.13f, maxUp = 0.42f, sideAccel = 0.035f, maxSide = 0.55f, descent = 0.16f;
    private int fuel, capacity = 1;
    private boolean wornByServer;
    private long fuelAt;
    /** Кто летит: id сущности → 1 тяга, 2 спуск. */
    private final Map<Integer, Byte> flying = new HashMap<>();
    /** Модели ранцев: id сущности → модель. */
    private final Map<Integer, ModelHandle> models = new HashMap<>();
    private final Map<Integer, Byte> shownAnim = new HashMap<>();
    private final Map<CustomData, Boolean> marks = new WeakHashMap<>();
    private byte myState;
    private int ticks;

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        ctx.onMessage(this::onMessage);
        ctx.onTick(this::tick);
        ctx.onLocalTravel(this::travel);
        ctx.onHud(this::hud);
    }

    // ───────────────────────── сервер ─────────────────────────

    private void onMessage(byte[] b) {
        try (var in = new DataInputStream(new ByteArrayInputStream(b))) {
            switch (in.readByte()) {
                case 0 -> { upAccel = in.readFloat(); maxUp = in.readFloat(); sideAccel = in.readFloat(); maxSide = in.readFloat(); descent = in.readFloat(); }
                case 1 -> { fuel = in.readInt(); capacity = Math.max(1, in.readInt()); wornByServer = in.readBoolean(); fuelAt = System.currentTimeMillis(); }
                case 2 -> {
                    int id = in.readInt();
                    byte s = in.readByte();
                    if (s == 0) flying.remove(id); else flying.put(id, s);
                }
                default -> { }
            }
        } catch (IOException e) {
            ctx.logger().warn("jetpack: битое сообщение сервера: {}", e.toString());
        }
    }

    // ───────────────────────── ранец на игроках ─────────────────────────

    boolean wears(Player p) {
        ItemStack it = p.getItemBySlot(EquipmentSlot.CHEST);
        if (it.isEmpty()) return false;
        CustomData cd = it.get(DataComponents.CUSTOM_DATA);
        if (cd == null || cd.isEmpty()) return false;
        return marks.computeIfAbsent(cd, c -> {
            var t = c.copyTag();
            return "jetpack".equals(t.getStringOr("hoka_item", "")) || "pack".equals(t.getStringOr("hoka_jetpack", ""));
        });
    }

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { clearModels(); flying.clear(); return; }
        ticks++;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (AbstractClientPlayer p : mc.level.players()) {
            if (!wears(p) || p.isSpectator()) continue;
            int id = p.getId();
            seen.add(id);
            ModelHandle h = models.computeIfAbsent(id, k -> ctx.model(MODEL).pose((pt, pos, rot) -> place(k, pt, pos, rot)));
            byte s = flying.getOrDefault(id, (byte) 0);
            if (shownAnim.getOrDefault(id, (byte) -1) != s) {
                if (s != 0) h.play("fly", s == 1 ? 1f : 0.6f); else h.stop();
                shownAnim.put(id, s);
            }
            if (s != 0) effects(mc, p, s);
        }
        models.entrySet().removeIf(e -> {
            if (seen.contains(e.getKey())) return false;
            e.getValue().remove();
            shownAnim.remove(e.getKey());
            return true;
        });
        flying.keySet().removeIf(id -> mc.level.getEntity(id) == null);
    }

    private void clearModels() {
        for (ModelHandle h : models.values()) h.remove();
        models.clear();
        shownAnim.clear();
    }

    /** Поза модели ранца у игрока id на этот кадр: по телу, присед — наклон корпуса, как у игры. */
    private boolean place(int id, float pt, Vector3d pos, Quaternionf rot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(id) instanceof Player p) || p.isRemoved() || p.isInvisible()) return false;
        if (p == mc.player && mc.options.getCameraType().isFirstPerson()) return false;   // себе от первого лица — нет
        Vec3 feet = p.getPosition(pt);
        float body = Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot);
        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(180 - body));
        if (p.isCrouching() && !p.getAbilities().flying) {
            // присед игры: корпус наклонён на 0.5 рад вокруг шеи, шея ниже на 3.2 px, вся модель ниже на 0.125 блока
            Quaternionf lean = new Quaternionf().rotationX(-0.5f);
            Vector3f p0 = new Vector3f(0, 1.5f, 0), p1 = new Vector3f(0, 1.5f - 3.2f / 16f, 0);
            Vector3f off = new Vector3f(p0).rotate(lean).negate().add(p1);       // P1 − lean·P0
            yaw.transform(off);
            pos.set(feet.x + off.x, feet.y - 0.125 + off.y, feet.z + off.z);
            rot.set(yaw).mul(lean);
        } else {
            pos.set(feet.x, feet.y, feet.z);
            rot.set(yaw);
        }
        return true;
    }

    /** Точка модели (пиксели) у игрока → мир (для пламени), без приседа. */
    private static Vec3 modelPoint(Player p, float[] px) {
        Quaternionf yaw = new Quaternionf().rotationY((float) Math.toRadians(180 - p.yBodyRot));
        Vector3f v = yaw.transform(new Vector3f(px[0] / 16f, px[1] / 16f - (p.isCrouching() ? 0.2f : 0), px[2] / 16f));
        return p.position().add(v.x, v.y, v.z);
    }

    private void effects(Minecraft mc, Player p, byte s) {
        var r = mc.level.random;
        for (float[] n : NOZZLES) {
            Vec3 at = modelPoint(p, n);
            Vec3 v = p.getDeltaMovement();
            if (s == 1) {
                mc.level.addParticle(ParticleTypes.FLAME, at.x, at.y, at.z, v.x * 0.5 + (r.nextDouble() - 0.5) * 0.03, -0.18 - r.nextDouble() * 0.1, v.z * 0.5 + (r.nextDouble() - 0.5) * 0.03);
                if (r.nextInt(3) == 0) mc.level.addParticle(ParticleTypes.SMOKE, at.x, at.y - 0.1, at.z, 0, -0.05, 0);
            } else if (r.nextInt(2) == 0) {
                mc.level.addParticle(ParticleTypes.SMALL_FLAME, at.x, at.y, at.z, 0, -0.08, 0);
            }
        }
        if (ticks % (s == 1 ? 4 : 8) == 0) {
            Vec3 c = p.position();
            mc.level.playLocalSound(c.x, c.y + 1, c.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS,
                    s == 1 ? 0.18f : 0.08f, 0.55f + r.nextFloat() * 0.15f, false);
        }
    }

    // ───────────────────────── полёт своего игрока ─────────────────────────

    private void travel(LocalPlayer p) {
        byte s = 0;
        boolean can = fuel > 0 && wears(p) && !p.getAbilities().flying && !p.isPassenger() && !p.isSpectator()
                && p.getAttributeValue(Attributes.GRAVITY) > 0.01 && !p.isFallFlying();
        if (can && !p.onGround()) {
            var k = p.input.keyPresses;
            Vec3 v = p.getDeltaMovement();
            if (k.jump()) {
                s = 1;
                double vy = Math.min(v.y + upAccel, maxUp);
                double vx = v.x, vz = v.z;
                var mv = p.input.getMoveVector();                               // x — влево, y — вперёд
                if (mv.lengthSquared() > 1e-4) {
                    float yr = p.getYRot() * Mth.DEG_TO_RAD;
                    double len = Math.sqrt(mv.lengthSquared());
                    double sx = mv.x / len, fw = mv.y / len;
                    double dx = sx * Mth.cos(yr) - fw * Mth.sin(yr), dz = fw * Mth.cos(yr) + sx * Mth.sin(yr);
                    double before = Math.hypot(vx, vz);
                    if (before < maxSide) {
                        vx += dx * sideAccel; vz += dz * sideAccel;
                        double after = Math.hypot(vx, vz);
                        if (after > maxSide) { vx *= maxSide / after; vz *= maxSide / after; }
                    }
                }
                p.setDeltaMovement(vx, vy, vz);
            } else if (k.shift() && v.y < -descent) {
                s = 2;
                p.setDeltaMovement(v.x, -descent, v.z);
            }
            if (s != 0) p.resetFallDistance();
        }
        if (s != myState) {
            myState = s;
            ctx.send(new byte[]{1, s});
        }
    }

    // ───────────────────────── HUD ─────────────────────────

    private void hud(net.minecraft.client.gui.GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || !wears(mc.player)) return;
        if (!wornByServer && System.currentTimeMillis() - fuelAt > 3000) return;      // сервер ещё не подтвердил
        var font = mc.font;
        float f = Math.clamp(fuel / (float) capacity, 0f, 1f);
        int w = 62, h = 18;
        int x = g.guiWidth() / 2 + 98, y = g.guiHeight() - h - 3;
        boolean low = f < 0.25f;
        boolean blink = fuel <= 0 && (System.currentTimeMillis() / 400) % 2 == 0;
        int accent = fuel <= 0 ? 0xFFFF5A4A : low ? 0xFFFFC46B : 0xFF8FD8FF;
        g.fill(x, y, x + w, y + h, 0xA005080F);                                 // панель
        g.fill(x, y, x + w, y + 1, 0x60D9E2EE);
        g.fill(x, y + h - 1, x + w, y + h, 0x60D9E2EE);
        g.drawString(font, "РАНЕЦ", x + 4, y + 2, blink ? 0xFFFF5A4A : 0xFFD9E2EE, false);
        String pct = Math.round(f * 100) + "%";
        g.drawString(font, pct, x + w - 4 - font.width(pct), y + 2, accent, false);
        int bx = x + 4, by = y + 12, bw = w - 8;
        g.fill(bx, by, bx + bw, by + 3, 0x50D9E2EE);                            // шкала
        g.fill(bx, by, bx + Math.round(bw * f), by + 3, accent);
        if (myState != 0) g.fill(bx + Math.round(bw * f) - 1, by - 1, bx + Math.round(bw * f), by + 4, 0xFFFFFFFF);
    }
}
