package ink.hoka.odyssey.client.inv;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 0.68 HUD выживания Hoka Odyssey (в стиле окна инвентаря): прозрачная быстрая панель со скруглёнными слотами,
 * над ней две полоски — здоровье (слева, сердце в бейдже) и еда (справа, окорок), деления по 1 единице, след
 * недавнего урона; тонкие полоски брони и воздуха — только когда нужны. Полосы опыта нет. Включается вместе с окном
 * инвентаря (сервер с модулем survival); в творческом и на других серверах — ванильный HUD.
 */
public final class HokaHud {

    private HokaHud() {}

    private static final int RIM = 0x96_3C64A0, PANEL = 0x6E_0A1428, SLOT = 0x78_060C1A;
    private static final int OUT = 0xFF0A1224, ICE = 0xFF6FD3FF, SEL_FILL = 0x326FD3FF;
    private static final int RED = 0xFFE84656, RED_HI = 0xFFFF8C94, RED_LO = 0xFFAA283A, TRAIL = 0xFFFFE6E6;
    private static final int FOOD = 0xFFE89842, FOOD_HI = 0xFFFFCE8C, FOOD_LO = 0xFFB06822;
    private static final int ARM = 0xFFAABAD2, ARM_HI = 0xFFE1EBF8, AIR = 0xFF6FD3FF, AIR_HI = 0xFFBEF0FF;
    private static final int BONE = 0xFFECE8DC;

    private static final String[] HEART = {".##.##.", "#+##++#", "#+#####", "#######", ".#####.", "..###..", "...#..."};
    private static final String[] DRUM = {"..###..", ".#++##.", ".#####.", ".#####.", "..###..", ".b.....", "bb....."};
    private static final String[] SHIELD = {"#####", "#+++#", "#+++#", ".#+#.", "..#.."};
    private static final String[] BUBBLE = {".###.", "#+..#", "#...#", "#...#", ".###."};

    private static float trail = -1, lastHp = -1;
    private static long trailAt;

    public static void register() {
        HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, v -> wrap(v, HokaHud::render));
        HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, v -> wrap(v, null));
        HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR, v -> wrap(v, null));
        HudElementRegistry.replaceElement(VanillaHudElements.ARMOR_BAR, v -> wrap(v, null));
        HudElementRegistry.replaceElement(VanillaHudElements.AIR_BAR, v -> wrap(v, null));
        HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, v -> wrap(v, null));          // полоса опыта
        HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, v -> wrap(v, null));
        // 0.79: модуль спрятал HUD (ClientContext.hideHud) — ещё прицел и подпись предмета
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, v -> (g, dt) -> { if (!ink.hoka.odyssey.client.Hooks.hideHud()) v.render(g, dt); });
        HudElementRegistry.replaceElement(VanillaHudElements.HELD_ITEM_TOOLTIP, v -> (g, dt) -> { if (!ink.hoka.odyssey.client.Hooks.hideHud()) v.render(g, dt); });
    }

    private static boolean active() {
        LocalPlayer p = Minecraft.getInstance().player;
        return HokaInventory.enabled && p != null && !p.isCreative() && !p.isSpectator();
    }

    /** Наш HUD вместо ванильного элемента (null — просто скрыть). */
    private static HudElement wrap(HudElement vanilla, HudElement ours) {
        return (g, dt) -> {
            if (ink.hoka.odyssey.client.Hooks.hideHud()) return;               // 0.79: модуль рисует свой HUD (кабина корабля)
            if (!active()) { vanilla.render(g, dt); return; }
            if (ours != null) ours.render(g, dt);
        };
    }

    private static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.options.hideGui) return;
        int w = g.guiWidth(), h = g.guiHeight();
        int x0 = w / 2 - 90, y = h - 22;
        int left = x0 - 4, right = x0 + 181;
        // быстрая панель
        round(g, left, y - 4, right - left + 1, 26, 6, PANEL, RIM);
        int sel = p.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            int x = x0 + i * 20;
            round(g, x, y, 19, 19, 3, SLOT, RIM);
            if (i == sel) {
                round(g, x + 1, y + 1, 17, 17, 3, SEL_FILL, 0);
                round(g, x - 1, y - 1, 21, 21, 4, 0, ICE);
            }
            ItemStack it = p.getInventory().getItem(i);
            if (!it.isEmpty()) {
                g.renderItem(p, it, x + 2, y + 2, i + 1);
                g.renderItemDecorations(mc.font, it, x + 2, y + 2);
            }
        }
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) {
            int ox = left - 26;
            round(g, ox, y - 1, 23, 23, 5, PANEL, RIM);
            round(g, ox + 2, y + 1, 19, 19, 3, SLOT, RIM);
            g.renderItem(p, off, ox + 4, y + 3, 10);
            g.renderItemDecorations(mc.font, off, ox + 4, y + 3);
        }
        // полоски
        int half = (right - left - 6) / 2, by = y - 15;
        float max = Math.max(1, p.getMaxHealth()), hp = Math.min(1, p.getHealth() / max);
        long now = System.currentTimeMillis();
        if (lastHp < 0) { lastHp = hp; trail = hp; }
        if (hp < lastHp) { if (trail < lastHp) trail = lastHp; trailAt = now; }
        if (hp >= trail || now - trailAt > 500) trail = Math.max(hp, trail - 0.02f);
        lastHp = hp;
        bar(g, left + 10, half - 10, by, 9, hp, trail, RED, RED_HI, RED_LO, false);
        bar(g, right - half + 1, half - 10, by, 9, p.getFoodData().getFoodLevel() / 20f, 0, FOOD, FOOD_HI, FOOD_LO, true);
        badge(g, left + 6, by + 4, HEART, RED, RED_HI);
        badge(g, right - 5, by + 4, DRUM, FOOD, FOOD_HI);
        int armor = p.getArmorValue();
        if (armor > 0) {
            bar(g, left + 14, half - 14, by - 6, 5, Math.min(1, armor / 20f), 0, ARM, ARM_HI, ARM, false);
            icon(g, SHIELD, left + 6, by - 7, ARM, ARM_HI);
        }
        int air = p.getAirSupply(), airMax = Math.max(1, p.getMaxAirSupply());
        if (air < airMax) {
            bar(g, right - half + 1, half - 14, by - 6, 5, Math.max(0, air / (float) airMax), 0, AIR, AIR_HI, AIR, true);
            icon(g, BUBBLE, right - 11, by - 7, AIR, AIR_HI);
        }
    }

    /** Полоска: прозрачный жёлоб с голубой кромкой (как панель), заливка с бликом и тенью, деления по 1/10. */
    private static void bar(GuiGraphics g, int x, int w, int y, int h, float frac, float trailFrac, int col, int hi, int lo, boolean fromRight) {
        round(g, x, y, w, h, h / 2, PANEL, RIM);
        int inner = w - 4;
        if (trailFrac > frac) seg(g, x, y, h, inner, frac, trailFrac, TRAIL, 0xFFFFFFFF, 0xFFDCB4B4, fromRight);
        seg(g, x, y, h, inner, 0, frac, col, hi, lo, fromRight);
        for (int k = 1; k < 10; k++) {
            int tx = x + 2 + Math.round(inner * k / 10f);
            g.fill(tx, y + 3, tx + 1, y + h - 3, 0x5A0A1224);
        }
    }

    private static void seg(GuiGraphics g, int x, int y, int h, int inner, float f0, float f1, int c, int hi, int lo, boolean fromRight) {
        int a = Math.round(inner * f0), b = Math.round(inner * f1);
        if (b - a < 1) return;
        int x0 = fromRight ? x + 2 + inner - b : x + 2 + a, x1 = fromRight ? x + 2 + inner - a : x + 2 + b;
        round(g, x0, y + 2, x1 - x0, h - 4, Math.max(1, (h - 4) / 2), c, 0);
        if (h >= 7 && x1 - x0 > 2) {
            g.fill(x0 + 1, y + 2, x1 - 1, y + 3, hi);
            g.fill(x0 + 1, y + h - 3, x1 - 1, y + h - 2, lo);
        }
    }

    /** Круглый бейдж со значком — в стиле панели. */
    private static void badge(GuiGraphics g, int cx, int cy, String[] icon, int main, int hi) {
        round(g, cx - 7, cy - 7, 15, 15, 7, 0xC80A1428, RIM);
        icon(g, icon, cx - icon[0].length() / 2, cy - icon.length / 2, main, hi);
    }

    /** Пиксельный значок с тёмным контуром. */
    private static void icon(GuiGraphics g, String[] rows, int x, int y, int main, int hi) {
        for (int j = 0; j < rows.length; j++) for (int i = 0; i < rows[j].length(); i++) {
            if (rows[j].charAt(i) == '.') continue;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int ni = i + d[0], nj = j + d[1];
                boolean in = nj >= 0 && nj < rows.length && ni >= 0 && ni < rows[nj].length() && rows[nj].charAt(ni) != '.';
                if (!in) g.fill(x + ni, y + nj, x + ni + 1, y + nj + 1, OUT);
            }
        }
        for (int j = 0; j < rows.length; j++) for (int i = 0; i < rows[j].length(); i++) {
            char c = rows[j].charAt(i);
            if (c == '.') continue;
            g.fill(x + i, y + j, x + i + 1, y + j + 1, c == 'b' ? BONE : c == '+' ? hi : main);
        }
    }

    /**
     * Скруглённый прямоугольник: заливка fill (0 — без) и кромка rim в 1 пиксель (0 — без). Прозрачные цвета не
     * накладываются друг на друга: кромка рисуется только там, где нет заливки.
     */
    static void round(GuiGraphics g, int x, int y, int w, int h, int r, int fill, int rim) {
        r = Math.min(r, Math.min(w, h) / 2);
        for (int j = 0; j < h; j++) {
            int in = inset(j, h, r), in2 = j == 0 || j == h - 1 ? w : inset2(j, h, r, in);
            if (rim != 0) {
                if (j == 0 || j == h - 1) g.fill(x + in, y + j, x + w - in, y + j + 1, rim);
                else {
                    g.fill(x + in, y + j, x + in2, y + j + 1, rim);
                    g.fill(x + w - in2, y + j, x + w - in, y + j + 1, rim);
                }
            }
            if (fill != 0) {
                int a = rim != 0 ? in2 : in, b = rim != 0 ? w - in2 : w - in;
                if (rim != 0 && (j == 0 || j == h - 1)) continue;
                if (b > a) g.fill(x + a, y + j, x + b, y + j + 1, fill);
            }
        }
    }

    /** Отступ строки j от края при радиусе r. */
    private static int inset(int j, int h, int r) {
        int d = j < r ? r - j - 1 : j >= h - r ? j - (h - r) : -1;
        if (d < 0) return 0;
        double dy = d + 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    /** Внутренний край кромки: соседние строки — чтобы на скруглении кромка была сплошной. */
    private static int inset2(int j, int h, int r, int in) {
        int a = inset(j - 1, h, r), b = inset(j + 1, h, r);
        return Math.max(in + 1, Math.max(a, b));
    }
}
