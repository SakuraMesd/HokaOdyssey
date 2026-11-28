package ink.hoka.odyssey.client.vessels.hud;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Логика HUD без экрана: компас, ленты, шкалы, подсказки, раскладка HUD и окна корабля, сводка окна. */
class HudLogicTest {

    static final ToIntFunction<String> FONT = new PngGfx(1, 1, 1)::textWidth;

    // ───────────────────────── компас ─────────────────────────

    static Compass.Mark at(List<Compass.Mark> ms, int deg) {
        return ms.stream().filter(m -> m.deg() == deg).findFirst().orElse(null);
    }

    @Test
    void compassNorth() {
        List<Compass.Mark> ms = Compass.marks(0, 90);
        assertEquals("С", at(ms, 0).label());
        assertEquals(0, at(ms, 0).off(), 1e-4);
        assertEquals("30", at(ms, 30).label());
        assertEquals(-30, at(ms, 330).off(), 1e-4, "330° — левее на 30");
        assertEquals("330", at(ms, 330).label());
        assertNull(at(ms, 90), "восток за краем обзора 90°");
        assertEquals(-45, at(ms, 315).off(), 1e-4);
        assertEquals(45, at(ms, 45).off(), 1e-4);
        assertEquals("000", Compass.readout(0));
    }

    @Test
    void compassWrapAt359() {
        List<Compass.Mark> ms = Compass.marks(359, 90);
        assertEquals(1, at(ms, 0).off(), 1e-4, "север — правее на 1°");
        assertEquals("С", at(ms, 0).label());
        assertEquals(-29, at(ms, 330).off(), 1e-4);
        assertEquals(31, at(ms, 30).off(), 1e-4);
        assertEquals("359", Compass.readout(359));
        assertEquals("000", Compass.readout(359.7f), "округление через 360");
        for (Compass.Mark m : ms) {
            assertTrue(m.deg() >= 0 && m.deg() < 360);
            assertTrue(Math.abs(m.off()) <= 45);
        }
        // одинаковые метки при курсе 359 и −1
        assertEquals(ms, Compass.marks(-1, 90));
    }

    @Test
    void compassSouth() {
        List<Compass.Mark> ms = Compass.marks(180, 90);
        assertEquals("Ю", at(ms, 180).label());
        assertEquals(0, at(ms, 180).off(), 1e-4);
        assertEquals("150", at(ms, 150).label());
        assertEquals("210", at(ms, 210).label());
        assertEquals(2, at(ms, 180).size());
        assertEquals(1, at(ms, 170).size());
        assertEquals(0, at(ms, 175).size());
        assertEquals("В", Compass.label(90));
        assertEquals("З", Compass.label(270));
        // курс игры: yaw 180 — север, 0 — юг, 270 — восток
        assertEquals(0, Compass.heading(180), 1e-4);
        assertEquals(180, Compass.heading(0), 1e-4);
        assertEquals(90, Compass.heading(270), 1e-4);
        assertEquals(270, Compass.heading(-270), 1e-4);
    }

    // ───────────────────────── ленты и шкалы ─────────────────────────

    @Test
    void tapeBounds() {
        for (double v : new double[]{0, 3, 27.4, 50, 123.9}) {
            List<Tape.Mark> ms = Tape.marks(v, 30, 5, 10, 0);
            assertFalse(ms.isEmpty());
            for (Tape.Mark m : ms) {
                assertTrue(m.pos() >= -1 && m.pos() <= 1, "в пределах ленты: " + m);
                assertTrue(m.value() >= 0, "скорость не ниже нуля: " + m);
                assertEquals(m.value() % 10 == 0, m.major());
                assertEquals(m.major(), m.label() != null);
            }
        }
        List<Tape.Mark> at0 = Tape.marks(0, 30, 5, 10, 0);
        assertEquals(0, at0.getFirst().value(), 1e-9);
        assertEquals(0, at0.getFirst().pos(), 1e-6, "ноль — посередине");
        assertEquals(30, at0.getLast().value(), 1e-9);
        assertEquals(1, at0.getLast().pos(), 1e-6, "30 — у верхнего края");
        // высота — и ниже нуля
        List<Tape.Mark> alt = Tape.marks(-420, 50, 10, 50, Double.NaN);
        assertEquals(-470, alt.getFirst().value(), 1e-9);
        assertEquals("-450", alt.stream().filter(Tape.Mark::major).findFirst().get().label());
        // мусор — пусто, без исключений
        assertTrue(Tape.marks(Double.NaN, 30, 5, 10, 0).isEmpty());
        assertTrue(Tape.marks(1e300, 30, 5, 10, 0).isEmpty() || Tape.marks(1e300, 30, 5, 10, 0).size() < 20);
        // подписи реже, если лента низкая
        assertEquals(10, Tape.labelStep(10, 30, 60, 14), 1e-9);
        assertTrue(Tape.labelStep(10, 30, 25, 14) >= 20);
    }

    @Test
    void barFraction() {
        assertEquals(0, Hud.frac(0, 256));
        assertEquals(1, Hud.frac(256, 256));
        assertEquals(1, Hud.frac(999, 256), "не больше 1");
        assertEquals(0, Hud.frac(-5, 256), "не меньше 0");
        assertEquals(0.25f, Hud.frac(64, 256), 1e-6);
        assertEquals(0, Hud.frac(10, 0), "пустой бак — 0");
        assertEquals(0, Hud.frac(Double.NaN, 10));
        assertEquals(0, Hud.frac(5, Double.POSITIVE_INFINITY));
        assertEquals("10 000", Hud.thousands(10000));
        assertEquals("-32 752", Hud.thousands(-32752));
        assertEquals(HudView.WARP_CHARGING, HudView.warpState(0.3f, 1, 0, true, 0, 64));
        assertEquals(HudView.WARP_AIMING, HudView.warpState(-1, 2, 0, true, 100, 64));
        assertEquals(HudView.WARP_RELOAD, HudView.warpState(-1, 0, 40, true, 100, 64));
        assertEquals(HudView.WARP_NO_FUEL, HudView.warpState(-1, 0, 0, true, 63, 64));
        assertEquals(HudView.WARP_READY, HudView.warpState(-1, 0, 0, true, 64, 64));
        assertEquals(HudView.WARP_READY, HudView.warpState(-1, 0, 0, false, 0, 64), "сводки ещё нет — не пугать");
    }

    @Test
    void hintsFade() {
        Hints h = new Hints();
        assertEquals(0, h.alpha(0), "до посадки — нет");
        h.board(1000);
        assertEquals(1, h.alpha(1000));
        assertEquals(1, h.alpha(1000 + Hints.SHOW_MS));
        float mid = h.alpha(1000 + Hints.SHOW_MS + Hints.FADE_MS / 2);
        assertTrue(mid > 0.3f && mid < 0.7f, "гаснут плавно: " + mid);
        assertEquals(0, h.alpha(1000 + Hints.SHOW_MS + Hints.FADE_MS));
        assertEquals(0, h.alpha(60_000));
        long t = 60_000;
        h.hold(false, t);
        assertEquals(0, h.alpha(t), "H не зажата — не показывать");
        h.hold(true, t);
        assertEquals(1, h.alpha(t), "H — снова видны");
        h.hold(true, t + 5000);
        assertEquals(1, h.alpha(t + 5000 + Hints.HOLD_MS), "пока держат и ещё чуть-чуть");
        assertEquals(0, h.alpha(t + 5000 + Hints.HOLD_MS + Hints.FADE_MS));
    }

    // ───────────────────────── раскладка HUD ─────────────────────────

    /** Экраны интерфейса для окон и масштабов GUI 1–4 (как считает игра: масштаб разрешён, если ≥ 320×240). */
    static List<int[]> screens() {
        int[][] windows = {{854, 480}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {1280, 960},
                {1024, 768}, {800, 600}, {640, 480}, {1920, 1200}, {2560, 1080}, {1000, 1000}};
        List<int[]> out = new ArrayList<>();
        for (int[] w : windows) for (int s = 1; s <= 4; s++) {
            if (s > 1 && (w[0] / s < 320 || w[1] / s < 240)) continue;
            out.add(new int[]{(w[0] + s - 1) / s, (w[1] + s - 1) / s, s});
        }
        return out;
    }

    static void noOverlap(HudLayout L, String where) {
        List<HudLayout.Box> bs = L.boxes();
        for (HudLayout.Box b : bs) assertTrue(b.inside(L.w, L.h), where + ": за экраном " + b);
        for (int i = 0; i < bs.size(); i++) for (int j = i + 1; j < bs.size(); j++)
            assertFalse(bs.get(i).overlaps(bs.get(j)), where + ": налезают " + bs.get(i) + " и " + bs.get(j));
        if (L.msg != null) assertFalse(L.msg.overlaps(L.compass), where + ": сообщение на компасе");
        if (L.ladderR > 0) {                                                    // лесенка — между лентами, не на нижних блоках
            HudLayout.Box lad = new HudLayout.Box(L.cx - L.ladderR, L.cy - L.ladderR, 2 * L.ladderR, 2 * L.ladderR);
            for (HudLayout.Box b : bs) if (b != L.compass) assertFalse(lad.overlaps(b), where + ": лесенка на " + b);
            assertTrue(lad.y() >= L.msg.bottom(), where + ": лесенка на сообщении");
        }
    }

    @Test
    void hudLayoutAllScales() {
        List<int[]> screens = screens();
        assertTrue(screens.size() > 30);
        for (int[] s : screens) {
            float k = HudLayout.hintScale(s[2]);
            HudLayout L = new HudLayout(s[0], s[1], k, FONT);
            String where = s[0] + "×" + s[1] + " ×" + s[2];
            noOverlap(L, where);
            assertNotNull(L.left, where + ": силуэт и топливо");
            assertNotNull(L.right, where + ": орудие и варп");
            assertNotNull(L.hints, where + ": подсказки");
            assertNotNull(L.warp, where + ": шкала варпа");
            assertNotNull(L.speed, where + ": ленты");
            assertTrue(L.ladderR > 0, where + ": лесенка");
            assertTrue(L.right.x() > L.left.right(), where + ": углы по разным сторонам");
            for (HudLayout.HintLine line : L.hintLines) assertTrue(line.box().inside(L.w, L.h), where + ": подсказка за экраном");
        }
    }

    @Test
    void hudLayoutTinyWindows() {
        for (int[] s : new int[][]{{300, 200}, {260, 180}, {200, 150}, {160, 120}, {427, 160}, {320, 120}}) {
            HudLayout L = new HudLayout(s[0], s[1], 1, FONT);
            noOverlap(L, s[0] + "×" + s[1]);                                   // лишнее спрятано, а не налезает
        }
    }

    // ───────────────────────── окно корабля ─────────────────────────

    @Test
    void menuSlotsInsidePanelNoOverlap() {
        List<int[]> cells = new ArrayList<>();
        int visible = 0;
        for (int i = 0; i < MenuLayout.SLOTS; i++) {
            int[] c = MenuLayout.cell(i), s = MenuLayout.slot(i);
            if (MenuLayout.kind(i) == MenuLayout.Kind.HIDDEN) {
                assertTrue(s[0] <= -1000 && s[1] <= -1000, "ярлык сундука спрятан: " + i);
                continue;
            }
            visible++;
            assertEquals(c[0] + 1, s[0]);
            assertEquals(c[1] + 1, s[1]);
            boolean button = MenuLayout.kind(i) == MenuLayout.Kind.BUTTON;
            assertTrue(c[0] >= MenuLayout.PAD && c[1] >= (button ? 2 : MenuLayout.HEAD_Y + 2) && c[0] + MenuLayout.CELL <= MenuLayout.W - MenuLayout.PAD
                    && c[1] + MenuLayout.CELL <= MenuLayout.H - MenuLayout.PAD / 2, "слот " + i + " внутри панели: " + c[0] + "," + c[1]);
            for (int[] o : cells) assertFalse(c[0] < o[0] + MenuLayout.CELL && o[0] < c[0] + MenuLayout.CELL
                    && c[1] < o[1] + MenuLayout.CELL && o[1] < c[1] + MenuLayout.CELL, "слоты налезают: " + i);
            assertFalse(c[0] < MenuLayout.GAUGE_X + MenuLayout.GAUGE_W + 26 && MenuLayout.GAUGE_X - 2 < c[0] + MenuLayout.CELL
                    && c[1] < MenuLayout.GAUGE_Y + MenuLayout.GAUGE_H && MenuLayout.GAUGE_Y < c[1] + MenuLayout.CELL, "слот на шкале бака: " + i);
            boolean top = c[1] + MenuLayout.CELL <= MenuLayout.DIV_Y, cargoSide = c[0] + MenuLayout.CELL <= MenuLayout.RIGHT_X - 4;
            switch (MenuLayout.kind(i)) {
                case CARGO -> assertTrue(top && cargoSide, "груз — слева сверху");
                case UPGRADE, FUEL -> assertTrue(top && !cargoSide, "улучшения и топливо — справа сверху");
                case INVENTORY, HOTBAR -> assertTrue(!top && cargoSide, "инвентарь — слева снизу");
                case BUTTON -> {                                                // кнопки — в заголовке, с подписью, не налезают друг на друга
                    int bw = i == MenuLayout.WORKSHOP ? MenuLayout.WORKSHOP_W : MenuLayout.REPAIR_W;
                    assertTrue(c[1] + MenuLayout.CELL <= MenuLayout.HEAD_Y, "кнопка в заголовке");
                    assertTrue(c[0] + bw <= MenuLayout.W - MenuLayout.PAD, "кнопка в окне");
                    assertTrue(FONT.applyAsInt(i == MenuLayout.WORKSHOP ? "МАСТЕРСКАЯ" : "РЕМОНТ") * 0.75f + MenuLayout.CELL + 3 <= bw,
                            "подпись влезает в кнопку " + i);
                }
                default -> { }
            }
            cells.add(c);
        }
        assertEquals(27 + 8 + 4 + 27 + 9 + 2, visible);
        assertTrue(MenuLayout.WORKSHOP_X + MenuLayout.WORKSHOP_W + 4 <= MenuLayout.REPAIR_X, "кнопки не налезают");
        assertTrue(FONT.applyAsInt("КОРАБЛЬ #999 · CORABLL") + MenuLayout.PAD + 4 <= MenuLayout.WORKSHOP_X, "заголовок не под кнопками");
        // сводка справа снизу (прочность, зоны и 7 строк по 8.5) — без слотов
        for (int[] c : cells) assertFalse(c[0] + MenuLayout.CELL > MenuLayout.RIGHT_X && c[1] >= MenuLayout.DIV_Y, "слот под сводкой");
        assertTrue(MenuLayout.INV_LABEL_Y + 10 + 9 * 8.5f + 1 <= MenuLayout.H - 3, "сводка влезает");
    }

    @Test
    void menuFitsAllScales() {
        for (int[] s : screens()) {
            int[] o = MenuLayout.origin(s[0], s[1]);
            assertTrue(o[0] >= 0 && o[1] >= 0 && o[0] + MenuLayout.W <= s[0] && o[1] + MenuLayout.H <= s[1],
                    "окно на экране " + s[0] + "×" + s[1] + " ×" + s[2]);
        }
    }

    @Test
    void menuInfoParseAndMatch() {
        MenuInfo m = MenuInfo.parse("{\"window\":-3,\"title\":\"Корабль #3 · corabll · Ник\",\"id\":3,\"kind\":\"corabll\",\"owner\":\"Ник\","
                + "\"fuel\":150,\"capacity\":256,\"warp-cost\":58,\"warp-distance\":10000.0,\"upgrades\":2,\"mul\":{\"speed\":1.1,\"warp-cost\":0.9}}");
        assertEquals(150, m.fuel);
        assertEquals(58, m.warpCost);
        assertEquals("Ник", m.owner);
        assertEquals(1.1, m.mulSpeed, 1e-9);
        assertEquals(1, m.mulGunDamage, 1e-9, "нет в пакете — без улучшения");
        assertTrue(m.matches(253, "Корабль #3 · corabll · Ник"), "номер окна — байт (−3 = 253)");
        assertFalse(m.matches(253, "Сундук"), "чужое окно с тем же номером");
        assertFalse(m.matches(4, "Корабль #3 · corabll · Ник"));
        assertFalse(new MenuInfo().matches(0, ""), "сводки не было");
        MenuInfo bad = MenuInfo.parse("{\"fuel\":\"много\",\"capacity\":0}");
        assertEquals(0, bad.fuel, "мусор — по умолчанию");
        assertEquals(1, bad.capacity, "ёмкость не 0");
    }
}
