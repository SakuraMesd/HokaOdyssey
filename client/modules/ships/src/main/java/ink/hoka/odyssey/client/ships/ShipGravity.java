package ink.hoka.odyssey.client.ships;

import ink.hoka.odyssey.client.api.CameraState;
import ink.hoka.odyssey.client.api.ClientContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * Гравитация корабля: внутри корабля игрока всегда тянет к его полу — под любым углом, хоть вверх ногами.
 * Пока игрок внутри, его движение считает этот класс в системе корабля (как игра: разгон, трение, прыжок, тяжесть
 * 0.08), взгляд — тоже в системе корабля (курс/наклон относительно палубы), камера повёрнута вместе с кораблём.
 * Игре остаётся только сдвинуть игрока (столкновения с кораблём — в ядре, в той же системе).
 * Вышел из корабля — скорость и взгляд отдаются игре как есть.
 */
final class ShipGravity {

    /** Корабль, в котором игрок: его система → мир (на конец тика и на кадр) и блоки. */
    interface Ship {
        Matrix4f place();

        Matrix4f placeAt(float partialTick);

        ShipMesh mesh();
    }

    private final ClientContext ctx;
    private Ship on;
    /** Ноги в системе корабля в прошлый тик (настоящий сдвиг = разница, со всеми упорами). */
    private Vector3d last;
    private boolean ground;
    /** Взгляд в системе корабля: курс и наклон относительно палубы (как у игры). */
    private float lyaw, lpitch;
    // ввод этого тика
    private float fwd, strafe;
    private boolean jump, sneak, sprint;

    ShipGravity(ClientContext ctx) { this.ctx = ctx; }

    boolean active() { return on != null; }

    boolean isOn(ShipMesh m) { return on != null && on.mesh() == m; }

    /** Поворот корабля, в котором игрок (для глаз), или null. */
    org.joml.Matrix3f rotation() { return on == null ? null : on.place().get3x3(new org.joml.Matrix3f()); }

    // ───────────────────────── вход / выход ─────────────────────────

    /** Точка (ноги, мир) внутри коробки блоков корабля с запасом m. */
    static boolean inside(Ship s, Vec3 pos, float m) {
        Vector3d lp = local(s.place(), pos);
        ShipMesh mesh = s.mesh();
        return lp.x > mesh.lo[0] - m && lp.x < mesh.hi[0] + m && lp.y > mesh.lo[1] - m - 0.5 && lp.y < mesh.hi[1] + m
                && lp.z > mesh.lo[2] - m && lp.z < mesh.hi[2] + m;
    }

    /** Поворот корабля, в котором игрок, на кадр (для модели игрока), или null. */
    Quaternionf shipRotation(float pt) {
        return on == null ? null : on.placeAt(pt).getNormalizedRotation(new Quaternionf());
    }

    /** Каждый тик: игрок внутри какого корабля (по коробке его блоков) — с запасом, чтобы не дёргало на краю. */
    void select(Iterable<Ship> ships, boolean piloting) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || piloting || p.isSpectator() || p.isPassenger()) { leave(p, !piloting); return; }
        Ship found = null;
        if (cooldown > 0) cooldown--;
        for (Ship s : ships) {
            if (!inside(s, p.position(), on != null && on.mesh() == s.mesh() ? 0.9f : 0.5f)) continue;
            // только что вышли — обратно лишь если правда стоим на палубе (иначе на краю дёргает туда-сюда)
            if (on == null && cooldown > 0 && !grounded(s.mesh(), local(s.place(), p.position()))) continue;
            found = s;
            break;
        }
        if (found == null) { leave(p, true); return; }
        // полёт (креатив, будущие ранцы): у корабля в верфи — летаем по его осям (игра летит в системе верфи); у старых — как раньше
        if (p.getAbilities().flying && found.mesh().yard == null) { leave(p, true); return; }
        if (on == null || on.mesh() != found.mesh()) enter(p, found);
        else on = found;
        look(p);
        if (on.mesh().yard == null) p.resetFallDistance();                      // в верфи падение считает игра (звук и пыль приземления)
        p.setNoGravity(true);                                                   // тяжесть — наша (ядро не прижимает второй раз)
    }

    /** Слезли с кресла: на этом корабле встать в эту точку верфи (ноги), пока сам вход ещё не случился. */
    private ShipMesh seedMesh;
    private Vector3d seedYard;

    void seedDeck(ShipMesh mesh, Vector3d yardFeet) {
        seedMesh = mesh;
        seedYard = yardFeet;
    }

    private void enter(LocalPlayer p, Ship s) {
        if (on != null) leave(p, true);
        Vector3f fromUp = shownUp(1f);                                          // «верх» до входа (мир или идущий переход)
        on = s;
        shipUp = true;                                                          // во всей зоне корабля — его тяжесть (и в полёте)
        freeTicks = 0;
        endYard = endWorld = null;
        yardPos = wSet = null;
        if (seedMesh == s.mesh() && seedYard != null) {                         // с кресла: стоим ровно здесь на палубе
            yardPos = seedYard;
            wSet = new Vector3d(p.getX(), p.getY(), p.getZ());
        }
        seedMesh = null;
        seedYard = null;
        lBody = Float.NaN;
        Matrix4d w = new Matrix4d(s.place());
        Matrix3d inv = w.get3x3(new Matrix3d()).transpose();
        // взгляд игры → в систему корабля
        Vector3d d = inv.transform(dir(p.getYRot(), p.getXRot()));
        lyaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        lpitch = (float) Math.toDegrees(Math.asin(Math.clamp(-d.y, -1, 1)));
        Vec3 dm = p.getDeltaMovement();
        Vector3d lv = inv.transform(new Vector3d(dm.x, dm.y, dm.z));
        last = local(s.place(), p.position()).sub(lv);
        ground = false;
        ctx.send(new byte[]{3, 1});                                             // серверу: внутри — вакуум не трогает
        startBlendFrom(fromUp);
        ctx.logger().info("[гравитация корабля] внутри");
    }

    /** Тиков после выхода, когда корабль не затягивает обратно. */
    private int cooldown;

    private void leave(LocalPlayer p, boolean keepSpeed) {
        if (on == null) return;
        cooldown = 10;
        Vector3f fromUp = shownUp(1f);
        if (p != null) p.setNoGravity(false);
        on = null;
        startBlendFrom(fromUp);
        endYard = endWorld = null;
        yardPos = wSet = null;
        if (p != null && !Float.isNaN(lBody)) { p.xBob = p.xBobO = p.getXRot(); p.yBob = p.yBobO = p.getYRot(); }
        lBody = Float.NaN;
        last = null;
        ctx.send(new byte[]{3, 0});
        ctx.logger().info("[гравитация корабля] снаружи");
    }

    // ───────────────────────── ввод и мышь ─────────────────────────

    /** Ввод игры — забрать себе (ходим сами, в системе корабля), игре — ничего (иначе пошла бы по миру). */
    void input(ClientInput in) {
        if (on == null) return;
        Input k = in.keyPresses;
        fwd = (k.forward() ? 1 : 0) - (k.backward() ? 1 : 0);
        strafe = (k.left() ? 1 : 0) - (k.right() ? 1 : 0);
        jump = k.jump();
        sneak = k.shift();
        sprint = k.sprint() && fwd > 0 && !sneak;
        if (yardReady(Minecraft.getInstance().player)) return;                  // ходьба по настоящим блокам — клавиши игре
        in.keyPresses = new Input(false, false, false, false, false, sneak, false);   // присед — игре (поза), ход — наш
        ctx.setMove(in, 0, 0);
    }

    /** Мышь — взгляд в системе корабля. */
    boolean mouse(double dyaw, double dpitch) {
        if (on == null) return false;
        lyaw += (float) dyaw;
        lpitch = Math.clamp(lpitch + (float) dpitch, -90f, 90f);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) look(p);
        return true;
    }

    /** Взгляд игрока в мире — из взгляда в системе корабля (для сервера, луча, модели игрока). */
    private void look(LocalPlayer p) {
        Matrix3d r = new Matrix4d(on.place()).get3x3(new Matrix3d());
        Vector3d d = r.transform(dir(lyaw, lpitch));
        float wy = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), wp = (float) Math.toDegrees(Math.asin(Math.clamp(-d.y, -1, 1)));
        wy = p.getYRot() + Mth.wrapDegrees(wy - p.getYRot());                    // без скачка через ±180
        float dy = wy - p.getYRot(), dp = wp - p.getXRot(), dh = Mth.wrapDegrees(wy - p.getYHeadRot());
        p.setYRot(wy);
        p.setXRot(wp);
        p.yRotO += dy;                                                          // как Entity.turn: поворот мышью — сразу, без догоняния
        p.xRotO += dp;
        p.yHeadRotO += dh;
        p.setYHeadRot(p.getYHeadRot() + dh);
    }

    // ───────────────────────── движение ─────────────────────────


    // ───────────────────────── ходьба по настоящим блокам (верфь) ─────────────────────────

    /**
     * Тяжесть корабля (true) или мира (false). У корабля в верфи: стоишь на его полу или над головой его крыша —
     * корабля; снаружи у обшивки (бок, днище наклонённого корабля) — мира, а столкновения всё равно с настоящими блоками.
     */
    private boolean shipUp = true;
    /** Юзер 25ноя: «во всей зоне корабля не должен переворачиваться» — тяжесть корабля везде в его зоне (не только на полу). */
    static final boolean ZONE_GRAVITY = true;
    private int freeTicks;

    /** После шага в верфи: где тяжесть — пол корабля под ногами / крыша над головой — корабля, иначе (через 6 тиков) мира. */
    private void updateShipUp(LocalPlayer p) {
        if (on == null || on.mesh().yard == null || ZONE_GRAVITY) { shipUp = true; return; }
        boolean deck = p.onGround() && shipUp;                                  // стоим на полу корабля (Y верфи)
        if (!deck && p.onGround()) {                                            // при мировой тяжести: «пол» верфи под ногами?
            var below = p.blockPosition().below();
            deck = !p.level().getBlockState(below).getCollisionShape(p.level(), below).isEmpty() && p.getDeltaMovement().y <= 0;
        }
        boolean roof = false;
        var head = p.blockPosition().above(2);
        for (int i = 0; i < 4 && !roof; i++) roof = !p.level().getBlockState(head.above(i)).isAir();
        boolean want = deck || roof;
        if (want) freeTicks = 0;
        else if (++freeTicks < 6) want = shipUp;                                // короткий прыжок/шаг через проём — не переключать
        if (want != shipUp) {
            Vector3f from = shownUp(1f);
            shipUp = want;
            startBlendFrom(from);
        }
    }

    /** Шаг движения идёт в системе верфи: мир → верфь (на этот тик), сохранённый взгляд мира. */
    private Matrix4d toYard;
    private float savedYaw, savedPitch;
    /** Скорость в конце прошлого шага: в системе верфи и то, что отдали игре (мир) — узнать, что игра поменяла до шага. */
    private Vector3d endYard, endWorld;
    /** Отладочный лог ходьбы [верфь]/[палуба] (выключен: пишет каждый тик). */
    static boolean DEBUG = false;
    private int dbg;
    private static String f2(double d) { return String.format(java.util.Locale.ROOT, "%.2f", d); }

    /**
     * Корабль в верфи и его блоки у мода есть: движение игрока на этот шаг считает САМА игра — в координатах верфи,
     * где лежат настоящие блоки корабля. Отсюда всё как на земле: ступени и полублоки, лестницы, трение блоков,
     * присед у края, покачивание камеры, звуки шагов (переносятся в мир). После шага — обратно в мир (travelEnd).
     */
    /** Мир → верфь для корабля, на котором игрок (или null — корабль не в верфи). */
    private Matrix4d yardMatrix() {
        int[] y = on == null ? null : on.mesh().yard;
        if (y == null) return null;
        return new Matrix4d().translation(y[0] + 0.5, y[1] + on.mesh().seatH, y[2] + 0.5).mul(new Matrix4d(on.place()).invert());
    }

    /** Ходьба пойдёт по настоящим блокам верфи (корабль в верфи и его блоки у мода есть). */
    private boolean yardReady(LocalPlayer p) {
        Matrix4d m = yardMatrix();
        if (m == null || p == null || p.level() == null) return false;
        Vector3d at = m.transformPosition(new Vector3d(p.getX(), p.getY(), p.getZ()));
        return p.level().hasChunkAt(net.minecraft.core.BlockPos.containing(at.x, at.y, at.z));
    }

    /** Ядро 0.36+: в систему верфи переводится ВЕСЬ тик игрока (иначе — только шаг travel, как раньше). */
    boolean wholeTick;
    /** Ядро 0.39+: рука от первого лица считается от палубного взгляда. */
    boolean handLocal;
    /** Ноги в верфи в конце прошлого тика и мировая точка, куда тогда поставили игрока (не двигали — едем с палубой). */
    private Vector3d yardPos, wSet;
    /** Мировые «прошлые» координаты начала тика (для плавной отрисовки) и поворот курса мир−палуба на этот тик. */
    private double wxo, wyo, wzo;
    private float dyaw, dpitch;
    /**
     * Углы, которые игра считает сама (тело, голова, раскачка руки) — ХРАНЯТСЯ в системе палубы между тиками. Через
     * мировой курс их гонять нельзя: на наклонённом корабле мировой курс взгляда прыгает, стоит посмотреть вверх/вниз,
     * и тело в F5 разворачивало, а руку от первого лица вытягивало. NaN — ещё не было тика на палубе.
     */
    private float lBody = Float.NaN, lBodyO, lHeadO, lxBob, lyBob, lxBobO, lyBobO;

    /**
     * Вход в систему верфи (начало тика игрока): позиция, «прошлая» позиция, скорость, все углы — в координаты верфи.
     * Дальше игра сама считает весь тик по настоящим блокам корабля: ходьбу, анимацию ног, поворот тела, частицы бега,
     * присед у края, падение. Выход — {@link #frameExit}.
     */
    void frameEnter(LocalPlayer p) {
        if (on == null || toYard != null || !yardReady(p)) return;              // блоки верфи ещё не пришли
        Matrix4d m = yardMatrix();
        Vector3d at;
        if (yardPos != null && wSet != null && p.getX() == wSet.x && p.getY() == wSet.y && p.getZ() == wSet.z)
            at = new Vector3d(yardPos);                                         // никто не двигал — стоим там же на палубе (корабль везёт)
        else
            at = m.transformPosition(new Vector3d(p.getX(), p.getY(), p.getZ()));   // телепорт, толчок, только вошли
        Vector3d now = new Vector3d(p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z);
        Vector3d v = endYard != null && endWorld != null && now.distanceSquared(endWorld) < 1e-10
                ? new Vector3d(endYard)                                         // скорость не трогали — в системе корабля как была
                : m.transformDirection(now);                                    // толчок извне — как есть
        savedYaw = p.getYRot();
        savedPitch = p.getXRot();
        toYard = m;
        ctx.setSoundFrame(new Matrix4d(m).invert());                            // звуки и частицы — из верфи в мир
        {                                                                       // тяжесть — по видимому «верху» (корабль, мир или переход)
            Vector3f su = shownUp(1f);
            Vector3d upL = m.transformDirection(new Vector3d(su.x, su.y, su.z)).normalize();
            if (upL.y < 0.9999 && !p.getAbilities().flying) v.add(-0.08 * 0.98 * upL.x, 0.08 * 0.98 * (1 - upL.y), -0.08 * 0.98 * upL.z);   // игра вычтет 0.08 по Y верфи
        }
        p.setNoGravity(false);                                                  // тяжесть — игры (в системе верфи «вниз» = к полу корабля)
        if (wholeTick) {                                                        // «прошлое» — тоже в верфи: анимация ног и тело по шагу на палубе
            wxo = p.xo; wyo = p.yo; wzo = p.zo;
            Vector3d o = m.transformPosition(new Vector3d(p.xo, p.yo, p.zo));
            if (o.distanceSquared(at) > 4) o.set(at);                           // скачок (вход, телепорт) — без шага
            p.xo = p.xOld = o.x; p.yo = p.yOld = o.y; p.zo = p.zOld = o.z;
        }
        p.setPos(at.x, at.y, at.z);
        p.setDeltaMovement(v.x, v.y, v.z);
        dyaw = savedYaw - lyaw;
        dpitch = savedPitch - lpitch;
        p.setYRot(lyaw);
        p.setXRot(lpitch);
        if (wholeTick) {
            p.yRotO -= dyaw; p.xRotO -= dpitch;
            if (Float.isNaN(lBody)) {                                           // первый тик на палубе — из мировых
                lBody = lBodyO = p.yBodyRot - dyaw; lHeadO = lyaw;
                lxBob = lxBobO = lpitch; lyBob = lyBobO = lyaw;
            }
            p.yBodyRot = lBody; p.yBodyRotO = lBodyO;
            p.yHeadRot = lyaw; p.yHeadRotO = lHeadO;
            p.xBob = lxBob; p.xBobO = lxBobO; p.yBob = lyBob; p.yBobO = lyBobO;
        }
    }

    /** Взгляд на палубе {курс, наклон} для руки от первого лица (или null — как у игры). */
    float[] localView() {
        if (on == null || on.mesh().yard == null || !wholeTick || Float.isNaN(lBody)) return null;
        return new float[]{lyaw, lpitch};
    }

    /** Поза своей модели на палубе: поворот — корабля (система взгляда lyaw), углы — палубные. Иначе null. */
    ink.hoka.odyssey.client.api.ClientContext.EntityPose pose(float pt) {
        if (on == null || on.mesh().yard == null || !wholeTick || Float.isNaN(lBody)) return null;
        Quaternionf q = on.placeAt(pt).getNormalizedRotation(new Quaternionf());
        Vector3f su = shownUp(pt), shipUp = q.transform(new Vector3f(0, 1, 0));
        if (su.dot(shipUp) < 0.9999f) q = new Quaternionf().rotationTo(shipUp, su).mul(q);   // переход тяжести — модель плавно за ним
        return new ink.hoka.odyssey.client.api.ClientContext.EntityPose(q, Mth.rotLerp(pt, lBodyO, lBody), lyaw, lpitch);
    }

    /** Идёт тик в системе верфи: мир → верфь (для земли мира как тела столкновений), иначе null. */
    Matrix4d yardFrame() { return toYard; }

    /** Конец тика: из верфи обратно в мир (положение, «прошлое», скорость, углы) — до отправки позиции серверу. */
    void frameExit(LocalPlayer p) {
        if (toYard == null) return;
        Matrix4d back = new Matrix4d(toYard).invert();
        toYard = null;
        ctx.setSoundFrame(null);
        if (DEBUG && (++dbg % 100 == 0 || p.input.getMoveVector().lengthSquared() > 0 && dbg % 10 == 0)) {
            ctx.logger().info("[верфь] ноги {} {} {} | земля {} | шаг {} | ноги.скор {} | yaw {} lyaw {} | кор.тяж {}", f2(p.getX()), f2(p.getY()), f2(p.getZ()),
                    p.onGround(), f2(Math.hypot(p.getX() - p.xo, p.getZ() - p.zo)), f2(p.walkAnimation.speed()), f2(savedYaw), f2(lyaw), shipUp);
        }
        updateShipUp(p);                                                        // ещё в системе верфи: пол/крыша — настоящие блоки
        yardPos = new Vector3d(p.getX(), p.getY(), p.getZ());
        Vector3d w = back.transformPosition(new Vector3d(yardPos));
        endYard = new Vector3d(p.getDeltaMovement().x, p.getDeltaMovement().y, p.getDeltaMovement().z);
        Vector3d v = back.transformDirection(new Vector3d(endYard));
        endWorld = new Vector3d(v);
        p.setPos(w.x, w.y, w.z);
        if (wholeTick) {
            p.xo = p.xOld = wxo; p.yo = p.yOld = wyo; p.zo = p.zOld = wzo;
            p.yRotO += dyaw; p.xRotO += dpitch;
            lBody = p.yBodyRot; lBodyO = p.yBodyRotO; lHeadO = p.yHeadRot;
            lxBob = p.xBob; lxBobO = p.xBobO; lyBob = p.yBob; lyBobO = p.yBobO;
            p.yBodyRot += dyaw; p.yBodyRotO += dyaw;
            p.yHeadRot += dyaw; p.yHeadRotO += dyaw;
            // след руки (xBob/yBob) остаётся палубным: рука считается от палубного взгляда (ядро 0.39: setLocalView)
            if (!handLocal) { p.xBob += dpitch; p.xBobO += dpitch; p.yBob += dyaw; p.yBobO += dyaw; }
        }
        p.setDeltaMovement(v.x, v.y, v.z);
        p.setYRot(p.getYRot() + dyaw);
        p.setXRot(Mth.clamp(p.getXRot() + dpitch, -90f, 90f));
        p.setNoGravity(true);                                                   // вне тика — как раньше (тяжесть корабля наша)
        wSet = new Vector3d(p.getX(), p.getY(), p.getZ());
        last = local(on != null ? on.place() : new Matrix4f(), p.position());
    }

    /** Старое ядро (до 0.36): в верфи только шаг travel. */
    void travelEnd(LocalPlayer p) { if (!wholeTick) frameExit(p); }

    /** Перед движением игрока: скорость в системе корабля (как у игры) → в мир. */
    void travel(LocalPlayer p) {
        if (on == null) return;
        if (!wholeTick) frameEnter(p);
        if (toYard != null) return;                                             // ходит игра — по настоящим блокам верфи
        // блоки верфи ещё не пришли (только подошёл к кораблю, перезаход) — стоять на месте, без тяжести: через долю
        // секунды ходьба пойдёт по настоящим блокам (старая самописная ходьба удалена — все корабли в верфи)
        p.setDeltaMovement(0, 0, 0);
    }

    /** Под ногами (в системе корабля) — блок корабля. */
    private boolean grounded(Vector3d lp, float height) { return grounded(on.mesh(), lp); }

    private static boolean grounded(ShipMesh m, Vector3d lp) {
        AABB probe = new AABB(lp.x - 0.299, lp.y - 0.05, lp.z - 0.299, lp.x + 0.299, lp.y + 0.0005, lp.z + 0.299);
        int x0 = (int) Math.floor(probe.minX + 0.5), x1 = (int) Math.floor(probe.maxX + 0.5);
        int y0 = (int) Math.floor(probe.minY + m.seatH), y1 = (int) Math.floor(probe.maxY + m.seatH);
        int z0 = (int) Math.floor(probe.minZ + 0.5), z1 = (int) Math.floor(probe.maxZ + 0.5);
        for (int dy = y0; dy <= y1; dy++) for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
            var sh = m.shape(dx, dy, dz);
            if (sh == null) continue;
            for (AABB b : sh.toAabbs()) if (b.intersects(probe)) return true;
        }
        return false;
    }

    // ───────────────────────── камера ─────────────────────────

    /** Поворот камеры — вместе с кораблём (w — вес перехода 0..1). Положение глаз ставит ShipsClientModule. */
    // ───────────────────────── смена гравитации: одна плавная кривая для всего ─────────────────────────

    /** Сколько длится смена «верха» (вошёл в корабль / вышел), секунд. */
    /** Длительность перехода: вход на корабль и выход (выход — быстрее). */
    private static final double ENTER_TIME = 1.0, LEAVE_TIME = 0.6;
    private double blendTime = ENTER_TIME;
    /** Откуда идёт «верх» (мир) и когда начался переход; null — перехода нет. */
    private Vector3f blendFrom;
    private long blendStart;
    /** Переход заметный (вход/выход — с затемнением и звуком) или тихий (из кресла пилота). */
    private boolean blendLoud;

    /** Переход от заданного «верха» (выход: «верх» корабля, посчитанный до того, как корабль забыт). */
    private void startBlendFrom(Vector3f from) {
        startBlend();
        blendFrom = new Vector3f(from);
    }

    /** Начать переход: «верх» поедет от того, что видно сейчас, к новому. */
    private void startBlend() {
        boolean quiet = blendFrom != null && !blendLoud && blendT() < 1;       // сразу после кресла пилота (ещё идёт) — без эффектов
        blendFrom = shownUp(1f);
        blendStart = System.nanoTime();
        blendTime = on != null ? ENTER_TIME : LEAVE_TIME;                      // вызывается после смены on: вошли / вышли
        blendLoud = !quiet;
        if (quiet) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null)                              // мягкий порыв воздуха
            mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(), net.minecraft.sounds.SoundEvents.WIND_CHARGE_BURST.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.12f, 1.6f, false);
    }

    /** Доля перехода 0..1 (1 — закончен или не было). */
    private double blendT() {
        if (blendFrom == null) return 1;
        double t = (System.nanoTime() - blendStart) / 1e9 / blendTime;
        if (t >= 1) { blendFrom = null; return 1; }
        return t;
    }

    /** Плавная кривая: медленный разгон, медленная остановка. */
    private static double ease(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    /** Настоящий «верх» тяжести (мир): верх корабля или неба. */
    private Vector3f targetUp(float pt) {
        if (on == null || !shipUp) return new Vector3f(0, 1, 0);
        return on.placeAt(pt).getNormalizedRotation(new Quaternionf()).transform(new Vector3f(0, 1, 0));
    }

    /** «Верх», который сейчас видно (камера, глаза, модель, тяжесть): во время перехода — плавно к настоящему. */
    Vector3f shownUp(float pt) {
        Vector3f to = targetUp(pt);
        double t = blendT();
        if (t >= 1) return to;
        Quaternionf r = new Quaternionf().rotationTo(blendFrom, to);
        return new Quaternionf().slerp(r, (float) ease(t)).transform(new Vector3f(blendFrom)).normalize();
    }

    /** Насколько сейчас заметен переход (0..1, пик в середине) — для затемнения краёв экрана. */
    float pulse() {
        double t = blendT();
        return t >= 1 || !blendLoud ? 0 : (float) Math.sin(Math.PI * t);
    }

    /** Выход из кресла пилота: «верх» — как был в кабине (верх корабля), без перехода. */
    void seedUp(Vector3f up) {
        if (up == null) return;
        blendFrom = new Vector3f(up).normalize();
        blendStart = System.nanoTime();
        blendLoud = false;
    }

    /** Камеру ведёт кто-то другой (пилот). */
    void resetView() { }

    /** Модель своего игрока: повёрнута от «верха» неба к видимому «верху» (null — ровно). */
    Quaternionf modelTilt(float pt) {
        if (on == null && blendFrom == null) return null;
        Vector3f up = shownUp(pt);
        if (up.y > 0.99999f) return null;
        return new Quaternionf().rotationTo(new Vector3f(0, 1, 0), up);
    }

    /**
     * Камера. Внутри корабля — вместе с кораблём, снаружи — как у игры; глаза — над ногами по видимому «верху».
     * При смене взгляд точно за мышью, а «верх» (горизонт) и глаза плавно, по одной кривой, едут к новому.
     */
    void camera(CameraState s, float pt, float w) {
        if (s.detached) {                                                       // F5 — свой облёт
            if (on != null) orbit(s, pt);
            return;
        }
        boolean blending = blendT() < 1;
        if (on == null && !blending) return;                                    // снаружи — камера игры
        Vector3f up = shownUp(pt);
        // глаза: над ногами по видимому «верху» (игра ставит их строго вверх по миру)
        var p = Minecraft.getInstance().player;
        if (p != null) {
            double eye = s.y - Mth.lerp(pt, p.yo, p.getY());
            s.x += up.x * eye;
            s.y += up.y * eye - eye;
            s.z += up.z * eye;
        }
        // взгляд: внутри — в системе корабля, снаружи — как у игры
        Quaternionf view;
        if (on != null) {
            Quaternionf ship = on.placeAt(pt).getNormalizedRotation(new Quaternionf());
            view = ship.rotateYXZ((float) Math.PI - (float) Math.toRadians(lyaw), (float) -Math.toRadians(lpitch), 0);
        } else {
            view = new Quaternionf().rotationYXZ((float) Math.PI - (float) Math.toRadians(s.yaw), (float) -Math.toRadians(s.pitch), 0);
        }
        if (!blending) { apply(s, view); return; }
        Vector3f f = view.transform(new Vector3f(0, 0, -1));
        Vector3f cu = new Vector3f(up).sub(new Vector3f(f).mul(f.dot(up)));
        if (cu.lengthSquared() < 1e-4f) { apply(s, view); return; }            // смотрим вдоль «верха» — как есть
        cu.normalize();
        Vector3f right = new Vector3f(f).cross(cu).normalize();
        apply(s, new Matrix3f(right, cu, new Vector3f(f).negate()).getNormalizedRotation(new Quaternionf()));
    }

    /** Затемнение краёв экрана на время смены гравитации. */
    void hud(net.minecraft.client.gui.GuiGraphics g) {
        float k = pulse();
        if (k <= 0.01f) return;
        float a = 0.55f * k;
        g.blit(net.minecraft.client.renderer.RenderPipelines.VIGNETTE, net.minecraft.resources.Identifier.withDefaultNamespace("textures/misc/vignette.png"),
                0, 0, 0f, 0f, g.guiWidth(), g.guiHeight(), g.guiWidth(), g.guiHeight(), net.minecraft.util.ARGB.colorFromFloat(1f, a, a, a));
    }

    private static void apply(CameraState s, Quaternionf q) {
        try {
            s.rotation = new Quaternionf(q);
        } catch (LinkageError old) {                                            // ядро до 0.21 — без полного поворота
        }
    }

    /**
     * Вид от третьего лица (F5): камера облетает игрока в системе корабля — от глаз (по «вверх» корабля) назад по
     * взгляду на 4 блока, ближе, если мешают блоки корабля или мира. Вид спереди — то же, развёрнутое к игроку.
     */
    private void orbit(CameraState s, float pt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Matrix4f m = on.placeAt(pt);
        Matrix4d md = new Matrix4d(m), toLocal = new Matrix4d(md).invert();
        Vec3 feet = p.getPosition(pt);
        Vector3d lf = toLocal.transformPosition(new Vector3d(feet.x, feet.y, feet.z));
        Vector3d leye = new Vector3d(lf.x, lf.y + p.getEyeHeight(), lf.z);
        boolean front = mc.options.getCameraType() == net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
        float yaw = front ? lyaw + 180 : lyaw, pitch = front ? -lpitch : lpitch;
        // камера смотрит по взгляду — значит стоит позади, против него
        Vector3d back = dir(yaw, pitch).negate();
        double want = 4.0 * p.getScale();
        double dist = shipClear(leye, back, want);
        Vector3d weye = md.transformPosition(new Vector3d(leye));
        Vector3d wback = md.transformDirection(new Vector3d(back));
        // мир: луч от глаз назад
        Vec3 from = new Vec3(weye.x, weye.y, weye.z), to = from.add(wback.x * dist, wback.y * dist, wback.z * dist);
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) dist = Math.max(0, hit.getLocation().distanceTo(from) - 0.1);
        s.x = weye.x + wback.x * dist;
        s.y = weye.y + wback.y * dist;
        s.z = weye.z + wback.z * dist;
        Quaternionf ship = m.getNormalizedRotation(new Quaternionf());
        Quaternionf view = new Quaternionf(ship).rotateYXZ((float) Math.PI - (float) Math.toRadians(yaw), (float) -Math.toRadians(pitch), 0);
        try {
            s.rotation = view;
        } catch (LinkageError old) {
        }
    }

    /** Насколько можно отойти от точки from по dir (система корабля) до блоков корабля, не дальше max. */
    private double shipClear(Vector3d from, Vector3d dir, double max) {
        ShipMesh mesh = on.mesh();
        double r = 0.15;                                                        // толщина камеры
        for (double t = 0; t <= max; t += 0.05) {
            double x = from.x + dir.x * t, y = from.y + dir.y * t, z = from.z + dir.z * t;
            AABB box = new AABB(x - r, y - r, z - r, x + r, y + r, z + r);
            int x0 = (int) Math.floor(box.minX + 0.5), x1 = (int) Math.floor(box.maxX + 0.5);
            int y0 = (int) Math.floor(box.minY + mesh.seatH), y1 = (int) Math.floor(box.maxY + mesh.seatH);
            int z0 = (int) Math.floor(box.minZ + 0.5), z1 = (int) Math.floor(box.maxZ + 0.5);
            for (int dy = y0; dy <= y1; dy++) for (int dz = z0; dz <= z1; dz++) for (int dx = x0; dx <= x1; dx++) {
                var sh = mesh.shape(dx, dy, dz);
                if (sh == null) continue;
                for (AABB b : sh.toAabbs()) if (b.intersects(box)) return Math.max(0, t - 0.05);
            }
        }
        return max;
    }

    // ───────────────────────── вспомогательное ─────────────────────────

    private static Vector3d local(Matrix4f place, Vec3 pos) {
        return new Matrix4d(place).invert().transformPosition(new Vector3d(pos.x, pos.y, pos.z));
    }

    /** Направление взгляда по курсу/наклону (как у игры). */
    private static Vector3d dir(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch), cp = Math.cos(p);
        return new Vector3d(-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp);
    }
}
