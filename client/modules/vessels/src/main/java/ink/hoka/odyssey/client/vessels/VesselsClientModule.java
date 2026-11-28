package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import ink.hoka.odyssey.client.api.ModelHandle;
import ink.hoka.odyssey.client.model.BbModel;
import ink.hoka.odyssey.client.vessels.hud.McGfx;
import ink.hoka.odyssey.client.vessels.hud.MenuInfo;
import ink.hoka.odyssey.client.vessels.hud.MenuLayout;
import ink.hoka.odyssey.client.vessels.hud.PilotHud;
import ink.hoka.odyssey.client.vessels.hud.ShopInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Клиентский модуль «vessels»: корабли-модели. Протокол — {@code modules/vessels/.../VesselsClient} на сервере.
 * Чужой корабль рисуется по носителю (item display: положение + левый поворот с интерполяцией), свой — по позе пилота.
 * ПКМ по кораблю (луч против треугольников модели) — запрос посадки; клик забираем, блок за кораблём не трогается.
 */
public final class VesselsClientModule implements ClientModule {

    private ClientContext ctx;
    private final Map<String, Kind> kinds = new HashMap<>();
    private JsonObject common = new JsonObject();
    private final Map<Integer, Ship> ships = new HashMap<>();
    private final Set<String> watched = new HashSet<>();
    private Pilot pilot;
    private final Decks decks = new Decks();
    private final Effects fx = new Effects();
    private ShipState state = new ShipState();
    /** HUD пилота (холсты) и его картинки в видеокарте. */
    private final PilotHud hud = new PilotHud();
    private final McGfx hudGfx = new McGfx("hud");
    /** Окно корабля: сводка последнего S2C MENU (по номеру окна и заголовку узнаём окно) и картинки фона. */
    private MenuInfo menuInfo;
    private final McGfx menuGfx = new McGfx("menu");
    /** Окно мастерской: сводка последнего S2C SHOP. */
    private ShopInfo shopInfo;
    private final McGfx shopGfx = new McGfx("shop");
    /** Карта: планета (подушка, лучи; S2C WORLD) или null — космос. */
    private Cushion.Settings world;
    /** Клавиши G и R в прошлом тике (нажатие — по фронту). */
    private boolean gWas, rWas;

    /** Корабль, который видит игрок: носитель, вид, модель. */
    private final class Ship implements Decks.Ship {
        final int id;
        int carrier;
        Kind kind;
        ModelHandle handle;

        Ship(int id) { this.id = id; }

        @Override public int id() { return id; }

        @Override public Kind kind() { return kind; }

        @Override public boolean poseNow(Vector3d pos, Quaternionf rot) { return pose(1f, pos, rot); }

        /** Поза корабля (середина модели в мире, поворот «система корабля → мир») на кадр; нет носителя — false. */
        boolean pose(float pt, Vector3d pos, Quaternionf rot) {
            if (pilot != null && pilot.vessel == id) {
                pilot.poseAt(pt, pos, rot);
                return true;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !(mc.level.getEntity(carrier) instanceof Display d) || d.renderState() == null) return false;
            Vec3 p = d.getPosition(pt);
            pos.set(p.x, p.y, p.z);
            Matrix4f m = new Matrix4f(d.renderState().transformation().get(d.calculateInterpolationProgress(pt)).getMatrix());
            m.getNormalizedRotation(rot);
            return true;
        }

        /** Поставить модель (вид пришёл или сменился). */
        void show() {
            if (handle != null) { handle.remove(); handle = null; }
            if (kind == null) return;
            Kind k = kind;
            handle = ctx.model(k.model).scale(k.scale).pose((pt, pos, rot) -> {
                if (!pose(pt, pos, rot)) return false;
                k.modelPose(pos, rot);                                          // середина корабля → начало модели
                return true;
            });
        }

        void remove() {
            if (handle != null) handle.remove();
            handle = null;
        }
    }

    @Override
    public void enable(ClientContext ctx) {
        this.ctx = ctx;
        ctx.onMessage(this::onMessage);
        ctx.onTick(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (pilot != null && (mc.player == null || mc.level == null)) stopPilot();
            if (pilot != null) pilot.tick();
            decks.own = pilot != null ? pilot.vessel : -1;
            decks.tick(mc.player, ships.values());                              // твёрдый корпус: перенос стоящих на корабле
            fx.tick();
            keys();
        });
        ctx.onWorld(fx::render);
        try {                                                                   // твёрдый корпус: столкновения (ядро 0.13+)
            ctx.onCollision(decks::collide);
        } catch (LinkageError old) {
            ctx.logger().warn("ядро мода без столкновений с телами — корабли проходимы насквозь до обновления мода");
        }
        ctx.onMouse((dy, dp) -> pilot != null && pilot.mouse(dy, dp));
        ctx.onCamera((s, pt) -> { if (pilot != null) pilot.camera(s, pt); });
        ctx.onInput(in -> { if (pilot != null) ctx.setMove(in, 0, 0); });       // пилот не ходит — клавиши читает Pilot
        ctx.onClick((attack, held) -> {
            if (pilot != null && pilot.editor != null) {                        // редактор камер: ЛКМ — сохранить, ПКМ — выйти
                if (!held) { if (attack) pilot.editorSave(); else pilot.stopEditor(); }
                return true;
            }
            if (pilot != null) {                                                // из кабины ничего не ставим и не ломаем; ЛКМ — огонь
                if (attack) pilot.fire();
                return true;
            }
            return !attack && !held && clickBoard();
        });
        try {                                                                   // окно корабля в стиле HUD (ядро 0.80+)
            ctx.onContainer(this::skin);
        } catch (LinkageError old) {
            ctx.logger().warn("ядро мода старше 0.80 — меню корабля в виде обычного сундука до обновления мода");
        }
        ctx.onHud((g, d) -> {
            if (pilot != null) pilot.hud(g, d, hud, hudGfx);
            fx.tunnel(g);                                                       // варп: туннель и у пилота, и у стоящих на корабле
        });
    }

    @Override
    public void disable() {
        stopPilot();
        decks.clear();
        fx.clear();
        hudGfx.release();
        menuGfx.release();
        shopGfx.release();
        menuInfo = null;
        shopInfo = null;
        world = null;
        for (Ship s : ships.values()) s.remove();
        ships.clear();
    }

    private void stopPilot() {
        if (pilot != null) pilot.end();
        pilot = null;
    }

    // ───────────────────────── сервер → мод ─────────────────────────

    private void onMessage(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            switch (in.readUnsignedByte()) {
                case 1 -> {                                                     // KINDS
                    byte[] b = new byte[in.readInt()];
                    in.readFully(b);
                    kinds(JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject());
                }
                case 2 -> {                                                     // ATTACH
                    int id = in.readInt(), carrier = in.readInt();
                    String kind = in.readUTF();
                    Ship s = ships.computeIfAbsent(id, Ship::new);
                    s.carrier = carrier;
                    Kind k = kinds.get(kind);
                    if (s.kind != k || s.handle == null) { s.kind = k; s.show(); }
                }
                case 3 -> {                                                     // DETACH
                    Ship s = ships.remove(in.readInt());
                    if (s != null) s.remove();
                }
                case 4 -> {                                                     // START
                    int id = in.readInt();
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    Quaternionf q = new Quaternionf(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
                    Ship s = ships.get(id);
                    if (s == null || s.kind == null) { ctx.logger().warn("корабль {}: нет вида — полёт не начат", id); return; }
                    stopPilot();
                    state = new ShipState();
                    pilot = new Pilot(ctx, id, s.kind, common, x, y, z, q, state, fx);
                    pilot.cushion = world;
                }
                case 5 -> stopPilot();                                          // END
                case 6 -> {                                                     // CORRECT
                    double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                    Quaternionf q = new Quaternionf(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
                    if (pilot != null) pilot.correct(x, y, z, q);
                }
                case 7 -> {                                                     // STATE
                    JsonObject o = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    state.read(o, pilot != null ? pilot.tickNo() : 0);
                }
                case 8 -> {                                                     // SHOT
                    in.readInt();
                    Vec3 from = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
                    Vec3 to = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
                    fx.shot(from, to, in.readUnsignedByte());
                }
                case 9 -> {                                                     // WARP
                    in.readInt();
                    int what = in.readUnsignedByte(), ticks = in.readInt();
                    fx.warp(what, ticks);
                }
                case 10 -> {                                                    // MSG
                    String text = in.readUTF();
                    boolean warn = in.readUnsignedByte() == 1;
                    if (pilot != null) pilot.message(text, warn);
                    else Minecraft.getInstance().gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(text)
                            .withStyle(warn ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.AQUA), false);
                }
                case 11 -> {                                                    // MENU
                    JsonObject o = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    MenuInfo m = new MenuInfo();
                    m.read(o);
                    if (menuInfo != null && menuInfo.window == m.window && menuInfo.title.equals(m.title)) menuInfo.read(o);   // открытое окно — на лету
                    else menuInfo = m;
                }
                case 12 -> {                                                    // HIT
                    int what = in.readUnsignedByte();
                    if (pilot != null) pilot.hit(what);
                }
                case 13 -> {                                                    // HULL: прочность своего корабля
                    JsonObject o = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    if (state.readHull(o) && pilot != null) pilot.hurt();
                }
                case 14 -> {                                                    // SHOP: окно мастерской
                    JsonObject o = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    ShopInfo m = ShopInfo.read(o);
                    ShopInfo was = shopInfo;
                    if (was != null && was.window == m.window && was.title.equals(m.title)) {   // открытое окно — на лету
                        was.recipes.clear();
                        was.recipes.addAll(m.recipes);
                    } else shopInfo = m;
                }
                case 16 -> {                                                    // CAMERAS: камеры своего корабля
                    ShipCams.Set c = ShipCams.Set.read(JsonParser.parseString(in.readUTF()));
                    if (pilot != null) pilot.cams = c;
                }
                case 17 -> {                                                    // CAMEDIT: открыть редактор камер
                    JsonObject o = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    if (pilot != null) pilot.startEditor(o);
                }
                case 18 -> {                                                    // CAMSAVED
                    JsonObject o = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    if (pilot != null) pilot.editorSaved(o);
                }
                case 19 -> {                                                    // AUTO: корабль ведёт сервер (вход в атмосферу / выход на орбиту)
                    JsonObject o = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    if (pilot != null) pilot.auto(o);
                }
                case 15 -> {                                                    // WORLD: планета или космос
                    JsonObject o = JsonParser.parseString(in.readUTF()).getAsJsonObject();
                    world = Cushion.Settings.read(o);
                    if (world != null && world.clearance() <= 0) world = null;          // clearance 0 — подушки нет
                    if (pilot != null) pilot.cushion = world;
                }
                default -> { }
            }
        } catch (IOException | RuntimeException e) {
            ctx.logger().warn("пакет кораблей не разобран: {}", e.toString());
        }
    }

    /** Виды кораблей: пересоздать, модели (треугольники для луча и корпуса) — по файлу, и при его смене. */
    private void kinds(JsonObject all) {
        JsonObject c = all.getAsJsonObject("_");
        common = c != null ? c : new JsonObject();
        decks.solid = flag("hull", "solid", true);
        decks.carry = flag("hull", "carry", true);
        try { fx.boltSpeed = common.getAsJsonObject("gun").get("bolt-speed").getAsDouble(); } catch (Exception ignored) { }
        Map<String, Kind> old = new HashMap<>(kinds);
        kinds.clear();
        for (Map.Entry<String, JsonElement> e : all.entrySet()) {
            if (e.getKey().equals("_") || !e.getValue().isJsonObject()) continue;
            try {
                Kind k = new Kind(e.getKey(), e.getValue().getAsJsonObject());
                kinds.put(k.id, k);
                Kind was = old.get(k.id);
                if (was != null && was.model.equals(k.model) && was.tris != null && was.scale == k.scale && was.turn == k.turn
                        && java.util.Arrays.equals(was.center, k.center)) {
                    k.adopt(was);
                } else {
                    ctx.file(k.model).ifPresent(b -> shape(k.model, b));
                }
                if (watched.add(k.model)) {
                    String path = k.model;
                    ctx.onFile(path, b -> shape(path, b));
                }
            } catch (RuntimeException ex) {
                ctx.logger().warn("вид корабля {} не разобран: {}", e.getKey(), ex.toString());
            }
        }
        for (Ship s : ships.values()) {                                         // виды сменились — модели заново
            if (s.kind == null) continue;
            s.kind = kinds.get(s.kind.id);
            s.show();
        }
    }

    /** Файл модели пришёл: форма для всех видов с этой моделью. */
    private void shape(String path, byte[] file) {
        try {
            float[] tris = BbModel.parse(path, JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject()).restTriangles();
            for (Kind k : kinds.values()) if (k.model.equals(path)) k.shape(tris);
        } catch (RuntimeException e) {
            ctx.logger().warn("модель корабля {} не разобрана: {}", path, e.toString());
        }
    }

    /** Окно игры открывается: окно корабля или мастерской (S2C MENU / SHOP пришёл до него) — своё оформление. */
    private ClientContext.ContainerSkin skin(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen, int containerId) {
        ShopInfo sh = shopInfo;
        if (sh != null && sh.matches(containerId, screen.getTitle().getString())) {
            if (screen.getMenu() instanceof net.minecraft.world.inventory.ChestMenu cm && cm.getRowCount() == 6 && cm.slots.size() == ink.hoka.odyssey.client.vessels.hud.ShopArt.SLOTS)
                return new ShopMenu(sh, shopGfx);
            return null;
        }
        MenuInfo m = menuInfo;
        if (m == null || !m.matches(containerId, screen.getTitle().getString())) return null;
        if (!(screen.getMenu() instanceof net.minecraft.world.inventory.ChestMenu cm) || cm.getRowCount() != 5
                || cm.slots.size() != MenuLayout.SLOTS) return null;
        return new ShipMenu(m, cm, menuGfx, () -> { });
    }

    // ───────────────────────── ПКМ — посадка ─────────────────────────

    /** ПКМ по кораблю: board-reach; на планете — дальше (луч поднимает), до beam-reach. */
    private double reach() {
        double r;
        try { r = common.has("board-reach") ? common.get("board-reach").getAsDouble() : 6; } catch (Exception e) { r = 6; }
        return world != null ? Math.max(r, world.beamReach()) : r;
    }

    private boolean flag(String section, String key, boolean def) {
        try { return common.getAsJsonObject(section).get(key).getAsBoolean(); } catch (Exception e) { return def; }
    }

    private double menuReach() {
        double r;
        try { r = common.has("menu-reach") ? common.get("menu-reach").getAsDouble() : 6; } catch (Exception e) { r = 6; }
        return world != null ? Math.max(r, world.beamReach()) : r;
    }

    /** G — меню корабля (пилот — своего, иначе — на который смотрит); R — варп (пилот). По нажатию, без экранов. */
    private void keys() {
        Minecraft mc = Minecraft.getInstance();
        boolean free = mc.screen == null && mc.player != null;
        var win = mc.getWindow();
        boolean g = free && com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, org.lwjgl.glfw.GLFW.GLFW_KEY_G);
        boolean r = free && com.mojang.blaze3d.platform.InputConstants.isKeyDown(win, org.lwjgl.glfw.GLFW.GLFW_KEY_R);
        boolean editing = pilot != null && pilot.editor != null;               // редактор камер: G и R — его (R — сброс)
        if (g && !gWas && !editing) {
            int id = pilot != null ? pilot.vessel : -1;
            if (id < 0) { Ship s = looked(menuReach()); if (s != null) id = s.id; }
            if (id >= 0) ctx.send(new byte[]{4, (byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id});   // MENU
        }
        if (r && !rWas && pilot != null && !editing && pilot.auto == null) ctx.send(new byte[]{6});    // WARP (не в переходе)
        gWas = g;
        rWas = r;
    }

    /** Луч от глаз по взгляду против кораблей; ближе блока мира — запрос посадки. */
    private boolean clickBoard() {
        Ship best = looked(reach());
        if (best == null) return false;
        int id = best.id;
        ctx.send(new byte[]{2, (byte) (id >>> 24), (byte) (id >>> 16), (byte) (id >>> 8), (byte) id});   // BOARD
        Minecraft.getInstance().player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return true;
    }

    /** Корабль под взглядом (по треугольникам модели) не дальше reach и ближе блока мира, или null. */
    private Ship looked(double reach) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || ships.isEmpty()) return null;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 eye = mc.player.getEyePosition(pt), look = mc.player.getViewVector(pt);
        double max = reach;
        HitResult hr = mc.hitResult;
        if (hr != null && hr.getType() != HitResult.Type.MISS) max = Math.min(max, hr.getLocation().distanceTo(eye));
        Ship best = null;
        double bestT = Double.MAX_VALUE;
        Vector3d pos = new Vector3d();
        Quaternionf rot = new Quaternionf();
        for (Ship s : ships.values()) {
            if (s.kind == null || !s.pose(pt, pos, rot)) continue;
            Quaternionf inv = new Quaternionf(rot).conjugate();
            Vector3f o = inv.transform(new Vector3f((float) (eye.x - pos.x), (float) (eye.y - pos.y), (float) (eye.z - pos.z)));
            Vector3f d = inv.transform(new Vector3f((float) look.x, (float) look.y, (float) look.z)).normalize();
            double t = MeshRay.hit(new double[]{o.x, o.y, o.z}, new double[]{d.x, d.y, d.z}, max, s.kind.half, s.kind.tris);
            if (t >= 0 && t < bestT) { bestT = t; best = s; }
        }
        return best;
    }

}
