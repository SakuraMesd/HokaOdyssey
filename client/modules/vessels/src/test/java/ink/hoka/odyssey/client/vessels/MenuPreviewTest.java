package ink.hoka.odyssey.client.vessels;

import ink.hoka.odyssey.client.vessels.hud.MenuArt;
import ink.hoka.odyssey.client.vessels.hud.HudView;
import ink.hoka.odyssey.client.vessels.hud.MenuInfo;
import ink.hoka.odyssey.client.vessels.hud.MenuLayout;
import ink.hoka.odyssey.client.vessels.hud.PngGfx;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Превью окна корабля (G) в PNG: фон и подписи — тем же кодом (MenuArt), предметы — их иконки (свои из demo-objects,
 * ванильные — из jar игры), число в стопке — как у игры. Под окном — затемнённый «мир», как у окон игры.
 */
class MenuPreviewTest {

    static BufferedImage item(String name) throws Exception {
        Path own = Path.of("../../../demo-objects/vessels/hoitems/vessels/" + name + ".png");
        if (own.toFile().exists()) return ImageIO.read(own.toFile());
        for (String path : new String[]{"item/" + name, "block/" + name + "_front", "block/" + name + "_top", "block/" + name}) {
            try (InputStream in = MenuPreviewTest.class.getClassLoader().getResourceAsStream("assets/minecraft/textures/" + path + ".png")) {
                if (in == null) continue;
                BufferedImage im = ImageIO.read(in);
                return im.getSubimage(0, 0, 16, 16);
            }
        }
        assertNotNull(null, name);
        return null;
    }

    static MenuInfo info(int fuel, int upgrades, boolean boosted) {
        MenuInfo m = new MenuInfo();
        m.id = 3; m.kind = "corabll"; m.owner = "Takuyamo"; m.fuel = fuel; m.capacity = 256;
        m.warpCost = boosted ? 58 : 64; m.warpDistance = 10000; m.warpCharge = 3; m.warpReload = boosted ? 25.5 : 30;
        m.gunDamage = boosted ? 6.6 : 6; m.gunReload = boosted ? 0.45 : 0.5; m.gunRange = 300; m.speedMul = boosted ? 1.1 : 1;
        m.upgrades = upgrades;
        m.hpMax = 200;
        m.hp = boosted ? 146 : 200;
        if (boosted) { m.zones[0] = 0.3f; m.zones[2] = 0.6f; }
        if (boosted) { m.mulSpeed = 1.1; m.mulWarpCost = 0.9; m.mulGunDamage = 1.1; m.mulGunReload = 0.9; m.mulWarpReload = 0.85; }
        return m;
    }

    void render(String name, int sw, int sh, float gs, MenuInfo m, String[] items, int[] counts, int hovered) throws Exception {
        PngGfx g = new PngGfx(sw, sh, gs).space(name.hashCode());
        g.fill(0, 0, sw, sh, 0xA0101018);                                       // затемнение под окном (как у игры)
        int[] o = MenuLayout.origin(sw, sh);
        MenuArt art = new MenuArt();
        art.background(g, o[0], o[1]);
        boolean[] empty = new boolean[MenuLayout.SLOTS];
        for (int i = 0; i < empty.length; i++) empty[i] = items[i] == null;
        art.labels(g, o[0], o[1], m, empty, hovered);
        for (int i = 0; i < MenuLayout.SLOTS; i++) {
            if (items[i] == null || MenuLayout.kind(i) == MenuLayout.Kind.HIDDEN) continue;
            int[] p = MenuLayout.slot(i);
            g.icon(item(items[i]), o[0] + p[0], o[1] + p[1], 16);
            if (counts[i] > 1) g.text(Integer.toString(counts[i]), o[0] + p[0] + 17, o[1] + p[1] + 9, 1, 0xFFFFFFFF, 1, true);
        }
        g.save(HudPreviewTest.out().resolve(name + ".png"));
    }

    @Test
    void preview() throws Exception {
        String[] items = new String[MenuLayout.SLOTS];
        int[] counts = new int[MenuLayout.SLOTS];
        String[] cargo = {"iron_ingot", "gold_ingot", "diamond", "coal", "redstone", "emerald", "bread", "cooked_beef", "apple",
                "stick", "arrow", "ender_pearl", "iron_pickaxe", "copper_ingot", "lapis_lazuli"};
        int[] cc = {48, 12, 5, 64, 31, 3, 16, 9, 7, 22, 40, 2, 1, 27, 18};
        for (int i = 0; i < cargo.length; i++) { int s = i < 9 ? i : i + 2; items[s] = cargo[i]; counts[s] = cc[i]; }
        items[MenuLayout.UP0] = "engine"; items[MenuLayout.UP0 + 1] = "tank"; items[MenuLayout.UP0 + 2] = "gun"; items[MenuLayout.UP0 + 4] = "warp";
        items[MenuLayout.WORKSHOP] = "crafting_table"; items[MenuLayout.REPAIR] = "anvil";
        items[MenuLayout.FUEL0] = "fuel"; counts[MenuLayout.FUEL0] = 64; items[MenuLayout.FUEL0 + 1] = "fuel"; counts[MenuLayout.FUEL0 + 1] = 64;
        items[MenuLayout.FUEL0 + 2] = "fuel"; counts[MenuLayout.FUEL0 + 2] = 22;
        String[] inv = {"fuel", "engine", "iron_sword", "bread", "coal", "torch", "oak_boat", "stick"};
        int[] ic = {38, 1, 1, 12, 9, 1, 1, 30};
        for (int i = 0; i < inv.length; i++) { int s = MenuLayout.PLAYER0 + i * 3; items[s] = inv[i].equals("torch") ? "flint" : inv[i]; counts[s] = ic[i]; }
        for (int i = 0; i < 9; i++) if (i % 2 == 0) { items[MenuLayout.HOTBAR0 + i] = new String[]{"iron_pickaxe", "bread", "fuel", "arrow", "diamond"}[i / 2]; counts[MenuLayout.HOTBAR0 + i] = i == 4 ? 16 : 1; }
        render("menu-1-cargo", 640, 360, 3, info(150, 4, true), items, counts, MenuLayout.UP0 + 3);

        String[] few = new String[MenuLayout.SLOTS];
        int[] fc = new int[MenuLayout.SLOTS];
        few[0] = "iron_ingot"; fc[0] = 7; few[MenuLayout.FUEL0] = "fuel"; fc[MenuLayout.FUEL0] = 40;
        few[MenuLayout.PLAYER0 + 4] = "engine"; few[MenuLayout.WORKSHOP] = "crafting_table"; few[MenuLayout.REPAIR] = "anvil"; few[MenuLayout.HOTBAR0] = "iron_pickaxe";
        render("menu-2-low-fuel-gui4-320x240", 320, 240, 4, info(40, 0, false), few, fc, MenuLayout.FUEL0 + 1);

        // vessels-5: разбиты нос, корпус, левый борт и двигатели — сводка показывает, что сломано
        MenuInfo b = info(150, 4, true);
        b.hp = 38; b.zones[0] = 0; b.zones[1] = 0; b.zones[2] = 0; b.zones[3] = 0.7f; b.zones[4] = 0;
        b.broken = HudView.GUN | HudView.WARP | HudView.LEFT | HudView.ENGINE; b.turn = 0.7; b.gunReload = 0.68; b.speedMul = 0.77;
        render("menu-3-broken", 640, 360, 3, b, items, counts, -1);
        render("menu-4-broken-gui4-320x240", 320, 240, 4, b, few, fc, -1);
    }
}
