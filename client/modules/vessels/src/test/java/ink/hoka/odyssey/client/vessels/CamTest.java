package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonParser;
import ink.hoka.odyssey.client.vessels.hud.EditorHud;
import ink.hoka.odyssey.client.vessels.hud.EditorView;
import ink.hoka.odyssey.client.vessels.hud.PngGfx;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** vessels-5, п.3: камеры у мода — мир ↔ корабль (с поворотом), перебор F5, редактор (правка, черновики, сброс), превью HUD. */
class CamTest {

    static ShipCams.Cam cam(double x, double y, double z, double yaw, double pitch) { return new ShipCams.Cam(x, y, z, yaw, pitch, 0, null, Double.NaN); }

    @Test
    void worldShipRoundTrip() {
        Quaternionf rot = new Quaternionf().rotationY((float) Math.toRadians(-73)).rotateX((float) Math.toRadians(18)).rotateZ((float) Math.toRadians(-25));
        Vector3d pos = new Vector3d(1200.5, 88, -431.25);
        Vector3d w = ShipCams.toWorld(2, 3.5, -14, pos, rot);
        Vector3d l = ShipCams.toLocal(w.x, w.y, w.z, pos, rot);
        assertEquals(2, l.x, 1e-4);
        assertEquals(3.5, l.y, 1e-4);
        assertEquals(-14, l.z, 1e-4);
        // поворот взгляда: курс/наклон в корабле → мир → обратно
        for (double[] a : new double[][]{{0, 0}, {35, 12}, {-120, -40}, {179, 5}}) {
            Quaternionf view = ShipCams.viewWorld(rot, a[0], a[1]);
            Vector3f dir = view.transform(new Vector3f(0, 0, 1));
            double[] back = ShipCams.anglesLocal(dir, rot);
            assertEquals(a[0], back[0], 1e-3, "курс");
            assertEquals(a[1], back[1], 1e-3, "наклон");
        }
        // без поворота корабля: курс 90 — вправо (у корабля правый борт −X), наклон + — вниз
        Vector3f d = ShipCams.orient(90, 0).transform(new Vector3f(0, 0, 1));
        assertEquals(-1, d.x, 1e-5);
        Vector3f dn = ShipCams.orient(0, 30).transform(new Vector3f(0, 0, 1));
        assertTrue(dn.y < -0.49);
    }

    @Test
    void lookAtAndPullIn() {
        double[] a = ShipCams.lookAt(0, 4, -14, new double[]{0, 0, 0});        // сзади-сверху на середину — вперёд и вниз
        assertEquals(0, a[0], 1e-9);
        assertEquals(Math.toDegrees(Math.atan2(4, 14)), a[1], 1e-9);
        double[] side = ShipCams.lookAt(9, 0, 0, new double[]{0, 0, 0});       // с левого борта — смотрит вправо
        assertEquals(90, side[0], 1e-9);
        ShipCams.Cam c = new ShipCams.Cam(0, 4, -14, 0, 0, 0, new double[]{0, 0, 0}, 1);
        assertEquals(a[1], ShipCams.angles(c)[1], 1e-9);
        assertEquals(1, ShipCams.pullIn(1, 15), 1e-9, "блоков нет — на месте");
        assertEquals(0.5 - 0.3 / 10, ShipCams.pullIn(0.5, 10), 1e-9, "блок посередине — ближе, за 0.3 до него");
        assertEquals(0, ShipCams.pullIn(0.01, 10), 1e-9);
    }

    @Test
    void f5Cycle() {
        assertEquals(1, ShipCams.nextView(0, 0), "своих нет — «сзади»");
        assertEquals(0, ShipCams.nextView(1, 0));
        int v = 0;
        int[] seen = new int[4];
        for (int i = 0; i < 4; i++) { v = ShipCams.nextView(v, 3); seen[i] = v; }
        assertArrayEquals(new int[]{1, 2, 3, 0}, seen, "первое лицо → 1 → 2 → 3 → первое лицо");
    }

    @Test
    void parseServerJson() {
        ShipCams.Set s = ShipCams.Set.read(JsonParser.parseString("{\"first\":{\"pos\":[0,1.2,2.5],\"yaw\":3,\"pitch\":5,\"fov\":80},"
                + "\"f5\":[{\"pos\":[0,4,-14],\"yaw\":0,\"pitch\":12,\"smooth\":0.15},{\"pos\":[9,3,0],\"look\":[0,0,0]},{\"bad\":1}]}"));
        assertEquals(80, s.first().fov(), 1e-9);
        assertEquals(2, s.f5().size(), "плохая камера пропущена");
        assertEquals(0.15, s.f5().get(0).smooth(), 1e-9);
        assertNotNull(s.f5().get(1).look());
        assertTrue(Double.isNaN(s.f5().get(1).smooth()));
        assertSame(ShipCams.Set.EMPTY, ShipCams.Set.read(null));
        // туда-обратно: что редактор шлёт, то и читается
        ShipCams.Cam c = new ShipCams.Cam(1.5, 2, -3, 10, -5, 75, new double[]{0, 1, 0}, 0.4);
        assertEquals(c.look()[1], ShipCams.Cam.read(c.json()).look()[1], 1e-9);
        assertEquals(c.fov(), ShipCams.Cam.read(c.json()).fov(), 1e-9);
    }

    static CamEditor editor(ShipCams.Set own, ShipCams.Set eff, int which) {
        return new CamEditor(false, "corabll", "вид corabll", which, own, eff, cam(0, 1, 2.5, 0, 0), ShipCams.defaultChase(8, 1.2, 0.35));
    }

    @Test
    void editorStartsFromSavedCamera() {
        ShipCams.Cam saved = cam(1, 2, 3, 40, 10);
        CamEditor e = editor(new ShipCams.Set(saved, List.of(cam(0, 5, -12, 0, 15))), ShipCams.Set.EMPTY, 0);
        assertEquals(saved, e.cur, "правка существующей — с её точки и углов");
        assertNotNull(e.saved(0));
        e.select(1);
        assertEquals(cam(0, 5, -12, 0, 15), e.cur);
        assertTrue(e.available(2), "следующий новый вид F5 — можно");
        assertFalse(e.available(3), "через один — нельзя");
        e.select(3);
        assertEquals(2, e.which, "дальше конца — на следующий новый");
        assertEquals(ShipCams.defaultChase(8, 1.2, 0.35), e.cur, "новой камеры нет — с камеры по умолчанию");
        // у корабля: камеры вида — как начало
        CamEditor s = new CamEditor(true, "7", "корабль #7", 0, ShipCams.Set.EMPTY, new ShipCams.Set(saved, List.of()), cam(0, 1, 2.5, 0, 0), cam(0, 3, -10, 0, 8));
        assertEquals(saved, s.cur);
        assertNull(s.saved(0));
        assertEquals(EditorView.INHERITED, s.view(70, 0.15, 0).states()[0] & EditorView.INHERITED);
    }

    @Test
    void draftsResetAndSave() {
        CamEditor e = editor(new ShipCams.Set(cam(1, 2, 3, 0, 0), List.of()), ShipCams.Set.EMPTY, 0);
        e.move(1, 0, 0, false, false);                                       // вперёд (курс 0 — по +Z)
        assertEquals(3 + 4 / 20.0, e.cur.z(), 1e-9);
        e.move(0, 1, 0, true, false);                                        // влево медленно — по +X
        assertEquals(1 + 4 / 20.0 * 0.2, e.cur.x(), 1e-9);
        e.move(0, 0, 1, false, true);                                        // вверх быстро
        assertEquals(2 + 4 / 20.0 * 4, e.cur.y(), 1e-9);
        assertTrue(e.changed(0));
        e.select(1);                                                         // к другой камере — правка остаётся черновиком
        assertTrue(e.changed(0));
        assertEquals(EditorView.DIRTY, e.view(70, 0.15, 0).states()[0] & EditorView.DIRTY);
        e.select(0);
        assertEquals(3 + 0.2, e.cur.z(), 1e-9, "черновик вернулся");
        e.reset();
        assertEquals(cam(1, 2, 3, 0, 0), e.cur, "сброс — к сохранённой");
        assertFalse(e.changed(0));
        e.mouse(30, 10);
        assertEquals(30, e.cur.yaw(), 1e-9);
        e.wheelFov(-5, 70);
        assertEquals(80, e.cur.fov(), 1e-9);
        // сохранить: сервер ответил CAMSAVED — «уже есть», черновик снят
        String js = e.saveJson();
        assertEquals(0, JsonParser.parseString(js).getAsJsonObject().get("which").getAsInt());
        ShipCams.Cam sent = ShipCams.Cam.read(JsonParser.parseString(js).getAsJsonObject().get("cam"));
        e.saved(0, new ShipCams.Set(sent, List.of()), new ShipCams.Set(sent, List.of()), "Сохранено");
        assertFalse(e.changed(0));
        assertEquals(sent, e.saved(0));
        // плавность и «смотреть на корабль» — только у видов F5
        e.smooth(1, 0.15);
        assertTrue(Double.isNaN(e.cur.smooth()));
        e.select(1);
        e.smooth(1, 0.15);
        assertEquals(0.2, e.cur.smooth(), 1e-9);
        e.toggleLook();
        assertNotNull(e.cur.look());
        e.mouse(50, 0);
        assertNotNull(e.cur.look(), "мышь не крутит взгляд на корабль");
        e.cycle(1);
        assertEquals(0, e.which, "по кругу: после последнего доступного — первое лицо");
        e.wheelSpeed(-3);
        assertEquals(4 * Math.pow(1.25, 3), e.speed, 1e-9);
    }

    @Test
    void editorHudPreview() throws Exception {
        CamEditor e = editor(new ShipCams.Set(cam(0, 1.4, 2.6, 0, 4), List.of(cam(0, 4, -14, 0, 12), cam(9, 3, 0, 90, 0))), ShipCams.Set.EMPTY, 2);
        e.mouse(-12, 3);
        e.move(1, 0, 0, false, false);
        e.wheelFov(-4, 70);
        e.select(1);
        e.select(2);
        e.status("Сохранено: вид F5 1 (вид corabll)", false);
        for (int[] sz : new int[][]{{640, 360, 3}, {320, 240, 4}, {960, 540, 2}}) {
            PngGfx g = new PngGfx(sz[0], sz[1], sz[2]).space(17);
            EditorView v = e.view(70, 0.15, System.currentTimeMillis());
            new EditorHud().draw(g, v);
            String name = "camedit-" + sz[0] + "x" + sz[1] + "-gui" + sz[2];
            g.save(HudPreviewTest.out().resolve(name + ".png"));
            assertTrue(HudPreviewTest.out().resolve(name + ".png").toFile().length() > 1000);
        }
    }
}
