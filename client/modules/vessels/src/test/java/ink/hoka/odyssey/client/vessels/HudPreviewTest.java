package ink.hoka.odyssey.client.vessels;

import ink.hoka.odyssey.client.vessels.hud.HudView;
import ink.hoka.odyssey.client.vessels.hud.PilotHud;
import ink.hoka.odyssey.client.vessels.hud.PngGfx;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Превью HUD пилота в PNG без игры — тем же кодом холстов, что в игре, шрифтом игры. Папка — системное свойство
 * vessels.preview (по умолчанию build/preview; для отчёта: {@code gradle :modules:vessels:test -PvesselsPreview=…}).
 */
class HudPreviewTest {

    static Path out() { return Path.of(System.getProperty("vessels.preview", "build/preview")); }

    /** Лесенка «как в игре» по наклону и крену камеры (линейно; в игре — проекцией по камере). */
    static float[] rungs(float w, float h, float pitch, float roll) {
        float k = (float) ((h / 2) / Math.tan(Math.toRadians(35)) * Math.PI / 180);
        float cx = w / 2, cy = h / 2, dx = (float) Math.cos(Math.toRadians(roll)), dy = (float) Math.sin(Math.toRadians(roll));
        float ux = dy, uy = -dx;                                                // «вверх» лесенки на экране
        float[] r = new float[19 * 5];
        int n = 0;
        for (int a = -90; a <= 90; a += 10) {
            float off = (a - pitch) * k;
            r[n++] = a; r[n++] = cx + ux * off; r[n++] = cy + uy * off; r[n++] = dx; r[n++] = dy;
        }
        return r;
    }

    record Scene(String name, int w, int h, float gs, HudView v) {}

    static HudView view(int w, int h, float[] tris, float aimDx, float aimDy, float heading, float pitch, float roll,
                        double speed, int throttle, boolean boost, double alt, double vs, int fuel, float gun, int warp, float warpK,
                        int warpLeft, String msg, boolean warn, float hints, float hit, boolean kill) {
        return new HudView(w / 2f, h / 2f, true, w / 2f + aimDx, h / 2f + aimDy, true, heading, rungs(w, h, pitch, roll),
                speed, 30, 50, throttle, boost, alt, vs, true, fuel, 256, 64, false, gun, (1 - gun) * 0.5f,
                warp, warpK, warpLeft, msg, warn, msg == null ? 0 : 1, hints, hit, kill, "corabll", tris);
    }

    @Test
    void preview() throws Exception {
        float[] tris = KindTest.corabll(KindTest.tris(), 180).tris;
        Scene[] scenes = {
                new Scene("hud-1-flight", 640, 360, 3, view(640, 360, tris, 26, -12, 247, 6, -12, 28, 1, false, 1184, 1.2, 150, 1,
                        HudView.WARP_READY, 1, 0, null, false, 1, 0, false)),
                new Scene("hud-2-boost-hit", 640, 360, 3, view(640, 360, tris, -8, 4, 2, -4, 18, 49, 1, true, 1302, -3.4, 150, 0.45f,
                        HudView.WARP_RELOAD, 0.4f, 18, null, false, 0, 0.9f, false)),
                new Scene("hud-3-warp-charge", 640, 360, 3, view(640, 360, tris, 0, 0, 91, 0, 0, 31, 1, false, 860, 0, 150, 1,
                        HudView.WARP_CHARGING, 0.63f, 0, "Варп: зарядка — R ещё раз, чтобы отменить", false, 1, 0, false)),
                new Scene("hud-4-low-fuel", 640, 360, 3, view(640, 360, tris, 40, 22, 180, 12, 4, 6, -1, false, -420, 0.6, 40, 1,
                        HudView.WARP_NO_FUEL, 40 / 64f, 0, "Мало топлива для варпа: нужно 64, в баке 40", true, 0, 1, true)),
                new Scene("hud-5-gui4-320x240", 320, 240, 4, view(320, 240, tris, 20, -10, 300, 3, -8, 30, 1, false, 75, 0.4, 150, 0.7f,
                        HudView.WARP_CHARGING, 0.3f, 0, null, false, 1, 0, false)),
                new Scene("hud-6-gui2-960x540", 960, 540, 2, view(960, 540, tris, 30, -18, 33, 8, 25, 44, 1, true, 2210, 4.8, 220, 1,
                        HudView.WARP_READY, 1, 0, null, false, 1, 0, false)),
        };
        for (Scene s : scenes) {
            PngGfx g = new PngGfx(s.w(), s.h(), s.gs()).space(s.name().hashCode());
            PilotHud hud = new PilotHud();
            hud.eager = true;
            hud.draw(g, s.v(), 0);
            g.save(out().resolve(s.name() + ".png"));
            assertTrue(out().resolve(s.name() + ".png").toFile().length() > 1000);
        }
    }
}
