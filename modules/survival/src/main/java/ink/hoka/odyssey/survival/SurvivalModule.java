package ink.hoka.odyssey.survival;

import ink.hoka.odyssey.api.OdysseyModule;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.ItemEntity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.entity.EntityDamageEvent;
import net.minestom.server.event.item.PlayerFinishItemUseEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.ConsumeEffect;
import net.minestom.server.potion.Potion;
import net.minestom.server.registry.RegistryKey;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Выживание на Minestom (сам он здоровье только хранит: урона от мира, голода, еды, смерти с выпадением вещей и
 * сохранения игроков в нём нет). Всё — как в ванили, числа в config.yml:
 * <ul>
 *   <li>урон: падение (по высоте падения, с множителем карты — невесомость), лава, огонь, горение, удушье в блоке,
 *       магма, пустота; бой — сила удара по оружию (атрибут attack_damage), откат удара, крит в падении, отбрасывание;
 *       броня по ванильной формуле; неуязвимость после удара 10 тиков;</li>
 *   <li>голод: усталость от бега, прыжков, ударов, ломания, лечения; лечение при сытости 18+ (быстро при полной и
 *       насыщении), урон от голода при 0;</li>
 *   <li>еда: компонент food предмета (сытость, насыщение), эффекты (золотое яблоко), остаток (миска);</li>
 *   <li>смерть: вещи выпадают (или keep-inventory), возрождение на точке появления этой карты;</li>
 *   <li>сохранение: инвентарь, здоровье, голод, опыт, место — при выходе, раз в N минут и при выключении.</li>
 * </ul>
 * Креатив и наблюдатель урона не получают (как в ванили). Воздух в космосе — модуль oxygen.
 */
public final class SurvivalModule extends OdysseyModule {

    /** Состояние игрока между тиками. */
    private static final class St {
        double fallTop = Double.NaN;          // высшая точка текущего падения
        double lastY = Double.NaN, lastX, lastZ;
        boolean lastGround = true;
        float exhaustion;
        int foodTimer;
        long tick;                             // счётчик тиков игрока
        long lastHurtTick = -1000;
        float lastHurt;
        long lastAttackTick = -1000;
        Instance lastInst;
        boolean loaded;                         // данные применены
        String gameMode;                        // выбран /gm (null — по умолчанию)
    }

    private final Map<UUID, St> states = new ConcurrentHashMap<>();
    private Path playersDir;

    // настройки
    private GameMode defaultGm;
    private boolean pvp, keepInv, hunger, fallOn, blockDrops, restorePos;
    private float starveTo, lava, inFire, suffocation, magma, burning, voidDmg;
    private int fallFree, voidBelow, iTicks;

    private static final Set<RegistryKey<DamageType>> BYPASS_ARMOR = Set.of(DamageType.FALL, DamageType.DROWN, DamageType.STARVE,
            DamageType.IN_WALL, DamageType.OUT_OF_WORLD, DamageType.GENERIC_KILL, DamageType.ON_FIRE);

    @Override
    protected void onEnable() {
        playersDir = dataFolder().resolve("players");
        readConfig();
        events().addListener(PlayerSpawnEvent.class, this::onSpawn);
        events().addListener(PlayerDisconnectEvent.class, e -> {
            St s = states.remove(e.getPlayer().getUuid());
            if (s != null && s.loaded) save(e.getPlayer(), s);
        });
        events().addListener(EntityDamageEvent.class, this::onDamage);
        events().addListener(EntityAttackEvent.class, this::onAttack);
        events().addListener(PlayerFinishItemUseEvent.class, this::onEat);
        events().addListener(PlayerDeathEvent.class, this::onDeath);
        events().addListener(PlayerRespawnEvent.class, this::onRespawn);
        events().addListener(PlayerBlockBreakEvent.class, this::onBreak);
        scheduler().repeat(this::tickAll, 1, 1);
        int every = Math.max(1, config().getInt("save.interval-minutes", 5)) * 60 * 20;
        scheduler().repeat(this::saveAll, every, every);
        registerCommand(gmCommand());
        // модуль включили на ходу — уже вошедшим применить
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) join(p, false);
        logger().info("выживание включено: режим по умолчанию {}, PvP {}, вещи при смерти {}", defaultGm, pvp ? "вкл" : "выкл",
                keepInv ? "остаются" : "выпадают");
    }

    @Override
    protected void onDisable() {
        saveAll();
    }

    private void readConfig() {
        var c = config();
        defaultGm = PlayerData.gameMode(c.getString("default-gamemode", "survival"));
        if (defaultGm == null) defaultGm = GameMode.SURVIVAL;
        pvp = c.getBoolean("pvp", true);
        keepInv = c.getBoolean("keep-inventory", false);
        hunger = c.getBoolean("hunger.enabled", true);
        starveTo = (float) c.getDouble("hunger.starve-to", 1.0);
        fallOn = c.getBoolean("damage.fall", true);
        fallFree = c.getInt("damage.fall-free-blocks", 3);
        lava = (float) c.getDouble("damage.lava", 4);
        inFire = (float) c.getDouble("damage.in-fire", 1);
        suffocation = (float) c.getDouble("damage.suffocation", 1);
        magma = (float) c.getDouble("damage.magma", 1);
        burning = (float) c.getDouble("damage.burning", 1);
        voidBelow = c.getInt("damage.void-below", 64);
        voidDmg = (float) c.getDouble("damage.void", 4);
        iTicks = c.getInt("damage.invulnerable-ticks", 10);
        blockDrops = c.getBoolean("block-drops", true);
        restorePos = c.getBoolean("save.restore-position", true);
    }

    private St st(Player p) { return states.computeIfAbsent(p.getUuid(), k -> new St()); }

    private boolean affected(Player p) {
        GameMode g = p.getGameMode();
        return (g == GameMode.SURVIVAL || g == GameMode.ADVENTURE) && !p.isDead();
    }

    // ───────────────────────── вход, выход, сохранение ─────────────────────────

    private void onSpawn(PlayerSpawnEvent e) {
        Player p = e.getPlayer();
        St s = st(p);
        s.lastInst = p.getInstance();
        s.fallTop = Double.NaN;
        s.lastY = Double.NaN;
        if (e.isFirstSpawn() && !s.loaded) join(p, true);
    }

    /** Первый вход (или модуль включили на ходу): данные с диска, режим, место. */
    private void join(Player p, boolean move) {
        St s = st(p);
        if (s.loaded) return;
        s.loaded = true;
        CompoundBinaryTag t = PlayerData.load(playersDir, p.getUuid(), logger());
        if (t != null) {
            PlayerData.apply(p, t);
            s.exhaustion = t.getFloat("exhaustion", 0);
            String gm = t.getString("gamemode", "");
            s.gameMode = gm.isEmpty() ? null : gm;
        }
        GameMode gm = s.gameMode != null ? PlayerData.gameMode(s.gameMode) : null;
        p.setGameMode(gm != null ? gm : defaultGm);
        if (move && restorePos && t != null && t.keySet().contains("map")) {
            String map = t.getString("map");
            Pos at = new Pos(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), t.getFloat("yaw"), t.getFloat("pitch"));
            // после появления в мире: на свою карту и место (через тик — когда вход закончен)
            scheduler().later(() -> {
                if (!p.isOnline()) return;
                server().map(map).ifPresent(inst -> {
                    s.fallTop = Double.NaN;
                    s.lastY = Double.NaN;
                    server().transfer(p, inst, at);
                });
            }, 2);
        }
    }

    private void save(Player p, St s) {
        String map = p.getInstance() == null ? null : server().mapName(p.getInstance()).orElse(null);
        PlayerData.save(playersDir, p, map, s.gameMode, s.exhaustion, logger());
    }

    private void saveAll() {
        int n = 0;
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            St s = states.get(p.getUuid());
            if (s != null && s.loaded) { save(p, s); n++; }
        }
        if (n > 0) logger().debug("сохранено игроков: {}", n);
    }

    // ───────────────────────── тик игрока ─────────────────────────

    private void tickAll() {
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            try { tick(p); } catch (Throwable e) { logger().warn("тик выживания {}: {}", p.getUsername(), e.toString()); }
        }
    }

    private void tick(Player p) {
        Instance inst = p.getInstance();
        if (inst == null) return;
        St s = st(p);
        s.tick++;
        Pos pos = p.getPosition();
        if (s.lastInst != inst) { s.lastInst = inst; s.fallTop = Double.NaN; s.lastY = Double.NaN; }
        boolean live = affected(p);
        // телепорт (скачок за тик) — не падение и не бег
        boolean jump = !Double.isNaN(s.lastY) && (Math.abs(pos.y() - s.lastY) > 12 || Math.abs(pos.x() - s.lastX) + Math.abs(pos.z() - s.lastZ) > 12);
        if (jump) s.fallTop = Double.NaN;
        Block feet = inst.getBlock(pos), head = inst.getBlock(pos.add(0, p.getEyeHeight(), 0)), below = inst.getBlock(pos.add(0, -0.2, 0));
        boolean inWater = feet.compare(Block.WATER) || head.compare(Block.WATER) || "true".equals(feet.getProperty("waterlogged"));
        boolean inLava = feet.compare(Block.LAVA) || head.compare(Block.LAVA);
        boolean climbing = feet.compare(Block.LADDER) || feet.compare(Block.VINE) || feet.compare(Block.SCAFFOLDING)
                || feet.key().asString().contains("vines");
        boolean ground = p.isOnGround();

        // ── падение ──
        if (!live || p.isFlying() || p.getVehicle() != null || inWater || climbing || feet.compare(Block.COBWEB)) s.fallTop = Double.NaN;
        else if (!ground) { if (Double.isNaN(s.fallTop) || pos.y() > s.fallTop) s.fallTop = pos.y(); }
        else {
            if (!Double.isNaN(s.fallTop) && fallOn) {
                double dist = s.fallTop - pos.y();
                float mul = fallMul(inst) * (float) p.getAttributeValue(Attribute.FALL_DAMAGE_MULTIPLIER);
                double free = Math.max(fallFree, p.getAttributeValue(Attribute.SAFE_FALL_DISTANCE));
                if (below.compare(Block.HAY_BLOCK)) mul *= 0.2f;
                if (below.compare(Block.SLIME_BLOCK) || below.key().asString().endsWith("_bed")) mul = 0;
                float dmg = (float) Math.ceil((dist - free) * mul);
                if (dmg > 0) p.damage(new Damage(DamageType.FALL, null, null, null, dmg));
            }
            s.fallTop = Double.NaN;
        }
        // прыжок (с земли вверх) — усталость
        if (live && hunger && s.lastGround && !ground && !Double.isNaN(s.lastY) && pos.y() > s.lastY && !jump && !inWater)
            s.exhaustion += p.isSprinting() ? 0.2f : 0.05f;
        // бег — усталость за пройденное
        if (live && hunger && !jump && !Double.isNaN(s.lastY) && p.isSprinting()) {
            double d = Math.hypot(pos.x() - s.lastX, pos.z() - s.lastZ);
            s.exhaustion += (float) (d * 0.1);
        }
        s.lastGround = ground;
        s.lastY = pos.y(); s.lastX = pos.x(); s.lastZ = pos.z();
        if (!live) return;

        // ── мир: лава, огонь, магма, удушье, пустота ──
        if (inLava) {
            p.setFireTicks(Math.max(p.getFireTicks(), 300));
            if (s.tick % 10 == 0) p.damage(new Damage(DamageType.LAVA, null, null, null, lava));
        } else if (feet.compare(Block.FIRE) || feet.compare(Block.SOUL_FIRE)) {
            p.setFireTicks(Math.max(p.getFireTicks(), 160));
            if (s.tick % 10 == 0) p.damage(new Damage(DamageType.IN_FIRE, null, null, null, inFire));
        }
        if (inWater && p.getFireTicks() > 0) p.setFireTicks(0);
        if (p.getFireTicks() > 0 && !inLava && s.tick % 20 == 0) p.damage(new Damage(DamageType.ON_FIRE, null, null, null, burning));
        if (ground && below.compare(Block.MAGMA_BLOCK) && !p.isSneaking() && s.tick % 20 == 0)
            p.damage(new Damage(DamageType.HOT_FLOOR, null, null, null, magma));
        if (head.isSolid() && head.registry().occludes() && s.tick % 10 == 0)
            p.damage(new Damage(DamageType.IN_WALL, null, null, null, suffocation));
        Integer bottom = mapBottom(inst);
        if (bottom != null && pos.y() < bottom - voidBelow && s.tick % 10 == 0)
            p.damage(new Damage(DamageType.OUT_OF_WORLD, null, null, null, voidDmg));

        // ── голод и лечение ──
        if (!hunger) return;
        while (s.exhaustion >= 4f) {
            s.exhaustion -= 4f;
            if (p.getFoodSaturation() > 0) p.setFoodSaturation(Math.max(0, p.getFoodSaturation() - 1));
            else p.setFood(Math.max(0, p.getFood() - 1));
        }
        float max = (float) p.getAttributeValue(Attribute.MAX_HEALTH);
        int food = p.getFood();
        float sat = p.getFoodSaturation();
        if (sat > 0 && food >= 20 && p.getHealth() < max) {
            if (++s.foodTimer >= 10) {
                float k = Math.min(sat, 6f);
                p.setHealth(Math.min(max, p.getHealth() + k / 6f));
                s.exhaustion += k;
                s.foodTimer = 0;
            }
        } else if (food >= 18 && p.getHealth() < max) {
            if (++s.foodTimer >= 80) {
                p.setHealth(Math.min(max, p.getHealth() + 1));
                s.exhaustion += 6f;
                s.foodTimer = 0;
            }
        } else if (food <= 0) {
            if (++s.foodTimer >= 80) {
                if (p.getHealth() > starveTo) p.damage(new Damage(DamageType.STARVE, null, null, null, 1));
                s.foodTimer = 0;
            }
        } else s.foodTimer = 0;
    }

    private final Map<Instance, Float> fallMulCache = new ConcurrentHashMap<>();
    private final Map<Instance, Integer> bottomCache = new ConcurrentHashMap<>();

    /** Множитель урона от падения карты (config maps.&lt;карта&gt;.fall). */
    private float fallMul(Instance inst) {
        return fallMulCache.computeIfAbsent(inst, i -> server().mapName(i)
                .map(n -> (float) config().getDouble("maps." + n + ".fall", 1.0)).orElse(1f));
    }

    /** Низ карты (gen_min_y из map.yml) или null. */
    private Integer mapBottom(Instance inst) {
        Integer v = bottomCache.get(inst);
        if (v != null) return v == Integer.MIN_VALUE ? null : v;
        int b = server().mapName(inst).flatMap(n -> server().mapConfig(n))
                .map(c -> c.contains("gen_min_y") ? c.getInt("gen_min_y", Integer.MIN_VALUE) : Integer.MIN_VALUE).orElse(Integer.MIN_VALUE);
        if (b == Integer.MIN_VALUE) b = inst.getCachedDimensionType().minY();
        bottomCache.put(inst, b);
        return b;
    }

    // ───────────────────────── урон и бой ─────────────────────────

    private void onDamage(EntityDamageEvent e) {
        LivingEntity v = e.getEntity();
        Damage d = e.getDamage();
        if (v instanceof Player p) {
            GameMode g = p.getGameMode();
            if ((g == GameMode.CREATIVE || g == GameMode.SPECTATOR) && !d.getType().equals(DamageType.OUT_OF_WORLD)) { e.setCancelled(true); return; }
        }
        St s = v instanceof Player p ? st(p) : null;
        float amount = d.getAmount();
        // неуязвимость после удара: сильнее прошлого — только разница
        if (s != null && iTicks > 0 && s.tick - s.lastHurtTick < iTicks) {
            if (amount <= s.lastHurt) { e.setCancelled(true); return; }
            float diff = amount - s.lastHurt;
            s.lastHurt = amount;
            amount = diff;
            e.setAnimation(false);
        } else if (s != null) {
            s.lastHurtTick = s.tick;
            s.lastHurt = amount;
        }
        // броня (ванильная формула): очки брони и прочность снижают урон, кроме урона «сквозь броню»
        if (!BYPASS_ARMOR.contains(d.getType())) {
            double armor = v.getAttributeValue(Attribute.ARMOR), tough = v.getAttributeValue(Attribute.ARMOR_TOUGHNESS);
            if (armor > 0) {
                double eff = Math.min(20, Math.max(armor / 5, armor - 4 * amount / (tough + 8)));
                amount = (float) (amount * (1 - eff / 25));
                if (v instanceof Player p) wearArmor(p, d.getAmount());
            }
        }
        d.setAmount(Math.max(0, amount));
        if (s != null && hunger) s.exhaustion += 0.1f;
    }

    private void onAttack(EntityAttackEvent e) {
        if (!(e.getEntity() instanceof Player a) || !(e.getTarget() instanceof LivingEntity t)) return;
        if (a.getGameMode() == GameMode.SPECTATOR || t.isDead()) return;
        if (t instanceof Player tp && (!pvp || tp.getGameMode() == GameMode.CREATIVE || tp.getGameMode() == GameMode.SPECTATOR)) return;
        St s = st(a);
        // откат удара: доля силы по времени с прошлого удара (скорость атаки — атрибут, у кулака 4)
        double speed = Math.max(0.1, a.getAttributeValue(Attribute.ATTACK_SPEED));
        double f = Math.min(1, (s.tick - s.lastAttackTick + 0.5) / (20 / speed));
        s.lastAttackTick = s.tick;
        double base = a.getAttributeValue(Attribute.ATTACK_DAMAGE);
        double dmg = base * (0.2 + f * f * 0.8);
        boolean crit = f > 0.9 && !a.isOnGround() && !Double.isNaN(s.fallTop) && a.getPosition().y() < s.fallTop && !a.isSprinting();
        if (crit) dmg *= 1.5;
        if (dmg <= 0) return;
        if (t.damage(Damage.fromPlayer(a, (float) dmg))) {
            double yaw = Math.toRadians(a.getPosition().yaw());
            float kb = 0.4f + (a.isSprinting() && f > 0.9 ? 0.5f : 0f);
            t.takeKnockback(kb, Math.sin(yaw), -Math.cos(yaw));
            if (a.getGameMode() != GameMode.CREATIVE) wearHand(a, 1);
        }
        if (hunger) s.exhaustion += 0.1f;
    }

    /** Износ оружия/инструмента в руке. */
    private void wearHand(Player p, int n) {
        ItemStack it = p.getItemInMainHand();
        ItemStack w = wear(it, n);
        if (w != it) p.setItemInMainHand(w);
    }

    /** Износ брони: по 1 за каждые 4 урона (минимум 1), как в ванили. */
    private void wearArmor(Player p, float dmg) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        int n = Math.max(1, (int) (dmg / 4));
        for (EquipmentSlot sl : new EquipmentSlot[]{EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS}) {
            ItemStack it = p.getEquipment(sl);
            ItemStack w = wear(it, n);
            if (w != it) p.setEquipment(sl, w);
        }
    }

    /** Предмет с износом n (сломался — пусто); без прочности — тот же. */
    static ItemStack wear(ItemStack it, int n) {
        if (it.isAir()) return it;
        Integer max = it.get(DataComponents.MAX_DAMAGE);
        if (max == null || max <= 0 || it.has(DataComponents.UNBREAKABLE)) return it;
        int dmg = it.get(DataComponents.DAMAGE, 0) + n;
        if (dmg >= max) return ItemStack.AIR;
        return it.with(DataComponents.DAMAGE, dmg);
    }

    // ───────────────────────── еда ─────────────────────────

    private void onEat(PlayerFinishItemUseEvent e) {
        Player p = e.getPlayer();
        ItemStack it = e.getItemStack();
        var food = it.get(DataComponents.FOOD);
        var cons = it.get(DataComponents.CONSUMABLE);
        if (food == null && cons == null) return;
        if (food != null) {
            int f = Math.min(20, p.getFood() + food.nutrition());
            p.setFood(f);
            p.setFoodSaturation(Math.min(f, p.getFoodSaturation() + food.nutrition() * food.saturationModifier() * 2f));
        }
        if (cons != null) for (ConsumeEffect ef : cons.effects()) {
            if (ef instanceof ConsumeEffect.ApplyEffects ae && ThreadLocalRandom.current().nextFloat() < ae.probability())
                for (var pe : ae.effects()) p.addEffect(new Potion(pe.id(), pe.amplifier(), pe.duration()));
            else if (ef instanceof ConsumeEffect.ClearAllEffects) p.clearEffects();
        }
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack rest = it.amount() > 1 ? it.withAmount(it.amount() - 1) : ItemStack.AIR;
        ItemStack rem = it.get(DataComponents.USE_REMAINDER);
        p.setItemInHand(e.getHand(), rest.isAir() && rem != null ? rem : rest);
        if (!rest.isAir() && rem != null && !p.getInventory().addItemStack(rem)) drop(p.getInstance(), p.getPosition().add(0, 1, 0), rem, 10);
    }

    // ───────────────────────── ломание блоков ─────────────────────────

    private void onBreak(PlayerBlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!affected(p)) return;
        St s = st(p);
        if (hunger) s.exhaustion += 0.005f;
        Block b = e.getBlock();
        ItemStack tool = p.getItemInMainHand();
        if (b.registry().hardness() > 0 && tool.has(DataComponents.MAX_DAMAGE)) {
            boolean weapon = tool.has(DataComponents.WEAPON) && !tool.has(DataComponents.TOOL);
            wearHand(p, weapon ? 2 : 1);
        }
        if (!blockDrops || e.isCancelled()) return;
        var pos = e.getBlockPosition();
        Instance inst = p.getInstance();
        scheduler().run(() -> {                                                // после ломания (другие модули могли отменить)
            if (inst == null || !inst.getBlock(pos).isAir() && !inst.getBlock(pos).isLiquid()) return;
            for (ItemStack d : Drops.of(b, tool)) drop(inst, new Pos(pos.blockX() + 0.5, pos.blockY() + 0.3, pos.blockZ() + 0.5), d, 10);
        });
    }

    private static void drop(Instance inst, Pos at, ItemStack it, int pickupTicks) {
        if (inst == null || it.isAir()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ItemEntity e = new ItemEntity(it);
        e.setPickupDelay(Duration.ofMillis(pickupTicks * 50L));
        e.setVelocity(new Vec((r.nextDouble() - 0.5) * 2, 3 + r.nextDouble() * 1.5, (r.nextDouble() - 0.5) * 2));
        e.setInstance(inst, at);
    }

    // ───────────────────────── смерть и возрождение ─────────────────────────

    private void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        St s = st(p);
        s.fallTop = Double.NaN;
        s.exhaustion = 0;
        if (keepInv || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        Instance inst = p.getInstance();
        Pos at = p.getPosition().add(0, 0.5, 0);
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItemStack(i);
            if (!it.isAir()) drop(inst, at, it, 40);
        }
        inv.clear();
        p.setLevel(0);
        p.setExp(0);
    }

    private void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Instance inst = p.getInstance();
        if (inst != null) server().mapName(inst).flatMap(n -> server().mapSpawn(n)).ifPresent(e::setRespawnPosition);
        p.setFood(20);
        p.setFoodSaturation(5);
        p.setFireTicks(0);
        St s = st(p);
        s.fallTop = Double.NaN;
        s.lastY = Double.NaN;
        s.exhaustion = 0;
        s.foodTimer = 0;
    }

    // ───────────────────────── /gm ─────────────────────────

    private Command gmCommand() {
        Command c = new Command("gm", "gamemode");
        c.setCondition((sender, cmd) -> !(sender instanceof Player p) || server().isOp(p));
        var mode = ArgumentType.Word("режим").from("survival", "creative", "adventure", "spectator", "0", "1", "2", "3", "s", "c", "a", "sp");
        var who = ArgumentType.Word("игрок").setSuggestionCallback((sender, ctx, sug) -> {
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers())
                sug.addEntry(new net.minestom.server.command.builder.suggestion.SuggestionEntry(p.getUsername()));
        });
        c.setDefaultExecutor((sender, ctx) -> sender.sendMessage(Component.text("/gm <survival|creative|adventure|spectator> [игрок]", NamedTextColor.YELLOW)));
        c.addSyntax((sender, ctx) -> {
            if (sender instanceof Player p) setGm(sender, p, ctx.get(mode));
        }, mode);
        c.addSyntax((sender, ctx) -> {
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(ctx.get(who));
            if (p == null) { sender.sendMessage(Component.text("Игрок «" + ctx.get(who) + "» не в сети", NamedTextColor.RED)); return; }
            setGm(sender, p, ctx.get(mode));
        }, mode, who);
        return c;
    }

    private void setGm(net.minestom.server.command.CommandSender sender, Player p, String m) {
        GameMode g = PlayerData.gameMode(m);
        if (g == null) { sender.sendMessage(Component.text("Нет такого режима: " + m, NamedTextColor.RED)); return; }
        p.setGameMode(g);
        if (g == GameMode.CREATIVE || g == GameMode.SPECTATOR) p.setFireTicks(0);
        St s = st(p);
        s.gameMode = g.name().toLowerCase();
        s.fallTop = Double.NaN;
        String name = switch (g) {
            case SURVIVAL -> "выживание";
            case CREATIVE -> "творческий";
            case ADVENTURE -> "приключение";
            case SPECTATOR -> "наблюдатель";
        };
        p.sendMessage(Component.text("Режим игры: " + name, NamedTextColor.GREEN));
        if (sender != p) sender.sendMessage(Component.text(p.getUsername() + ": " + name, NamedTextColor.GREEN));
    }
}
