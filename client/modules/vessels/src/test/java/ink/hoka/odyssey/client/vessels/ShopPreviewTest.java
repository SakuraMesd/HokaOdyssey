package ink.hoka.odyssey.client.vessels;

import ink.hoka.odyssey.client.vessels.hud.MenuLayout;
import ink.hoka.odyssey.client.vessels.hud.PngGfx;
import ink.hoka.odyssey.client.vessels.hud.ShopArt;
import ink.hoka.odyssey.client.vessels.hud.ShopInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-4: окно «МАСТЕРСКАЯ» — превью PNG и раскладка (слоты в окне, не налезают, влезает в наименьший экран). */
class ShopPreviewTest {

    static ShopInfo info() {
        ShopInfo s = new ShopInfo();
        s.id = 3;
        s.kind = "corabll";
        s.recipes.add(new ShopInfo.Recipe("Топливо", 16, true, List.of(new ShopInfo.Need("Топливный кристалл", 4, 10))));
        s.recipes.add(new ShopInfo.Recipe("Ремкомплект", 1, false, List.of(new ShopInfo.Need("Железная руда", 4, 6),
                new ShopInfo.Need("Никелевая руда", 2, 1), new ShopInfo.Need("Топливный кристалл", 1, 10))));
        s.recipes.add(new ShopInfo.Recipe("Двигатель+", 1, false, List.of(new ShopInfo.Need("Кобальтовая руда", 6, 0),
                new ShopInfo.Need("Платиновая руда", 2, 0), new ShopInfo.Need("Топливный кристалл", 8, 10))));
        s.recipes.add(new ShopInfo.Recipe("Бак+", 1, true, List.of(new ShopInfo.Need("Магниевая руда", 8, 9),
                new ShopInfo.Need("Медная руда", 6, 12), new ShopInfo.Need("Топливный кристалл", 4, 10))));
        s.recipes.add(new ShopInfo.Recipe("Орудие+", 1, false, List.of(new ShopInfo.Need("Серебряная руда", 4, 2),
                new ShopInfo.Need("Железная руда", 8, 6), new ShopInfo.Need("Топливный кристалл", 6, 10))));
        s.recipes.add(new ShopInfo.Recipe("Варп-катушка", 1, false, List.of(new ShopInfo.Need("Урановая руда", 2, 0),
                new ShopInfo.Need("Золотая руда", 4, 1), new ShopInfo.Need("Кремниевая руда", 6, 3), new ShopInfo.Need("Топливный кристалл", 12, 10))));
        return s;
    }

    void render(String name, int sw, int sh, float gs, int hovered) throws Exception {
        PngGfx g = new PngGfx(sw, sh, gs).space(name.hashCode());
        g.fill(0, 0, sw, sh, 0xA0101018);
        int[] o = ShopArt.origin(sw, sh);
        ShopArt art = new ShopArt();
        art.background(g, o[0], o[1]);
        art.labels(g, o[0], o[1], info(), hovered);
        String[] items = new String[ShopArt.SLOTS];
        int[] counts = new int[ShopArt.SLOTS];
        String[] rec = {"fuel", "repair", "engine", "tank", "gun", "warp"};
        int[] rc = {16, 1, 1, 1, 1, 1};
        for (int i = 0; i < rec.length; i++) { items[i] = rec[i]; counts[i] = rc[i]; }
        items[ShopArt.BACK] = "arrow";
        String[] cargo = {"crystal", "iron_ore", "nickel_ore", "magnesium_ore", "copper_ore", "silver_ore", "gold_ore", "silicon_ore", "bread", "iron_ingot"};
        int[] cc = {10, 6, 1, 9, 12, 2, 1, 3, 5, 7};
        for (int i = 0; i < cargo.length; i++) {
            String n = cargo[i].endsWith("_ore") && !cargo[i].equals("iron_ore") && !cargo[i].equals("copper_ore") && !cargo[i].equals("gold_ore") ? "raw_iron" : cargo[i];
            items[ShopArt.CARGO0 + i] = n;
            counts[ShopArt.CARGO0 + i] = cc[i];
        }
        for (int i = 0; i < ShopArt.SLOTS; i++) {
            if (items[i] == null || ShopArt.kind(i) == ShopArt.Kind.HIDDEN) continue;
            int[] p = ShopArt.slot(i);
            g.icon(MenuPreviewTest.item(items[i]), o[0] + p[0], o[1] + p[1], 16);
            if (counts[i] > 1) g.text(Integer.toString(counts[i]), o[0] + p[0] + 17, o[1] + p[1] + 9, 1, 0xFFFFFFFF, 1, true);
        }
        g.save(HudPreviewTest.out().resolve(name + ".png"));
        assertTrue(HudPreviewTest.out().resolve(name + ".png").toFile().length() > 1000);
    }

    @Test
    void preview() throws Exception {
        render("shop-1-workshop", 640, 360, 3, 3);
        render("shop-2-gui4-320x240", 320, 240, 4, ShopArt.BACK);
    }

    @Test
    void layout() {
        List<int[]> cells = new ArrayList<>();
        int visible = 0;
        for (int i = 0; i < ShopArt.SLOTS; i++) {
            int[] c = ShopArt.cell(i);
            if (ShopArt.kind(i) == ShopArt.Kind.HIDDEN) {
                assertTrue(c[0] <= -1000, "спрятан: " + i);
                continue;
            }
            visible++;
            int w = i == ShopArt.BACK ? ShopArt.BACK_W : ShopArt.CELL;
            assertTrue(c[0] >= ShopArt.PAD && c[1] >= 2 && c[0] + w <= ShopArt.W - ShopArt.PAD && c[1] + ShopArt.CELL <= ShopArt.H - 4, "в окне: " + i);
            for (int[] o : cells) assertFalse(c[0] < o[0] + ShopArt.CELL && o[0] < c[0] + ShopArt.CELL && c[1] < o[1] + ShopArt.CELL && o[1] < c[1] + ShopArt.CELL,
                    "налезают: " + i);
            cells.add(c);
        }
        assertEquals(8 + 27 + 1, visible);
        assertTrue(ShopArt.REC_Y + 4 * ShopArt.REC_H <= ShopArt.DIV_Y, "рецепты над грузом");
        for (int[] s : new int[][]{{320, 240}, {427, 240}, {480, 270}, {640, 360}}) {
            int[] o = ShopArt.origin(s[0], s[1]);
            assertTrue(o[0] >= 0 && o[1] >= 0 && o[0] + ShopArt.W <= s[0] && o[1] + ShopArt.H <= s[1], "на экране " + s[0] + "×" + s[1]);
        }
        assertTrue(MenuLayout.AWAY < -1000);
    }

    @Test
    void parseShop() {
        ShopInfo s = ShopInfo.read(com.google.gson.JsonParser.parseString("{\"window\":7,\"title\":\"Мастерская · корабль #3\",\"id\":3,"
                + "\"recipes\":[{\"name\":\"Топливо\",\"count\":16,\"ok\":true,\"needs\":[{\"name\":\"Топливный кристалл\",\"need\":4,\"have\":9}]},"
                + "{\"name\":\"мусор\"}]}").getAsJsonObject());
        assertEquals(7, s.window);
        assertEquals(1, s.recipes.size(), "битый рецепт пропущен");
        assertTrue(s.recipes.get(0).needs().get(0).ok());
        assertTrue(s.matches(7, "Мастерская · корабль #3"));
        assertFalse(s.matches(8, "Мастерская · корабль #3"));
        assertFalse(s.matches(7, "Сундук"));
    }
}
