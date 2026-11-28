package ink.hoka.odyssey.vessels;

import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-5, п.2: эффекты разбитых зон корпуса — множители по зонам, конфиг, сложение с улучшениями, ремонт. */
class HullEffectsTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    static final double[] ALL = {0.2, 0.2, 0.2, 0.2, 0.2};
    static final HullEffects.Config C = HullEffects.Config.DEFAULT;

    static double[] h(double nose, double body, double left, double right, double engine) { return new double[]{nose, body, left, right, engine}; }

    @Test
    void perZone() {
        assertSame(HullEffects.Effects.NONE, HullEffects.of(h(1, 1, 1, 1, 1), ALL, C));
        assertSame(HullEffects.Effects.NONE, HullEffects.of(h(0.01, 0.5, 0.3, 0.3, 0.1), ALL, C), "почти разбиты — ещё нет");
        HullEffects.Effects en = HullEffects.of(h(1, 1, 1, 1, 0), ALL, C);
        assertEquals(0.7, en.speed(), 1e-9);
        assertEquals(HullEffects.ENGINE, en.broken());
        HullEffects.Effects no = HullEffects.of(h(0, 1, 1, 1, 1), ALL, C);
        assertEquals(1.5, no.gunReload(), 1e-9);
        assertEquals(1.5, no.spread(), 1e-9);
        assertEquals(1, no.speed(), 1e-9);
        HullEffects.Effects le = HullEffects.of(h(1, 1, 0, 1, 1), ALL, C);
        assertEquals(0.7, le.turn(), 1e-9);
        assertEquals(HullEffects.LEFT, le.broken());
        HullEffects.Effects both = HullEffects.of(h(1, 1, 0, 0, 1), ALL, C);
        assertEquals(0.49, both.turn(), 1e-9, "оба борта — ×0.7 за каждый");
        HullEffects.Effects bo = HullEffects.of(h(1, 0, 1, 1, 1), ALL, C);
        assertTrue(bo.warpBroken());
        assertEquals(HullEffects.WARP, bo.broken());
        HullEffects.Effects all = HullEffects.of(h(0, 0, 0, 0, 0), ALL, C);
        assertEquals(31, all.broken());
        // зоны, которой у вида нет (доля 0), разбить нельзя
        assertSame(HullEffects.Effects.NONE, HullEffects.of(h(0, 1, 1, 1, 1), new double[]{0, 0.3, 0.3, 0.2, 0.2}, C));
    }

    @Test
    void configAndSwitches() {
        HullEffects.Config d = HullEffects.Config.of(null);
        assertEquals(C, d);
        // старое engine-broken-speed без раздела effects — работает как раньше
        HullEffects.Config legacy = HullEffects.Config.of(Map.of("engine-broken-speed", 0.5));
        assertEquals(0.5, HullEffects.of(h(1, 1, 1, 1, 0), ALL, legacy).speed(), 1e-9);
        HullEffects.Config one = HullEffects.Config.of(Map.of("engine-broken-speed", 1));
        assertSame(HullEffects.Effects.NONE, HullEffects.of(h(1, 1, 1, 1, 0), ALL, one), "1 — двигатели на ход не влияют");
        HullEffects.Config c = HullEffects.Config.of(Map.of("effects", Map.of(
                "threshold", 0.25,
                "engine", Map.of("speed", 0.6),
                "nose", Map.of("enabled", false),
                "sides", Map.of("turn", 0.5),
                "body", Map.of("enabled", false))));
        HullEffects.Effects e = HullEffects.of(h(0, 0, 0.2, 0.9, 0.25), ALL, c);
        assertEquals(0.6, e.speed(), 1e-9, "порог 25% — двигатели на 25% уже разбиты");
        assertEquals(1, e.gunReload(), 1e-9, "нос выключен");
        assertEquals(0.5, e.turn(), 1e-9, "левый ниже порога, правый — цел");
        assertFalse(e.warpBroken(), "корпус выключен");
        assertEquals(HullEffects.ENGINE | HullEffects.LEFT, e.broken());
    }

    @Test
    void stacksWithUpgrades() {
        Upgrades.Stats up = Upgrades.stats(new int[]{2, 0, 3, 0}, Upgrades.Config.DEFAULT);   // 2 двигателя, 3 орудия
        assertEquals(1.2, up.speed(), 1e-9);
        assertEquals(0.7, up.gunReload(), 1e-9);
        assertSame(up, HullEffects.combine(up, HullEffects.Effects.NONE));
        Upgrades.Stats s = HullEffects.combine(up, HullEffects.of(h(0, 1, 1, 1, 0), ALL, C));
        assertEquals(1.2 * 0.7, s.speed(), 1e-9, "Двигатель+ ×2 и разбитые двигатели — перемножаются");
        assertEquals(0.7 * 1.5, s.gunReload(), 1e-9, "Орудие+ ×3 и разбитый нос");
        assertEquals(up.gunDamage(), s.gunDamage(), 1e-9);
        assertEquals(up.warpCost(), s.warpCost(), 1e-9);
    }

    @Test
    void repairBringsZonesBack() throws Exception {
        VesselKind k = HullHpTest.corabll();
        Vessel v = new Vessel(1, "corabll", "cosmos", 0, 0, 0, Quat.IDENTITY);
        v.damage(500, Zones.NOSE, k.hp);                                      // нос — в ноль
        v.damage(30, Zones.BODY, k.hp);
        double[] zh = new double[Zones.COUNT];
        for (int i = 0; i < zh.length; i++) zh[i] = k.zoneHealth(i, v.zoneDmg[i]);
        HullEffects.Effects e = HullEffects.of(zh, k.zoneShare, C);
        assertEquals(HullEffects.GUN, e.broken() & HullEffects.GUN);
        v.repair(k.hp, k.hp);                                                // полный ремонт
        for (int i = 0; i < zh.length; i++) zh[i] = k.zoneHealth(i, v.zoneDmg[i]);
        assertSame(HullEffects.Effects.NONE, HullEffects.of(zh, k.zoneShare, C));
    }

    @Test
    void messages() {
        HullEffects.Effects e = HullEffects.of(h(0, 0, 0, 1, 0), ALL, C);
        assertEquals("Двигатели разбиты — ход 70%", HullEffects.message(HullEffects.ENGINE, e));
        assertTrue(HullEffects.message(HullEffects.WARP, e).contains("варп"));
        assertTrue(HullEffects.message(HullEffects.LEFT, e).contains("70%"));
        assertEquals(4, VesselMenu.brokenLines(HullEffects.of(h(0, 0, 0, 0, 0), ALL, C)).size());
    }
}
