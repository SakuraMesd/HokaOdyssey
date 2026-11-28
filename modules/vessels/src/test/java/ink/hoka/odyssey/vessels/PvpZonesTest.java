package ink.hoka.odyssey.vessels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-5, п.1: бить или не бить — бой кораблей и орудие по игрокам в PvP-зонах. */
class PvpZonesTest {

    @Test
    void modeFromConfig() {
        assertEquals(PvpRule.Mode.ZONES, PvpRule.mode(null));
        assertEquals(PvpRule.Mode.ZONES, PvpRule.mode("zones"));
        assertEquals(PvpRule.Mode.ALL, PvpRule.mode("ALL"));
        assertEquals(PvpRule.Mode.OFF, PvpRule.mode("off"));
        assertEquals(PvpRule.Mode.OFF, PvpRule.mode("false"));                   // YAML: off без кавычек → false
        assertEquals(PvpRule.Mode.ZONES, PvpRule.mode("чепуха"));
    }

    @Test
    void shipsFightOnlyInZones() {
        PvpRule.Mode z = PvpRule.Mode.ZONES;
        assertTrue(PvpRule.fight(z, true, true, true), "оба в зоне — бьём");
        assertFalse(PvpRule.fight(z, true, true, false), "цель вне зоны — болт насквозь");
        assertFalse(PvpRule.fight(z, true, false, true), "стрелок вне зоны — не бьём");
        assertFalse(PvpRule.fight(z, true, false, false));
        assertTrue(PvpRule.fight(z, false, false, false), "survival нет — как раньше (бьём)");
        assertTrue(PvpRule.fight(PvpRule.Mode.ALL, true, false, false));
        assertFalse(PvpRule.fight(PvpRule.Mode.OFF, false, true, true));
    }

    @Test
    void playersAlsoFollowSurvivalMode() {
        PvpRule.Mode z = PvpRule.Mode.ZONES;
        assertTrue(PvpRule.fightPlayer(z, "all", true, true));
        assertFalse(PvpRule.fightPlayer(z, "all", true, false));
        assertFalse(PvpRule.fightPlayer(z, "off", true, true), "в survival PvP выключен — игроков не бьём");
        assertFalse(PvpRule.fightPlayer(PvpRule.Mode.ALL, "off", true, true));
        assertTrue(PvpRule.fightPlayer(PvpRule.Mode.ALL, "zones", false, false), "бой кораблей везде — режим vessels главнее");
        assertTrue(PvpRule.fightPlayer(z, null, false, false), "survival нет — как раньше");
    }

    @Test
    void hudLabel() {
        assertTrue(PvpRule.label(PvpRule.Mode.ZONES, true, true));
        assertFalse(PvpRule.label(PvpRule.Mode.ZONES, true, false));
        assertFalse(PvpRule.label(PvpRule.Mode.ZONES, false, false), "без survival зон нет — метки нет");
        assertTrue(PvpRule.label(PvpRule.Mode.ALL, false, false));
        assertFalse(PvpRule.label(PvpRule.Mode.OFF, true, true));
    }

    @Test
    void spreadStaysInCone() {
        double[] d = {0, 0, 1};
        assertSame(d, Combat.spread(d, 0));
        for (double[] dir : new double[][]{{0, 0, 1}, {0, 1, 0}, {0.6, -0.8, 0}}) {
            for (int i = 0; i < 500; i++) {
                double[] s = Combat.spread(dir, 1.5);
                double len = Math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
                assertEquals(1, len, 1e-9);
                double ang = Math.toDegrees(Math.acos(Math.min(1, s[0] * dir[0] + s[1] * dir[1] + s[2] * dir[2])));
                assertTrue(ang <= 1.5 + 1e-6, "отклонение " + ang);
            }
        }
    }
}
