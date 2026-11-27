package ink.hoka.odyssey.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.66 Мгновенный отклик на урон (сервер — главный, клиент только рисует раньше). Раньше красная вспышка, звук и
 * сердечки приходили через пинг. Теперь мод сам видит приземление с высоты, касание лавы/огня/магмы и свой удар по
 * существу — и сразу показывает то же, что покажет сервер (падение — тем же расчётом по атрибутам
 * fall_damage_multiplier / safe_fall_distance от модуля survival). Повтор вспышки и звука от сервера пропускается
 * (одно событие на сущность в окне 0.7 с); здоровье сервер присылает своё — если не совпало, встаёт верное число.
 */
public final class HitPredict {

    private HitPredict() {}

    private static final long WINDOW_MS = 700;
    /** id сущности → до какого времени пропустить одно серверное событие урона. */
    private static final ConcurrentHashMap<Integer, Long> SUPPRESS = new ConcurrentHashMap<>();

    private static double fallTop = Double.NaN;
    private static boolean wasLava, wasFire;
    private static long lastMagma;

    /** Серверное событие урона по сущности: true — повтор предсказанного, не показывать. */
    public static boolean suppress(int entityId) {
        Long until = SUPPRESS.remove(entityId);
        return until != null && until >= System.currentTimeMillis();
    }

    private static void mark(Entity e) { SUPPRESS.put(e.getId(), System.currentTimeMillis() + WINDOW_MS); }

    /** Свой удар по существу (MultiPlayerGameMode.attack): цель сразу мигает и звучит. */
    public static void attack(LocalPlayer p, Entity target) {
        if (!TallWorld.net || p == null || p.isSpectator() || !(target instanceof LivingEntity le) || !le.isAlive()) return;
        if (le.hurtTime > 0) return;                                          // ещё мигает — сервер урон, скорее всего, не даст
        le.handleDamageEvent(p.damageSources().playerAttack(p));
        mark(le);
    }

    /** Каждый тик клиента: падение и опасные блоки у своего игрока. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!TallWorld.net || p == null || mc.level == null) return;
        boolean live = !p.isCreative() && !p.isSpectator() && p.isAlive();
        boolean ground = p.onGround();
        BlockState feet = mc.level.getBlockState(p.blockPosition());
        boolean water = p.isInWater() || feet.getFluidState().is(FluidTags.WATER);
        if (!live || p.getAbilities().flying || p.isPassenger() || water || p.onClimbable() || feet.is(Blocks.COBWEB)) fallTop = Double.NaN;
        else if (!ground) { if (Double.isNaN(fallTop) || p.getY() > fallTop) fallTop = p.getY(); }
        else if (!Double.isNaN(fallTop)) {
            double dist = fallTop - p.getY();
            double mul = p.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), free = p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
            BlockState below = mc.level.getBlockState(BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ()));
            if (below.is(Blocks.HAY_BLOCK)) mul *= 0.2;
            if (below.is(Blocks.SLIME_BLOCK) || below.is(BlockTags.BEDS)) mul = 0;
            float dmg = (float) Math.ceil((dist - free) * mul);
            if (dmg > 0) hit(p, p.damageSources().fall(), dmg);
            fallTop = Double.NaN;
        }
        if (!live) { wasLava = wasFire = false; return; }
        boolean lava = p.isInLava();
        if (lava && !wasLava) hit(p, p.damageSources().lava(), 4);
        wasLava = lava;
        boolean fire = feet.is(Blocks.FIRE) || feet.is(Blocks.SOUL_FIRE);
        if (fire && !wasFire && !lava) hit(p, p.damageSources().inFire(), 1);
        wasFire = fire;
        if (ground && !p.isShiftKeyDown() && System.currentTimeMillis() - lastMagma > 1000
                && mc.level.getBlockState(BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ())).is(Blocks.MAGMA_BLOCK)) {
            lastMagma = System.currentTimeMillis();
            hit(p, p.damageSources().hotFloor(), 1);
        }
    }

    /** Показать урон сейчас: вспышка, звук, наклон камеры, сердечки (смерть не предсказываем — её решит сервер). */
    private static void hit(LocalPlayer p, DamageSource src, float dmg) {
        p.handleDamageEvent(src);
        float h = p.getHealth() - dmg;
        if (h > 0) p.setHealth(h);
        mark(p);
    }
}
