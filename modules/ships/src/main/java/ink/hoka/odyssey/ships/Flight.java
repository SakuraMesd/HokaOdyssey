package ink.hoka.odyssey.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.network.packet.server.play.VehicleMovePacket;
import net.minestom.server.network.packet.server.play.EntityVelocityPacket;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.metadata.display.BlockDisplayMeta;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Полёт корабля.
 *
 * <p><b>Почему без задержки управления.</b> Пилот сидит на невидимом крошечном счастливом гасте («двигатель»)
 * со сбруей. Таким гастом управляет клиент пилота сам, по ванильной физике: гаст плавно доворачивает курс
 * к взгляду всадника (8% оставшегося угла за тик), W ведёт вперёд <i>по курсу гаста</i> — по носу корабля,
 * наклон взгляда — вверх/вниз, пробел — вверх. Клиент шлёт серверу координаты гаста (как у лодки):
 * ни поворот, ни движение не ждут сервер.
 *
 * <p>Модель корабля (одна item display из пака) и прицел носа — пассажиры пилота: клиент сам держит их на нём.
 * Сервер поворачивает модель по курсу гаста (с упреждением на пинг), наклоняет нос за взглядом, проверяет
 * столкновения с рельефом (при ударе возвращает гаст назад пакетом VehicleMove) и сажает корабль.
 *
 * <p>Смещения частей — целые (угол блока относительно клетки места пилота), поворот при посадке — ровно
 * на 90°·q без тригонометрии, чтобы блоки не съезжали на клетку.
 */
final class Flight {

    /** Часть модели: смещение угла блока от клетки пилота (dx, dy, dz — целые, ориентация на момент взлёта). */
    private record Part(int dx, int dy, int dz, Block block, Entity display) {}


    final Ship ship;
    final Player pilot;
    private final ShipsModule mod;
    private final Instance instance;
    private final List<Part> parts = new ArrayList<>();
    private final List<Entity> hubs = new ArrayList<>();
    /** Модель всего корабля одной сущностью (если модель есть в паке), иначе null — блоки на «узлах». */
    private Entity single;
    /**
     * Личная копия модели для пилота (flight.pilot-view: locked): billboard VERTICAL — курс ей поворачивает сам клиент
     * вслед за камерой, мгновенно и без пинга (корабль не «плавает» вокруг взгляда). Наклон/крен — с сервера.
     * Остальные видят single (курс с сервера). Доводку носа показывает кольцо-прицел.
     */
    private Entity mine;
    /**
     * camera.follow: ship — камера смотрит глазами невидимой стойки-«камеры» (пакет Camera), которая сидит на пилоте
     * и поворачивается сервером ровно как модель: камера всегда за кораблём и движется с ним как одно целое.
     * Мышь двигает прицел по экрану, корабль (и камера) доворачивают за ним.
     */
    private LivingEntity camEnt;
    /**
     * camera.follow: ghast — камера глазами гаста-двигателя. Клиент сам доворачивает гаст за мышью (8%/тик) и сам
     * двигает его по W — камера, доводка и полёт считаются на клиенте, без пинга. Модель пилота — billboard по камере.
     */
    private final boolean ghastCam;
    private boolean ghastCamSent;
    /** Режим ghast, слот 2: вид сзади-сверху — центр корабля на chD впереди и chH ниже камеры (по курсу). */
    private boolean chaseView;
    private double chD, chH;
    /** Мгновенный прицел мыши «шлемом» (только пилоту, пакетом экипировки) — шейдер entity рисует прицел и пунктир. */
    private boolean helmetAim;
    /** Эффект скорости на форсаже: клиент сам плавно расширяет поле зрения по атрибуту скорости игрока. */
    private double moveSpeedBase = Double.NaN;
    private boolean fovBoost;
    private double prevEngineYaw = Double.NaN;
    // упреждение прицела по горизонтали: сглаженная скорость мыши, °/тик
    private double lookRate, prevLookYaw = Double.NaN;
    private long yawClampUntil;
    private long corrQuietUntil, rawLookTick = Long.MIN_VALUE / 2;
    /**
     * «Память» мыши: курс взгляда как его шлёт клиент — без обрезки по кругу (после полутора оборотов вправо это +540°),
     * и курс гаста, склеенный по шагам (за тик гаст поворачивает ≤ 14°). Их разность — насколько и в какую сторону мышь
     * реально ушла от носа: поворот на 270° вправо — это +270, а не «90° влево».
     */
    private double rawLook = Double.NaN, engUnw = Double.NaN, prevEngWrapped;

    /** Пакет поворота от клиента пилота (сырой курс взгляда). */
    void rawLook(float yaw) {
        if (!Float.isFinite(yaw) || Math.abs(yaw) > 1e6) return;                // мусор / огромный накопленный курс — не верим
        rawLook = yaw;
        rawLookTick = ticks;
    }
    private boolean camSent;
    private float camSentYaw = Float.NaN, camSentPitch;
    /** Прицел взгляда и точки пунктира — отдельные text display (в режиме follow: ship); [0] — прицел, дальше точки. */
    private final List<Entity> marks = new ArrayList<>();
    private boolean dotsShown = true;
    /**
     * Виртуальный прицел (follow: ship): копит движения мыши × reticle.sensitivity и не уходит от носа дальше
     * reticle.max-yaw / max-pitch — упирается в край, не перескакивает. Корабль доворачивает к нему.
     */
    private double aimVYaw = Double.NaN, aimVPitch, lastLookYaw, lastLookPitch;
    private boolean recentering;             // взгляд пилота вернули к горизонту — ждём, пока клиент подтвердит
    /** Невидимый гаст со сбруей — на нём сидит пилот, им управляет клиент. */
    private LivingEntity engine;
    /** Режим невесомости пилота до взлёта (вернуть после посадки); null — модуля gravity нет. */
    private String vacuumBefore;
    private double engineSpeed = Double.NaN;  // отправленный клиенту flying_speed гаста
    // журнал полёта (flight.debug): сколько тиков гаст сдвинулся, путь, самая длинная пауза без пакетов, откаты
    private int dbgMoved, dbgGap, dbgMaxGap, dbgRollbacks;
    private double dbgDist;
    private Pos dbgLast;
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> dbgPackets = new java.util.concurrent.ConcurrentHashMap<>();
    /** Прицел носа: значок далеко перед носом корабля, виден только пилоту. */
    private Entity reticle;
    /** Курс «переда» модели, как она собрана (чертёж без поворота). */
    private double modelFwd;
    /** Части в системе «нос на юг, горизонтально», от ног пилота (центры клеток) — для столкновений. */
    private double[][] pts = new double[0][];
    private double cameraBase = 4;
    private final double baseYaw;            // взгляд пилота при взлёте (поворот модели 0)
    private double shipYaw;                  // текущий курс модели
    private double shipPitch;                // текущий наклон носа (+ — вниз, как у взгляда)
    private double yawRate, pitchRate;       // скорость поворота, °/тик (плавный разгон)
    private double shownYaw = Double.NaN, shownPitch;  // последние отправленные клиентам
    // физика полёта (считает сервер): скорость двигателя, блоков/тик; крен модели в поворотах, градусов
    private double vx, vy, vz;
    private double roll, shownRoll;
    // камера orbit: центр корабля — на orbitY ниже/выше ног пилота (точки, вокруг которой крутится камера F5);
    // shipC — центр корабля от места пилота в системе «нос на юг» (корабль вращается вокруг центра)
    private double[] shipC = {0, 0, 0};
    private double orbitY;
    private double camDist = 4;
    private boolean orbitView;               // вид «сверху» (слот 2): глаз пилота над кораблём; иначе — из кресла (слот 1)
    private int viewGrace;                   // тиков без проверки столкновений после смены вида (старые координаты от клиента)
    // курс/наклон корабля, какими их увидит клиент пилота (прогноз на пинг), — каждый тик; история — для столкновений
    private double aimYaw = Double.NaN, aimPitch;
    private final double[] histYaw = new double[64], histPitch = new double[64];
    private Pos lastSafe;                    // последнее безопасное положение двигателя (гаста)
    private long ticks;
    private boolean finished;
    // как было у пилота до полёта
    private final GameMode gm;
    private final boolean allowFly, flying;
    private final float flySpeed;

    Flight(ShipsModule mod, Ship ship, Player pilot) {
        this.mod = mod;
        this.ship = ship;
        this.pilot = pilot;
        this.instance = pilot.getInstance();
        this.gm = pilot.getGameMode();
        this.allowFly = pilot.isAllowFlying();
        this.flying = pilot.isFlying();
        this.flySpeed = pilot.getFlyingSpeed();
        // курс корабля при взлёте — его перед (задан админом / от сиденья), пилот садится лицом вперёд
        this.baseYaw = ShipTemplate.yawOf(Rot.dir(ship.t.forwardDir(), ship.rot));
        this.shipYaw = baseYaw;
        this.ghastCam = "ghast".equalsIgnoreCase(mod.config().getString("camera.follow", "ghast"));
    }

    private double seatH() { return mod.config().getDouble("flight.seat-height", 0.5); }
    private double riderY() { return mod.config().getDouble("flight.rider-y", 1.8) * pilotScale(); }

    /** Размер пилота (режим ghast — крошечный: точка его пассажиров почти у камеры, кабина не «уплывает» при наклоне). */
    private double pilotScale() { return ghastCam ? Math.clamp(mod.config().getDouble("camera.pilot-scale", 1), 0.0625, 1) : 1; }
    private double pilotScaleBefore = Double.NaN;

    /** Взлёт: блоки → модель (на «узлах» по 16 блоков, узлы — пассажиры пилота), пилот в полёт. */
    void start() {
        Vec seat = ship.seatWorld();
        Pos pivot = new Pos(seat.x() + 0.5, seat.y() + seatH(), seat.z() + 0.5, (float) baseYaw, pilot.getPosition().pitch());
        // части: клетки чертежа с блоком + всё, что игроки построили в зонах строительства
        ShipTemplate t = ship.t;
        List<Vec> toClear = new ArrayList<>();
        java.util.Map<Long, Block> cells = new java.util.HashMap<>();
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
            if (t.block(x, y, z).isAir() && !t.inZone(x, y, z)) continue;
            Vec w = ship.world(x, y, z);
            Block placed = instance.getBlock(w);
            if (placed.isAir()) continue;
            int dx = w.blockX() - seat.blockX(), dy = w.blockY() - seat.blockY(), dz = w.blockZ() - seat.blockZ();
            parts.add(new Part(dx, dy, dz, placed, null));
            cells.put(key(dx, dy, dz), placed);
            toClear.add(w);
        }
        for (Vec w : toClear) instance.setBlock(w, Block.AIR, false);
        for (Vec w : toClear) instance.setBlock(w, Block.AIR);                  // второй проход — обновить соседей снаружи

        double sh0 = seatH();
        pts = new double[parts.size()][];
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            double[] h = Rot.yaw(p.dx, p.dz, -baseYaw);                         // в систему «нос на юг»
            pts[i] = new double[]{h[0], p.dy + 0.5 - sh0, h[1]};
        }
        orbitView = "orbit".equalsIgnoreCase(mod.config().getString("camera.mode", "seat"));
        orbitSetup(orbitView);
        java.util.Arrays.fill(histYaw, baseYaw);
        Vec k0 = camOff(baseYaw, 0);
        Pos cam0 = pivot.sub(k0);                                               // пилот (центр вращения камеры) — над центром корабля

        pilot.teleport(cam0);
        pilot.setHeldItemSlot((byte) (orbitView ? 1 : 0));
        pilot.setGameMode(GameMode.ADVENTURE);                                  // в полёте ломать/ставить нельзя
        pilot.setFlying(false);
        pilot.setAllowFlying(false);
        pilot.setInvisible(true);
        if (ghastCam) {
            pilotScaleBefore = pilot.getAttribute(Attribute.SCALE).getBaseValue();
            pilot.getAttribute(Attribute.SCALE).setBaseValue(pilotScale());
        }
        vacuumBefore = mod.vacuum().get(pilot);                                 // невесомость не толкает пилота в полёте
        if (vacuumBefore != null) mod.vacuum().set(pilot, "NORMAL");
        // двигатель: гаст ставим так, чтобы ноги пилота (место всадника) пришлись точно в pivot
        engine = new LivingEntity(EntityType.HAPPY_GHAST);
        engine.setNoGravity(true);
        engine.setInvisible(true);
        engine.setInvulnerable(true);
        engine.setSilent(true);
        engine.setSynchronizationTicks(Integer.MAX_VALUE);                         // положение задаёт клиент пилота
        double es = engineScale();
        engine.setBoundingBox(4 * es, 4 * es, 4 * es);
        engine.getAttribute(Attribute.SCALE).setBaseValue(es);
        engineSpeed = 0;                                                    // ввод гаста у клиента выкл: скорость шлёт сервер
        // (в режиме ghast клиент тоже не двигает гаст: пока камера не у игрока, клиент не передаёт ему W/A/S/D)
        engine.getAttribute(Attribute.FLYING_SPEED).setBaseValue(engineSpeed);
        ItemStack harness = ItemStack.of(Material.WHITE_HARNESS);
        var eq = harness.get(DataComponents.EQUIPPABLE);
        if (eq != null) harness = harness.with(DataComponents.EQUIPPABLE, eq.withAssetId(ShipsModule.SEAT_ASSET));  // сбруя ничего не рисует
        engine.setEquipment(EquipmentSlot.BODY, harness);
        Pos ePos = enginePosFor(cam0, baseYaw);
        engine.setInstance(instance, ePos).join();
        engine.addPassenger(pilot);
        var cam = pilot.getAttribute(Attribute.CAMERA_DISTANCE);              // камера от 3-го лица (F5) — дальше, по размеру
        cameraBase = cam.getBaseValue();
        cam.setBaseValue(Math.min(32, camDist));                                // у игрока предел 32
        lastSafe = ePos;
        if (ghastCam) {
            // camera.f5: false — F5 выключен (дальность камеры 0: вид из кабины и в F5)
            engine.getAttribute(Attribute.CAMERA_DISTANCE).setBaseValue(mod.config().getBoolean("camera.f5", false) ? Math.clamp(camDist / engineScale(), 1, 32) : 0);
        }
        if ("ship".equalsIgnoreCase(mod.config().getString("camera.follow", "ghast"))) {
            camEnt = new LivingEntity(EntityType.ARMOR_STAND);
            camEnt.setNoGravity(true);
            camEnt.setInvisible(true);
            camEnt.setInvulnerable(true);
            camEnt.setSilent(true);
            camEnt.setSynchronizationTicks(Integer.MAX_VALUE);
            camEnt.editEntityMeta(net.minestom.server.entity.metadata.other.ArmorStandMeta.class, m -> { m.setMarker(true); m.setSmall(true); });
            double attr = Math.min(32, camDist);                                   // дальность F5 = атрибут × масштаб стойки
            camEnt.getAttribute(Attribute.CAMERA_DISTANCE).setBaseValue(attr);
            camEnt.getAttribute(Attribute.SCALE).setBaseValue(Math.clamp(camDist / attr, 1, 16));
            camEnt.updateViewableRule(v -> v == pilot);
            camEnt.setInstance(instance, cam0.withView((float) baseYaw, 0f)).join();
            pilot.addPassenger(camEnt);
        }

        // модель. Видны только блоки с открытой стороной (внутренние не рисуем — при посадке они всё равно ставятся).
        // Сущности смотрят строго yaw 0 / pitch 0 (поворот — только в трансформации), синхронизация позиции выключена:
        // иначе сервер раз в секунду «дёргал» бы каждый блок к своей (отстающей на пинг) позиции пилота.
        Pos spawn = cam0.withView(0f, 0f);
        double ry = riderY(), sh = seatH();
        ShipsModule.ModelInfo mi = mod.model(ship.template);
        if (mi != null) {
            // Весь корабль — одна сущность с моделью из пака (её видят все, и пилот тоже). Поворачивает сервер:
            // корабль плавно доводится к взгляду пилота (прицелу), клиент сглаживает между обновлениями.
            // модель собрана при повороте чертежа 0; item display дополнительно разворачивает предмет на 180° вокруг Y
            modelFwd = ShipTemplate.yawOf(ship.t.forwardDir()) + 180;
            float s = mi.scale();
            ItemStack model = ItemStack.of(Material.PAPER).with(DataComponents.ITEM_MODEL, ShipModel.itemModel(ship.template));
            single = new Entity(EntityType.ITEM_DISPLAY);
            single.setNoGravity(true);
            single.setSynchronizationTicks(Integer.MAX_VALUE);
            single.editEntityMeta(ItemDisplayMeta.class, m -> {
                m.setItemStack(model);
                m.setViewRange(8f);
                m.setScale(new Vec(s, s, s));
                m.setTranslation(new Vec(k0.x(), k0.y() - ry, k0.z()));
                m.setLeftRotation(Rot.quat(baseYaw, 0, modelFwd));
            });
            if (ghastCam || !"world".equalsIgnoreCase(mod.config().getString("flight.pilot-view", "world"))) {
                single.updateViewableRule(v -> v != pilot);
                mine = new Entity(EntityType.ITEM_DISPLAY);
                mine.setNoGravity(true);
                mine.setSynchronizationTicks(Integer.MAX_VALUE);
                Vec km = camOff(180, 0).add(cockpitShift());
                mine.editEntityMeta(ItemDisplayMeta.class, m -> {
                    m.setItemStack(model);
                    m.setViewRange(8f);
                    m.setScale(new Vec(s, s, s));
                    m.setBillboardRenderConstraints(rigidCockpit()
                            ? net.minestom.server.entity.metadata.display.AbstractDisplayMeta.BillboardConstraints.CENTER
                            : net.minestom.server.entity.metadata.display.AbstractDisplayMeta.BillboardConstraints.VERTICAL);
                    m.setTranslation(rigidCockpit() ? cockpitT(0) : new Vec(km.x(), km.y() - ry, km.z()));
                    m.setLeftRotation(Rot.quat(180, 0, modelFwd));               // в системе камеры нос — на север (−z), куда она смотрит
                });
                mine.updateViewableRule(v -> v == pilot);
                mine.setInstance(instance, spawn).join();
                // жёсткая кабина — на сиденье №2 гаста (справа, ниже головы пилота): ближе к камере, меньше «уплывает»
                if (rigidCockpit()) engine.addPassenger(mine); else pilot.addPassenger(mine);
            }
            single.setInstance(instance, spawn).join();
            pilot.addPassenger(single);
            spawnReticle(spawn);
            ship.state = Ship.State.FLYING;
            ship.flight = this;
            hello();
            return;
        }
        List<Part> out = new ArrayList<>(parts.size());
        Entity hub = null;
        int inHub = 0;
        for (Part p : parts) {
            if (!exposed(p, cells)) { out.add(p); continue; }
            if (hub == null || inHub >= 16) {
                hub = new Entity(EntityType.ITEM_DISPLAY);                      // пустой «узел», ничего не рисует
                hub.setNoGravity(true);
                hub.setSynchronizationTicks(Integer.MAX_VALUE);
                hub.setInstance(instance, spawn).join();
                hubs.add(hub);
                inHub = 0;
            }
            Entity e = new Entity(EntityType.BLOCK_DISPLAY);
            e.setNoGravity(true);
            e.setSynchronizationTicks(Integer.MAX_VALUE);
            Vec o = corner(p, 0, sh);
            e.editEntityMeta(BlockDisplayMeta.class, m -> {
                m.setBlockState(p.block);
                m.setViewRange(4f);
                m.setTranslation(new Vec(o.x() + k0.x(), o.y() - ry + k0.y(), o.z() + k0.z()));
            });
            e.setInstance(instance, spawn).join();
            hub.addPassenger(e);
            inHub++;
            out.add(new Part(p.dx, p.dy, p.dz, p.block, e));
        }
        parts.clear();
        parts.addAll(out);
        for (Entity h : hubs) pilot.addPassenger(h);
        spawnReticle(spawn);
        ship.state = Ship.State.FLYING;
        ship.flight = this;
        hello();
    }

    private void hello() {
        pilot.sendMessage(Component.text("Полёт: мышь — прицел, нос корабля плавно доводится к нему. W — вперёд по носу "
                + "(смотришь вверх/вниз — набираешь/теряешь высоту), Ctrl+W — форсаж, S — тормоз/назад, A/D — вбок, "
                + "пробел/Shift — вверх/вниз. F — посадка (нос ровно). 1/2 — вид из кабины / сверху (с F5 — свысока).", NamedTextColor.GREEN));
    }

    /**
     * camera.mode orbit: камера — ванильный вид F5 (считает клиент: без пинга и рывков), она крутится вокруг глаз пилота
     * на camera-distance. Корабль ставим центром ниже глаз на camDist·tg(angle) — камера оказывается над ним и сзади,
     * а прицел (центр экрана) — над кораблём. seat — как раньше: пилот в своём кресле.
     */
    private void orbitSetup(boolean orbit) {
        int[] size = ship.size();
        double want = mod.config().getDouble("flight.camera-distance", 0);
        camDist = Math.clamp(want > 0 ? want : Math.max(size[0], Math.max(size[1], size[2])) * mod.config().getDouble("camera.distance-per-size", 1.5) + 6, 4, 256);
        if (!orbit || pts.length == 0) {
            shipC = new double[]{0, 0, 0};
            orbitY = 0;
            return;
        }
        double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
        for (double[] v : pts) for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], v[i] - 0.5); hi[i] = Math.max(hi[i], v[i] + 0.5); }
        shipC = new double[]{(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
        double h = mod.config().getDouble("camera.height", 0);
        if (h <= 0) h = camDist * Math.tan(Math.toRadians(mod.config().getDouble("camera.angle", 12)));
        orbitY = 1.62 - h;
    }

    /**
     * Смена вида: слот 1 — из кресла, слот 2 — сверху (глаз пилота над центром корабля, F5 смотрит свысока).
     * Корабль остаётся на месте — переносим гаст (а с ним пилота и камеру) на разницу.
     */
    void setView(boolean orbit) {
        if (ghastCam) {
            if (finished || engine == null || orbit == chaseView) return;
            if (orbit) {
                double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
                for (double[] v : pts) for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], v[i] - 0.5); hi[i] = Math.max(hi[i], v[i] + 0.5); }
                shipC = pts.length == 0 ? new double[]{0, 0, 0} : new double[]{(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2};
                double len = pts.length == 0 ? 4 : Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
                chD = mod.config().getDouble("camera.chase-distance", 0);
                if (chD <= 0) chD = len * mod.config().getDouble("camera.chase-per-size", 1.2) + 6;
                chH = chD * Math.tan(Math.toRadians(mod.config().getDouble("camera.chase-angle", 15)));
            }
            chaseView = orbit;
            viewGrace = (int) Math.round(pilot.getLatency() / 50.0) + 6;
            shownYaw = Double.NaN;
            dbgLast = null;
            return;
        }
        if (finished || engine == null || orbit == orbitView) return;
        double y = Double.isNaN(aimYaw) ? shipYaw : aimYaw, pt = aimPitch;
        Vec before = camOff(y, pt);
        orbitView = orbit;
        orbitSetup(orbit);
        Vec delta = before.sub(camOff(y, pt));
        Pos ne = engine.getPosition().add(delta);
        engine.refreshPosition(ne);
        pilot.sendPacket(new VehicleMovePacket(ne));
        lastSafe = lastSafe.add(delta);
        dbgLast = null;
        viewGrace = (int) Math.round(pilot.getLatency() / 50.0) + 6;
        shownYaw = Double.NaN;                                                  // модель — сразу на новое место
        pilot.sendActionBar(Component.text(orbit ? "Вид сверху (в F5 — над кораблём). 1 — кабина" : "Вид из кабины. 2 — сверху", NamedTextColor.AQUA));
    }

    /** Ноги пилота → место пилота в корабле (в мире) при курсе yaw и наклоне pitch: вниз к центру, потом от центра к креслу. */
    private Vec camOff(double yaw, double pitch) {
        double[] w = Rot.orient(-shipC[0], -shipC[1], -shipC[2], yaw, pitch);
        if (ghastCam && chaseView) return chaseOff(yaw, w);
        return new Vec(w[0], orbitY + w[1], w[2]);
    }

    /**
     * Вид сзади (ghast, слот 2): место пилота в корабле − ноги пилота. Центр корабля = глаза гаста (камера) +
     * курс·(0, −chH, chD); ноги пилота = камера + курс·(0, ys, 1.7·масштаб); w — от центра к креслу (уже повёрнут).
     */
    private Vec chaseOff(double yaw, double[] w) {
        double es = engineScale();
        double ys = 4 * es - 0.6 * pilotScale() - mod.config().getDouble("camera.ghast-eye", 2.6) * es;
        double[] h = Rot.yaw(0, chD - 1.7 * es, yaw);
        return new Vec(h[0] + w[0], -chH - ys + w[1], h[1] + w[2]);
    }

    /** То же с креном (для модели: крен — вокруг центра корабля). */
    private Vec camOff(double yaw, double pitch, double roll) {
        double[] w = Rot.rotate(Rot.mul(Rot.mul(Rot.quat(yaw), Rot.quatPitch(pitch)), Rot.quatRoll(roll)), -shipC[0], -shipC[1], -shipC[2]);
        if (ghastCam && chaseView) return chaseOff(yaw, w);
        return new Vec(w[0], orbitY + w[1], w[2]);
    }

    /** Курс/наклон, которые были у модели на клиенте n тиков назад. */
    private int histAt(int n) { return (int) Math.floorMod(ticks - 1 - n, 64L); }

    /**
     * Режим ghast: камера — в глазах гаста, а пилот сидит выше и впереди (место всадника). Личную модель сдвигаем так,
     * чтобы кресло пришлось на камеру. Система billboard: нос — −z, назад — +z.
     */
    /** Жёсткая кабина (ghast + camera.rigid-cockpit): личная модель — billboard CENTER (курс и наклон камеры), кресло у камеры. */
    private boolean rigidCockpit() { return ghastCam && mod.config().getBoolean("camera.rigid-cockpit", true); }

    /** Оценка наклона камеры-гаста (клиент ставит гасту половину наклона мыши), градусов, + — вниз. */
    private double camPitchEst, camPitchPrev = Double.NaN, camPitchRate, camPitchPred;

    /**
     * Сдвиг личной модели в системе камеры (billboard CENTER: нос −z, верх +y), чтобы глаза пилота в кресле были ровно
     * в камере (глазах гаста) при её наклоне p: T = Rx(p)·(C − O) − (0, 1.62, 0); C − O — от точки пассажира пилота
     * до глаз гаста в системе курса (вверх и назад на 1.7·масштаб — пилот сидит впереди).
     */
    private Vec cockpitT(double pitchDeg) {
        double es = engineScale();
        double eyeG = mod.config().getDouble("camera.ghast-eye", 2.6) * es;
        // от точки модели до глаз гаста, в системе камеры (нос −z, вправо +x): модель сидит на сиденье №2 гаста
        // (−1.7, 4, 0)·масштаб — справа от центра, т.е. камера от неё влево; глаза гаста — на 2.6·масштаб
        double x = -1.7 * es, y = eyeG - 4 * es, z = 0;
        double p = Math.toRadians(pitchDeg), c = Math.cos(p), s = Math.sin(p);
        return new Vec(x + mod.config().getDouble("camera.cockpit-side", 0), y * c - z * s - 1.62 + mod.config().getDouble("camera.cockpit-up", 0), y * s + z * c);
    }

    /** Где камера (глаза гаста) в системе личной модели при наклоне камеры — центр вращения кабины. */
    private Vec cockpitCam(double pitchDeg) {
        double es = engineScale();
        double eyeG = mod.config().getDouble("camera.ghast-eye", 2.6) * es;
        double x = -1.7 * es, y = eyeG - 4 * es, z = 0;
        double p = Math.toRadians(pitchDeg), c = Math.cos(p), s = Math.sin(p);
        return new Vec(x + mod.config().getDouble("camera.cockpit-side", 0), y * c - z * s, y * s + z * c);
    }

    private Vec cockpitShift() {
        if (!ghastCam) return Vec.ZERO;
        double es = engineScale();
        double eye = mod.config().getDouble("camera.ghast-eye", 2.6) * es;     // высота глаз гаста
        double pilotEye = 4 * es - 0.6 * pilotScale() + 1.62;                                  // глаза пилота над гастом
        return new Vec(0, eye - pilotEye + mod.config().getDouble("camera.cockpit-up", 0), 1.7 * es);
    }

    private double engineScale() {
        return ghastCam ? Math.clamp(mod.config().getDouble("camera.ghast-scale", 1), 0.0625, 4)
                : Math.clamp(mod.config().getDouble("flight.engine-scale", 0.0625), 0.0625, 1);
    }

    /**
     * Ноги пилота относительно гаста — как считает ванильный клиент: место всадника №0 гаста (0, 4, 1.7)·масштаб,
     * повёрнутое по курсу гаста, минус точка посадки игрока (0, 0.6, 0).
     */
    private double[] riderOffset(double engineYaw) {
        double es = engineScale();
        double[] h = Rot.yaw(0, 1.7 * es, engineYaw);
        return new double[]{h[0], 4 * es - 0.6 * pilotScale(), h[1]};            // −0.6 — точка посадки игрока (с его размером)
    }

    private Pos enginePosFor(Pos feet, double yaw) {
        double[] o = riderOffset(yaw);
        return new Pos(feet.x() - o[0], feet.y() - o[1], feet.z() - o[2], (float) yaw, 0f);
    }

    /** Ноги пилота по положению гаста. */
    private Pos feet(Pos e) {
        double[] o = riderOffset(e.yaw());
        return new Pos(e.x() + o[0], e.y() + o[1], e.z() + o[2], e.yaw(), e.pitch());
    }

    /** Вернуть гаст (и пилота на нём) в безопасное место: гастом управляет клиент, поэтому — пакетом VehicleMove. */
    private void rollback(String why) {
        dbgRollbacks++;
        vx = vy = vz = 0;
        pilot.sendPacket(new EntityVelocityPacket(engine.getEntityId(), Vec.ZERO));
        engine.refreshPosition(lastSafe);
        pilot.sendPacket(new VehicleMovePacket(lastSafe));
        pilot.sendActionBar(Component.text(why, NamedTextColor.RED));
    }

    /**
     * Прицелы пилота — на экране, рисует шейдер пака (rendertype_text, см. ShipsModule.putHud):
     * text display далеко по носу корабля, в нём глифы шрифта hoka_ships:hud цвета #FEgg bb — шейдер узнаёт их по цвету
     * и переносит в экранные координаты: g=0 — кольцо носа (там, где точка на экране), g=2 — прицел взгляда (центр экрана),
     * g=1 — точки пунктира между ними (b — доля пути). Виден только пилоту.
     */
    private void spawnReticle(Pos spawn) {
        if (!mod.config().getBoolean("reticle.enabled", true)) return;
        reticle = new Entity(EntityType.TEXT_DISPLAY);
        reticle.setNoGravity(true);
        reticle.setSynchronizationTicks(Integer.MAX_VALUE);
        int dots = Math.clamp(mod.config().getInt("reticle.dots", 14), 0, 60);
        var font = net.kyori.adventure.key.Key.key("hoka_ships", "hud");
        var text = Component.text().font(font);
        helmetAim = ghastCam && mod.config().getBoolean("reticle.helmet", false);  // шлем не виден, пока камера не у игрока
        if (helmetAim) {                                                        // прицел и пунктир рисует шлем; здесь — только кольцо носа в центре
            text.append(Component.text("", TextColor.color(0xFE, 0, 255)));
        } else if (ghastCam) {                                                  // камера = нос: кольцо в центре, прицел по взгляду
            ghastDots = dots;
            for (Component ch : ghastHud(0, 0, 0, 0).children()) text.append(ch);   // круг, компас, полоски, прицелы
        } else if (camEnt == null) {                                            // камера = взгляд: прицел в центре экрана, пунктир шейдером
            for (int i = 1; i <= dots; i++)
                text.append(Component.text("\uE001", TextColor.color(0xFE, 1, (int) Math.round(255.0 * i / (dots + 1)))));
            text.append(Component.text("\uE000", TextColor.color(0xFE, 0, 0)));
            text.append(Component.text("\uE002", TextColor.color(0xFE, 2, 255)));
        } else {                                                                // камера = корабль: прицел и точки — в мире по взгляду
            text.append(Component.text("\uE000", TextColor.color(0xFE, 0, 0)));
            marks.add(newMark(Component.text("\uE002", TextColor.color(0xFE, 2, 0)).font(font), spawn));
            for (int i = 1; i <= dots; i++) marks.add(newMark(Component.text("\uE001", TextColor.color(0xFE, 1, 0)).font(font), spawn));
        }
        Component built = text.build();
        reticle.editEntityMeta(TextDisplayMeta.class, m -> {
            m.setText(built);
            m.setLineWidth(100000);
            m.setBackgroundColor(0);
            m.setShadow(false);
            m.setSeeThrough(false);                                             // обычный текст: шейдер rendertype_text (с глубиной)
            m.setBrightness(15, 15);
            m.setViewRange(16f);
            m.setScale(new Vec(0.01, 0.01, 0.01));                             // в мире — точка, размер задаёт шейдер
            reticlePose(m, shipYaw, shipPitch);
        });
        if (ghastCam && !helmetAim) reticle.editEntityMeta(TextDisplayMeta.class,
                m -> m.setBillboardRenderConstraints(net.minestom.server.entity.metadata.display.AbstractDisplayMeta.BillboardConstraints.VERTICAL));
        reticle.updateViewableRule(v -> v == pilot);
        reticle.setInstance(instance, spawn).join();
        pilot.addPassenger(reticle);
    }

    private Entity newMark(Component text, Pos spawn) {
        Entity e = new Entity(EntityType.TEXT_DISPLAY);
        e.setNoGravity(true);
        e.setSynchronizationTicks(Integer.MAX_VALUE);
        e.editEntityMeta(TextDisplayMeta.class, m -> {
            m.setText(text);
            m.setLineWidth(100000);
            m.setBackgroundColor(0);
            m.setShadow(false);
            m.setBrightness(15, 15);
            m.setViewRange(16f);
            m.setScale(new Vec(0.01, 0.01, 0.01));
            m.setTranslation(new Vec(0, 1.62 - riderY(), 0));
        });
        e.updateViewableRule(v -> v == pilot);
        e.setInstance(instance, spawn).join();
        pilot.addPassenger(e);
        return e;
    }

    /** Прицел взгляда и пунктир от носа к нему (по дуге) — каждый тик: взгляд меняется быстрее модели. */
    private void updateMarks(Pos look) {
        if (marks.isEmpty()) return;
        double y = Double.isNaN(shownYaw) ? shipYaw : shownYaw, pt = Double.isNaN(shownYaw) ? shipPitch : shownPitch;
        double[] n = Rot.orient(0, 0, 1, y, pt), a = Rot.orient(0, 0, 1, aimVYaw, aimVPitch);
        double d = mod.config().getDouble("reticle.distance", 200), eye = 1.62 - riderY();
        double cos = Math.clamp(n[0] * a[0] + n[1] * a[1] + n[2] * a[2], -1, 1), th = Math.acos(cos);
        int interp = Math.max(0, mod.config().getInt("reticle.interp-ticks", 3));
        for (int i = 0; i < marks.size(); i++) {
            double t = i == 0 ? 1 : (double) i / marks.size();                  // [0] — прицел (t=1), точки — по дуге
            double[] v;
            if (th < 1e-4) v = a;
            else if (Math.PI - th < 1e-3) v = new double[]{n[0] + (a[0] - n[0]) * t, n[1] + (a[1] - n[1]) * t, n[2] + (a[2] - n[2]) * t};
            else {
                double s0 = Math.sin((1 - t) * th) / Math.sin(th), s1 = Math.sin(t * th) / Math.sin(th);
                v = new double[]{n[0] * s0 + a[0] * s1, n[1] * s0 + a[1] * s1, n[2] * s0 + a[2] * s1};
            }
            double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (len < 1e-6) continue;
            Vec tr = new Vec(v[0] / len * d, eye + v[1] / len * d, v[2] / len * d);
            marks.get(i).editEntityMeta(TextDisplayMeta.class, m -> {
                m.setTransformationInterpolationStartDelta(0);
                m.setTransformationInterpolationDuration(interp);
                m.setTranslation(tr);
            });
        }
        boolean show = Math.toDegrees(th) > mod.config().getDouble("reticle.dots-hide-angle", 2);  // сошлись — пунктир прячем
        if (show != dotsShown) {
            dotsShown = show;
            var font = net.kyori.adventure.key.Key.key("hoka_ships", "hud");
            Component dot = show ? Component.text("\uE001", TextColor.color(0xFE, 1, 0)).font(font) : Component.empty();
            for (int i = 1; i < marks.size(); i++) marks.get(i).editEntityMeta(TextDisplayMeta.class, m -> m.setText(dot));
        }
    }

    /** Прицел: в режиме камеры за кораблём — виртуальный (копит мышь, ограничен конусом от носа), иначе — сам взгляд. */
    private void updateAim(Pos look, double maxPitch) {
        if (camEnt == null || Double.isNaN(aimVYaw)) {
            boolean first = Double.isNaN(aimVYaw);
            lastLookYaw = look.yaw();
            lastLookPitch = look.pitch();
            if (camEnt == null || first) { aimVYaw = camEnt == null ? look.yaw() : shipYaw; aimVPitch = camEnt == null ? look.pitch() : shipPitch; }
            if (camEnt == null) return;
        }
        double sens = mod.config().getDouble("reticle.sensitivity", 0.5);
        double dy = Rot.wrap(look.yaw() - lastLookYaw), dp = look.pitch() - lastLookPitch;
        lastLookYaw = look.yaw();
        lastLookPitch = look.pitch();
        // взгляд игрока упирается в ±90° — тогда мышью дальше не повести. Камера не его, поэтому незаметно
        // возвращаем взгляд к горизонту (пакет поворота) и, пока клиент не подтвердил, наклон не считаем
        if (recentering) {
            dp = 0;
            if (Math.abs(look.pitch()) < 10) recentering = false;
        } else if (Math.abs(look.pitch()) > mod.config().getDouble("reticle.recenter-pitch", 45)) {
            pilot.sendPacket(new net.minestom.server.network.packet.server.play.PlayerRotationPacket(0f, true, 0f, false));
            recentering = true;
        }
        double my = mod.config().getDouble("reticle.max-yaw", 35), mp = mod.config().getDouble("reticle.max-pitch", 25);
        double offY = Math.clamp(Rot.wrap(aimVYaw + dy * sens - shipYaw), -my, my);
        double offP = Math.clamp(aimVPitch + dp * sens - shipPitch, -mp, mp);
        aimVYaw = Rot.wrap(shipYaw + offY);
        aimVPitch = Math.clamp(shipPitch + offP, -maxPitch, maxPitch);
    }

    /** Камера-стойка: поворот как у модели (+ наклон камеры), точными углами (пакет синхронизации, не байтовый поворот). */
    private void updateCamera() {
        if (camEnt == null || Double.isNaN(shownYaw)) return;
        if (!camSent && ticks >= 2) {
            pilot.sendPacket(new net.minestom.server.network.packet.server.play.CameraPacket(camEnt.getEntityId()));
            camSent = true;
        }
        double off = mod.config().getDouble(orbitView ? "camera.chase-pitch" : "camera.cockpit-pitch", 0);
        float yaw = (float) shownYaw, pitch = (float) Math.clamp(shownPitch + off, -90, 90);
        if (!Float.isNaN(camSentYaw) && Math.abs(Rot.wrap(yaw - camSentYaw)) < 0.01 && Math.abs(pitch - camSentPitch) < 0.01) return;
        camSentYaw = yaw;
        camSentPitch = pitch;
        Pos at = pilot.getPosition().add(0, riderY(), 0);                    // близко к настоящему: дальше 64 бл. клиент не сглаживает
        pilot.sendPacket(new net.minestom.server.network.packet.server.play.EntityPositionSyncPacket(camEnt.getEntityId(), at, Vec.ZERO, yaw, pitch, false));
        // камера «живой» сущности берёт курс от ГОЛОВЫ (yHeadRot), наклон — от тела; голову клиент сглаживает 3 тика
        pilot.sendPacket(new net.minestom.server.network.packet.server.play.EntityHeadLookPacket(camEnt.getEntityId(), yaw));
    }

    /** Точка далеко по носу от глаз пилота: при носе «на прицел» она ровно в центре экрана (и в F5 — камера на этом луче). */
    private void reticlePose(TextDisplayMeta m, double yaw, double pitch) {
        double d = mod.config().getDouble("reticle.distance", 200);
        double[] nose = Rot.orient(0, 0, 1, yaw, pitch);
        double eye = 1.62 - riderY();
        m.setTranslation(new Vec(nose[0] * d, eye + nose[1] * d, nose[2] * d));
    }

    private static long key(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

    /** Есть ли у блока сторона, не закрытая соседним полным блоком корабля. */
    private static boolean exposed(Part p, java.util.Map<Long, Block> cells) {
        if (!p.block.registry().occludes()) return true;
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : n) {
            Block b = cells.get(key(p.dx + d[0], p.dy + d[1], p.dz + d[2]));
            if (b == null || !b.registry().occludes()) return true;
        }
        return false;
    }

    /** Угол блока относительно точки пилота при повороте модели на rel градусов (вокруг пилота). */
    private static Vec corner(Part p, double rel, double sh) {
        double[] c = Rot.yaw(p.dx, p.dz, rel);                                   // центр клетки (точка пилота — центр его клетки)
        double[] v = Rot.yaw(-0.5, -0.5, rel);                                  // от центра к углу (0,0) блока
        return new Vec(c[0] + v[0], p.dy - sh, c[1] + v[1]);
    }

    /** Каждый тик. */
    void tick() {
        if (finished) return;
        ticks++;
        if (!pilot.isOnline() || pilot.getInstance() != instance || pilot.getVehicle() != engine) { emergency(); return; }
        Pos ep = engine.getPosition();
        Pos fp = feet(ep);
        Pos look = pilot.getPosition();                                         // взгляд пилота (прицел)
        int minY = instance.getCachedDimensionType().minY(), maxY = minY + instance.getCachedDimensionType().height();
        int top = 0, bottom = 0;
        for (Part p : parts) { top = Math.max(top, p.dy); bottom = Math.min(bottom, p.dy); }
        if (fp.y() + orbitY + top + 2 > maxY || fp.y() + orbitY + bottom - 1 < minY) { rollback("Дальше нельзя — край мира"); return; }
        debugTick(ep);
        // курс — у гаста (его считает клиент, по нему же летит W); нос вверх/вниз плавно за взглядом
        double maxPitch = single != null ? Math.clamp(mod.config().getDouble("flight.max-pitch", 90), 0, 89.9) : 0;  // без модели — только курс
        double maxStep = mod.config().getDouble("flight.turn-speed", 90) / 20.0;
        double accel = mod.config().getDouble("flight.turn-accel", 360) / 400.0;
        double k = mod.config().getDouble("flight.turn-smoothing", 0.08);
        updateAim(look, maxPitch);
        if (ghastCam) {
            aimVYaw = look.yaw();
            camPitchEst = 0.5 * look.pitch();                                   // так клиент наклоняет гаста (камеру)
            // наклон камеры к моменту, когда сдвиг кабины дойдёт до клиента: + скорость × (пинг туда-обратно + тик)
            double dp = Double.isNaN(camPitchPrev) ? 0 : camPitchEst - camPitchPrev;
            camPitchPrev = camPitchEst;
            camPitchRate += (dp - camPitchRate) * mod.config().getDouble("camera.cockpit-predict-smoothing", 0.6);
            double aheadT = pilot.getLatency() / 50.0 + mod.config().getDouble("camera.cockpit-predict-extra", 1);
            camPitchPred = Math.clamp(camPitchEst + camPitchRate * aheadT * mod.config().getDouble("camera.cockpit-predict", 1), -45, 45);
            // нос корабля — как камера (кабина жёсткая), иначе — за полным наклоном мыши
            aimVPitch = rigidCockpit() ? camPitchEst : look.pitch();
        }
        pitchRate = approach(pitchRate, Math.clamp(aimVPitch, -maxPitch, maxPitch) - shipPitch, k, maxStep, accel);
        // курс ведёт сервер (как башню танка): плавно за прицелом, с разгоном и пределом скорости поворота.
        // Курс гаста у клиента не используем — он приходит пачками и давал тряску; гаст лишь везёт пилота.
        double ymax = mod.config().getDouble("flight.yaw-turn-speed", 90) / 20.0;
        double yacc = mod.config().getDouble("flight.yaw-turn-accel", 360) / 400.0;
        double yk = mod.config().getDouble("flight.yaw-turn-smoothing", 0.08);
        if (ghastCam) {                                                         // курс — у гаста (клиент): камера и полёт по нему
            double ey = Rot.wrap(ep.yaw());
            double d = Double.isNaN(prevEngineYaw) ? 0 : Rot.wrap(ey - prevEngineYaw);
            prevEngineYaw = ey;
            yawRate += (d - yawRate) * mod.config().getDouble("flight.bank-rate-smoothing", 0.12);                                     // для крена — сглаженный темп поворота
            shipYaw = ey;
        } else {
            yawRate = approach(yawRate, Rot.wrap(aimVYaw - shipYaw), yk, ymax, yacc);
            shipYaw = Rot.wrap(shipYaw + yawRate);
        }
        shipPitch = Math.clamp(shipPitch + pitchRate, -maxPitch, maxPitch);
        // столкновения: положение гаста от клиента — пинг назад, модель тогда стояла по прогнозу тех тиков
        int lag = Math.clamp((int) Math.round(pilot.getLatency() / 50.0), 0, 40);
        double cYaw = histYaw[histAt(lag)], cPitch = histPitch[histAt(lag)];
        int col = mod.config().getInt("flight.collision-ticks", 1);
        if (viewGrace > 0) viewGrace--;
        else if (col > 0 && ticks % col == 0) {
            Pos safeFeet = feet(lastSafe);
            boolean hit = collides(fp, cYaw, cPitch) || sweptCollides(safeFeet, fp, cYaw, cPitch);
            // задевает и на прошлом месте (застряли поворотом) — назад не дёргаем, даём вылететь
            if (hit && collides(safeFeet, cYaw, cPitch)) hit = false;
            if (hit) { rollback("Столкновение!"); return; }
            lastSafe = ep;
        }
        // прогноз курса: клиент сам доворачивает гаст к взгляду (8% за тик); пока наши пакеты дойдут, он повернёт дальше
        int every = Math.max(1, mod.config().getInt("flight.model-update-ticks", 1));
        aimYaw = shipYaw;                                                       // курс модели = курс сервера (без прогноза)
        aimPitch = shipPitch;
        histYaw[(int) Math.floorMod(ticks, 64L)] = aimYaw;
        histPitch[(int) Math.floorMod(ticks, 64L)] = aimPitch;
        if (ghastCam) {
            // физика — на сервере; оси — по курсу, который клиент успеет довернуть, пока дойдёт пакет скорости
            int n = Math.clamp((int) Math.round(pilot.getLatency() / 50.0), 0, 12);
            aimYaw = Rot.wrap(shipYaw + Rot.wrap(look.yaw() - shipYaw) * (1 - Math.pow(0.92, n)));
            physics(look);
            if (!ghastCamSent && ticks >= 2) {
                pilot.sendPacket(new net.minestom.server.network.packet.server.play.CameraPacket(engine.getEntityId()));
                ghastCamSent = true;
            }
            if (helmetAim && ticks % 40 == 2) sendHelmet(true);                // повторяем: клиент мог перезаписать слот
            if (reticle != null) {                                              // прицел — по взгляду (центр экрана — нос)
                // мышь клиент двигает сразу, а сюда она приходит через полпинга — продолжаем её движение вперёд.
                // Вертикаль прицела шейдер берёт у камеры (мгновенно), отсюда важен только курс.
                double eyw = Rot.wrap(ep.yaw());
                double lookU = Double.isNaN(rawLook) ? look.yaw() : rawLook;
                if (Double.isNaN(engUnw)) engUnw = lookU - Rot.wrap(lookU - eyw);       // ближайший к взгляду оборот
                else engUnw += Rot.wrap(eyw - prevEngWrapped);
                prevEngWrapped = eyw;
                int rttT = Math.clamp((int) Math.round(pilot.getLatency() / 50.0) + 1, 1, 10);
                double dly = Double.isNaN(prevLookYaw) ? 0 : lookU - prevLookYaw;
                prevLookYaw = lookU;
                // пока наша поправка взгляда в пути — скачки взгляда не считаем движением мыши (иначе раскачка)
                if (ticks <= corrQuietUntil || !Double.isFinite(dly) || Math.abs(dly) > 90) dly = 0;
                lookRate += (dly - lookRate) * mod.config().getDouble("reticle.predict-smoothing", 0.5);
                double ahead = pilot.getLatency() / 100.0 + mod.config().getDouble("reticle.predict-extra", 1);  // тиков: полпинга + запас
                double sc = mod.config().getDouble("reticle.ghast-screen-yaw", 42);
                // насколько мышь ушла от носа — с памятью оборотов (не по кругу)
                double off = lookU - engUnw;
                // Дальше 180° «память» уже неправда: клиент доворачивает гаст кратчайшим путём, т.е. в другую сторону.
                // Признаём его выбор — иначе тянем мышь против гаста и корабль крутится по кругу (журнал 22ноя: −197° навсегда).
                if (Math.abs(off) > 180) {
                    engUnw = lookU - Rot.wrap(lookU - eyw);
                    off = lookU - engUnw;
                }
                // у края упреждение не нужно — только раскачивает прицел
                double pk = mod.config().getDouble("reticle.predict", 1) * Math.clamp(1 - (Math.abs(off) - sc * 0.6) / (sc * 0.4), 0, 1);
                double py = Rot.wrap(look.yaw() + lookRate * ahead * pk);
                // предел мыши по горизонтали от носа: перебор снимаем ОТНОСИТЕЛЬНЫМ поворотом взгляда ровно на его величину
                // (без скачка: мышь «упирается»); раз в пинг — пока клиент не применил, повторно не шлём
                // Тормозим ЗАРАНЕЕ: где будет мышь, когда дойдёт поправка (с учётом поправок в пути) — гаст у клиента
                // доворачивает кратчайшим путём, и за ±180° он бы развернулся не туда.
                // Перебор снимаем одной относительной поправкой и ждём пинг, пока клиент её применит. Только если клиент
                // жив (поворот приходил недавно), величина ограничена — никакой раскачки и огромных углов.
                double lim = mod.config().getDouble("reticle.ghast-max-yaw", 150);
                if (!Double.isFinite(off) || Math.abs(off) > 100000) { engUnw = Double.NaN; off = 0; }
                boolean alive = ticks - rawLookTick <= 4;
                if (alive && Math.abs(off) > lim + 0.5 && ticks >= yawClampUntil) {
                    double corr = Math.clamp(-Math.signum(off) * (Math.abs(off) - lim), -180, 180);
                    pilot.sendPacket(new net.minestom.server.network.packet.server.play.PlayerRotationPacket((float) corr, true, 0f, true));
                    yawClampUntil = ticks + rttT + 1;
                    corrQuietUntil = ticks + rttT + 2;
                    lookRate = 0;
                }
                // на экране — мягко прижимаем к краю: смещение S·th(x/S) никогда не больше S (≈ край экрана), без скачков
                // смещение на экране: от носа, который клиент уже довернул (aimYaw), с памятью стороны
                double offDisp = off + lookRate * ahead * pk - Rot.wrap(aimYaw - shipYaw);
                double scr = Math.toRadians(sc * Math.tanh(Math.clamp(offDisp, -lim, lim) / sc));
                // прицел привязан к КАМЕРЕ (billboard VERTICAL: курс системы = курс камеры, нос — −z, вправо — +x):
                // поворот камеры (клиент) его не сдвигает, с сервера — только смещение от носа. Вертикаль — шейдер.
                double d = mod.config().getDouble("reticle.distance", 200), eyeY = 1.62 - riderY();
                // доводка по вертикали: камера уже там (клиент), а нос корабля догоняет — прицел выше/ниже кольца
                // на этот «недоворот» (× усиление), шейдер прибавляет его к уровню камеры
                double upOff = Math.toRadians(Math.clamp((shipPitch - camPitchEst) * mod.config().getDouble("reticle.pitch-lag-gain", 1.5), -40, 40));
                double ce = Math.cos(upOff);
                Vec tr = new Vec(Math.sin(scr) * ce * d, eyeY + Math.sin(upOff) * d, -Math.cos(scr) * ce * d);
                int ri = Math.max(0, mod.config().getInt("reticle.ghast-interp-ticks", 2));
                // приборы: скорость (бл/с) и высота — числа и полоски; текст шлём, только когда что-то поменялось
                double max = mod.config().getDouble("flight.max-speed", 50), boostK = mod.config().getDouble("flight.boost", 1.4);
                int spd = (int) Math.round(Math.sqrt(vx * vx + vy * vy + vz * vz) * 20);
                int alt = fp.blockY();
                int hMinY = instance.getCachedDimensionType().minY(), hY = instance.getCachedDimensionType().height();
                int spdFill = (int) Math.round(Math.clamp(spd / (max * boostK), 0, 1) * 255);
                int altFill = (int) Math.round(Math.clamp((alt - hMinY) / (double) hY, 0, 1) * 255);
                long key = ((long) spd << 40) ^ ((long) (alt & 0xFFFF) << 20) ^ ((long) spdFill << 8) ^ altFill ^ ((long) Math.round(-shipPitch) << 56);
                Component hud = key != lastHudKey ? ghastHud(spd, alt, spdFill, altFill) : null;
                lastHudKey = key;
                reticle.editEntityMeta(TextDisplayMeta.class, m -> {
                    m.setTransformationInterpolationStartDelta(0);
                    m.setTransformationInterpolationDuration(ri);
                    m.setTranslation(tr);
                    if (hud != null) m.setText(hud);
                });
            }
        } else physics(look);
        if (ticks % every == 0 && (Double.isNaN(shownYaw) || Math.abs(Rot.wrap(aimYaw - shownYaw)) > 0.05 || Math.abs(aimPitch - shownPitch) > 0.05 || Math.abs(roll - shownRoll) > 0.2)) {
            shownYaw = aimYaw;
            shownPitch = aimPitch;
            shownRoll = roll;
            updateModel(Math.max(1, mod.config().getInt("flight.model-interp-ticks", 3)));
        }
        updateCamera();
        boostFov();
        updateMarks(look);
        if (!ghastCam && ticks % 10 == 0) {                                     // в режиме ghast всё — на HUD
            double off = Math.hypot(Rot.wrap(aimVYaw - shipYaw), Math.clamp(aimVPitch, -maxPitch, maxPitch) - shipPitch);
            pilot.sendActionBar(Component.text("«" + ship.template + "»  " + Math.round(Math.sqrt(vx * vx + vy * vy + vz * vz) * 20) + " бл/с" + (pilot.inputs().sprint() && pilot.inputs().forward() ? " ФОРСАЖ" : "") + "  высота " + fp.blockY() + "  нос " + Math.round(-shipPitch) + "°"
                    + (off > 3 ? "  ◎ доводка" : "  ◎ на цели") + "   1/2 — вид   F — посадка", NamedTextColor.AQUA));
        }
    }

    /** Журнал: пакеты пилоту по типам; «→гаст» — пакет про сущность-двигатель (его быть не должно, кроме меты/атрибутов). */
    void packetOut(Object packet) {
        if (!mod.config().getBoolean("flight.debug", true)) return;
        String name = packet.getClass().getSimpleName();
        LivingEntity e = engine;
        if (e != null && packet instanceof Record r) {
            try {
                for (var c : r.getClass().getRecordComponents())
                    if (c.getName().equals("entityId") && c.getType() == int.class && (int) c.getAccessor().invoke(r) == e.getEntityId()) { name += "→гаст"; break; }
            } catch (Throwable ignored) {}
        }
        dbgPackets.merge(name, 1, Integer::sum);
    }

    /** Оси корабля {нос xyz, правый борт xyz, верх xyz} — ортонормированные (верх = правый × нос). */
    static double[] basis(double yaw, double pitch) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch), cp = Math.cos(pr);
        double fx = -Math.sin(yr) * cp, fy = -Math.sin(pr), fz = Math.cos(yr) * cp;
        double rx = -Math.cos(yr), rz = -Math.sin(yr);
        return new double[]{fx, fy, fz, rx, 0, rz, -rz * fy, rz * fx - rx * fz, rx * fy};
    }

    /** Доля пути к цели за тик, чтобы пройти 90% за t секунд. */
    static double rate(double seconds) {
        return seconds <= 0 ? 1 : 1 - Math.pow(0.1, 1 / (20 * seconds));
    }

    /**
     * Физика корабля. Скорость раскладываем по осям корабля: вперёд (по носу, с наклоном), вбок, вверх.
     * Вперёд — разгон/торможение/накат (инерция), вбок и вверх — слабые двигатели и «сцепление» (гасит занос).
     * Клиенту — скорость гаста пакетом каждый тик (своего ввода у гаста нет), курс гаст доворачивает сам.
     */
    private void physics(Pos look) {
        var c = mod.config();
        var in = pilot.inputs();
        double max = c.getDouble("flight.max-speed", 50) / 20.0;
        boolean boost = in.sprint() && in.forward();
        int fwd = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
        int side = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
        double[] b = basis(aimYaw, aimPitch);
        double fx = b[0], fy = b[1], fz = b[2], rx = b[3], rz = b[5], ux = b[6], uy = b[7], uz = b[8];
        // цель
        double tf = fwd > 0 ? max * (boost ? c.getDouble("flight.boost", 1.4) : 1) : fwd < 0 ? -max * c.getDouble("flight.reverse", 0.3) : 0;
        double tr = side * max * c.getDouble("flight.strafe", 0.2);
        int vert = (in.jump() ? 1 : 0) - (in.shift() ? 1 : 0);
        double lift = vert * max * c.getDouble("flight.lift", 0.3);                 // пробел — вверх, Shift — вниз (по миру)
        double tF = tf + lift * fy, tR = tr, tU = lift * uy;
        // текущая скорость в осях корабля
        double vF = vx * fx + vy * fy + vz * fz, vR = vx * rx + vz * rz, vU = vx * ux + vy * uy + vz * uz;
        double kF;
        if (fwd == 0) kF = rate(c.getDouble("flight.coast-time", 8));                    // накат: медленно теряет скорость
        else if (tF * vF >= 0 && Math.abs(tF) >= Math.abs(vF)) kF = rate(c.getDouble(boost ? "flight.boost-time" : "flight.accel-time", boost ? 2 : 3));
        else kF = rate(c.getDouble("flight.brake-time", 1.5));
        if (fwd == 0 && lift != 0) kF = Math.max(kF, rate(c.getDouble("flight.grip-time", 1)));
        double kG = rate(c.getDouble("flight.grip-time", 1));
        vF += (tF - vF) * kF;
        vR += (tR - vR) * kG;
        vU += (tU - vU) * kG;
        vx = fx * vF + rx * vR + ux * vU;
        vy = fy * vF + uy * vU;
        vz = fz * vF + rz * vR + uz * vU;
        // предохранитель: не быстрее форсажа (+20%), без NaN
        double cap = max * Math.max(1, c.getDouble("flight.boost", 1.4)) * 1.2, len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (!Double.isFinite(len)) { vx = vy = vz = 0; len = 0; }
        else if (len > cap) { vx *= cap / len; vy *= cap / len; vz *= cap / len; }
        if (len < 1e-4) vx = vy = vz = 0;
        pilot.sendPacket(new EntityVelocityPacket(engine.getEntityId(), new Vec(vx, vy, vz)));
        // крен: в повороте внутренний борт вниз (сильнее на скорости), при A/D — чуть в сторону сдвига
        double turn = yawRate;                                                       // °/тик поворота корабля
        double speed = Math.min(1, Math.abs(vF) / max);
        double target = turn * c.getDouble("flight.bank-per-turn", 5) * (0.3 + 0.7 * speed) + side * c.getDouble("flight.bank-strafe", 12);
        double maxBank = c.getDouble("flight.max-bank", 50);
        target = Math.clamp(target, -maxBank, maxBank) * (c.getBoolean("flight.bank-invert", false) ? -1 : 1);
        roll += (target - roll) * c.getDouble("flight.bank-smoothing", 0.12);
    }

    /** Форсаж: поле зрения шире (FOV = (скорость/0.1 + 1)/2 — так считает клиент, анимирует плавно). */
    private void boostFov() {
        var attr = pilot.getAttribute(Attribute.MOVEMENT_SPEED);
        if (Double.isNaN(moveSpeedBase)) moveSpeedBase = attr.getBaseValue();
        boolean on = pilot.inputs().sprint() && pilot.inputs().forward();
        if (on == fovBoost) return;
        fovBoost = on;
        attr.setBaseValue(on ? moveSpeedBase * mod.config().getDouble("flight.boost-fov", 1.3) : moveSpeedBase);
    }

    /** Шлем-прицел только пилоту (другие его не видят — у них шейдер не нарисует чужой прицел). */
    private void sendHelmet(boolean on) {
        ItemStack it = pilot.getEquipment(EquipmentSlot.HELMET);
        if (on) {
            it = ItemStack.of(Material.IRON_HELMET);
            var eq = it.get(DataComponents.EQUIPPABLE);
            if (eq != null) it = it.with(DataComponents.EQUIPPABLE, eq.withAssetId(ShipsModule.AIM_ASSET));
        }
        pilot.sendPacket(new net.minestom.server.network.packet.server.play.EntityEquipmentPacket(pilot.getEntityId(), java.util.Map.of(EquipmentSlot.HELMET, it)));
    }

    private int ghastDots = 14;
    private long lastHudKey = Long.MIN_VALUE;

    /**
     * HUD пилота в режиме ghast (рисует шейдер rendertype_text, глифы цвета #FD g b): круг-компас, буквы сторон света,
     * полоски скорости и высоты с подписями и числами, пунктир, кольцо носа, прицел мыши. Порядок — снизу вверх.
     */
    private Component ghastHud(int spd, int alt, int spdFill, int altFill) {
        var font = net.kyori.adventure.key.Key.key("hoka_ships", "hud");
        var t = Component.text().font(font);
        t.append(Component.text("", TextColor.color(0xFD, 3, 255)));                // круг-компас
        for (int i = 0; i < 4; i++) t.append(Component.text(String.valueOf((char) (0xE010 + i)), TextColor.color(0xFD, 4, i)));  // С В Ю З
        t.append(Component.text("", TextColor.color(0xFD, 5, spdFill)));            // полоска скорости (глиф любой)
        t.append(Component.text("", TextColor.color(0xFD, 6, altFill)));            // полоска высоты
        t.append(Component.text("", TextColor.color(0xFD, 8, 0)));                  // подписи
        t.append(Component.text("", TextColor.color(0xFD, 8, 1)));
        t.append(Component.text("", TextColor.color(0xFD, 8, 2)));
        hudNumber(t, 0, String.valueOf(Math.clamp(spd, 0, 9999)));                     // числа — по центру своих мест
        hudNumber(t, 1, String.valueOf(Math.clamp(alt, -999, 9999)));
        int up = (int) Math.round(Math.clamp(-shipPitch, -90, 90));                     // наклон носа, + — вверх
        hudNumber(t, 2, (up > 0 ? "+" : "") + up);
        for (int i = 1; i <= ghastDots; i++)
            t.append(Component.text("", TextColor.color(0xFD, 1, (int) Math.round(255.0 * i / (ghastDots + 1)))));
        t.append(Component.text("", TextColor.color(0xFD, 0, 255)));                // кольцо носа — центр
        t.append(Component.text("", TextColor.color(0xFD, 2, 0)));                  // прицел мыши
        return t.build();
    }

    /** Число на HUD: группа 0 скорость / 1 высота / 2 наклон; до 4 знаков (цифры, «−», «+»), по центру. */
    private static void hudNumber(net.kyori.adventure.text.TextComponent.Builder t, int grp, String v) {
        if (v.length() > 4) v = v.substring(0, 4);
        int n = v.length();
        for (int i = 0; i < n; i++) {
            char ch = v.charAt(i);
            char g = ch >= '0' && ch <= '9' ? (char) (0xE020 + ch - '0') : ch == '-' ? '' : ch == '+' ? '' : 0;
            if (g == 0) continue;
            t.append(Component.text(String.valueOf(g), TextColor.color(0xFD, 7, grp * 16 + (n - 1) * 4 + i)));
        }
    }

    /** Крен модели в режиме ghast (физику ведёт клиент): по темпу поворота и A/D. */
    private void physicsBank() {
        var c = mod.config();
        var in = pilot.inputs();
        int side = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
        double target = yawRate * c.getDouble("flight.bank-per-turn", 5) + side * c.getDouble("flight.bank-strafe", 12);
        double maxBank = c.getDouble("flight.max-bank", 50);
        target = Math.clamp(target, -maxBank, maxBank) * (c.getBoolean("flight.bank-invert", false) ? -1 : 1);
        roll += (target - roll) * c.getDouble("flight.bank-smoothing", 0.12);
    }

    /** Журнал полёта раз в 5 с: скорость, как часто приходят координаты гаста от клиента, паузы, откаты. */
    private void debugTick(Pos ep) {
        if (!mod.config().getBoolean("flight.debug", true)) return;
        if (dbgLast != null) {
            double d = ep.distance(dbgLast);
            if (d > 1e-4) { dbgMoved++; dbgDist += d; dbgGap = 0; } else dbgMaxGap = Math.max(dbgMaxGap, ++dbgGap);
        }
        dbgLast = ep;
        if (ticks % mod.config().getInt("flight.debug-every", 100) == 0) {
            mod.logger().info("полёт {}: {} бл/с, движение в {}/100 тиков, пауза до {} тиков, откатов {}, скорость сервера {} бл/с, пинг {} мс",
                    pilot.getUsername(), Math.round(dbgDist / 5.0 * 10) / 10.0, dbgMoved, dbgMaxGap, dbgRollbacks, Math.round(Math.sqrt(vx * vx + vy * vy + vz * vz) * 200) / 10.0, pilot.getLatency());
            StringBuilder sb = new StringBuilder();
            dbgPackets.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(14)
                    .forEach(en -> sb.append(en.getKey()).append('=').append(en.getValue()).append(' '));
            mod.logger().info("полёт {}: пакеты за 5 с: {}", pilot.getUsername(), sb);
            mod.logger().info("полёт {}: мышь сырая {} (тик {} назад), взгляд Pos {}, гаст Pos {}, гаст склеенный {}, смещение {}, прогноз {}",
                    pilot.getUsername(), Math.round(rawLook * 10) / 10.0, ticks - rawLookTick, Math.round(pilot.getPosition().yaw() * 10) / 10.0,
                    Math.round(engine.getPosition().yaw() * 10) / 10.0, Math.round(engUnw * 10) / 10.0,
                    Math.round((rawLook - engUnw) * 10) / 10.0, Math.round(aimYaw * 10) / 10.0);
            dbgPackets.clear();
            dbgMoved = 0; dbgDist = 0; dbgMaxGap = 0; dbgRollbacks = 0;
        }
    }

    /** Новая скорость поворота (°/тик): к желаемой (доля оставшегося угла, не больше max), меняясь не быстрее accel. */
    static double approach(double rate, double diff, double k, double max, double accel) {
        if (Math.abs(diff) < 0.05 && Math.abs(rate) < accel) return diff;      // приехали — без дрожи
        double want = Math.clamp(diff * k, -max, max);
        double next = rate + Math.clamp(want - rate, -accel, accel);
        if (Math.abs(next) > Math.abs(diff) && Math.signum(next) == Math.signum(diff)) next = diff;  // не перелетать цель
        return next;
    }

    private void updateModel(int interp) {
        if (reticle != null && !ghastCam) {
            double y = shownYaw, pt = shownPitch;
            reticle.editEntityMeta(TextDisplayMeta.class, m -> {
                m.setTransformationInterpolationStartDelta(0);
                m.setTransformationInterpolationDuration(interp);
                reticlePose(m, y, pt);
            });
        }
        if (single != null) {
            float[] q1 = Rot.quat(shownYaw, shownPitch, shownRoll, modelFwd);
            Vec k1 = camOff(shownYaw, shownPitch, shownRoll);
            double ry1 = riderY();
            single.editEntityMeta(ItemDisplayMeta.class, m -> {
                m.setTransformationInterpolationStartDelta(0);
                m.setTransformationInterpolationDuration(interp);
                m.setTranslation(new Vec(k1.x(), k1.y() - ry1, k1.z()));
                m.setLeftRotation(q1);
            });
            if (mine != null && rigidCockpit() && chaseView) {                  // вид сзади: корабль впереди-ниже камеры
                double p = camPitchPred, rollC = shownRoll * mod.config().getDouble("camera.pilot-roll", 1);
                // от камеры до кресла в системе «нос на юг» (курс 0): центр (0, −chH, chD) + поворот (наклон, крен) к креслу
                double[] w = Rot.rotate(Rot.mul(Rot.quatPitch(shownPitch), Rot.quatRoll(rollC)), -shipC[0], -shipC[1], -shipC[2]);
                double bx = -w[0], by = -chH + w[1], bz = -(chD + w[2]);           // → система billboard (нос −z)
                double pr = Math.toRadians(p), cs = Math.cos(pr), sn = Math.sin(pr);
                Vec cam = cockpitCam(p);
                Vec tc = new Vec(cam.x() + bx, cam.y() + by * cs - bz * sn, cam.z() + by * sn + bz * cs);
                float[] qm = Rot.quat(180, shownPitch - p, rollC, modelFwd);
                mine.editEntityMeta(ItemDisplayMeta.class, m -> {
                    m.setTransformationInterpolationStartDelta(0);
                    m.setTransformationInterpolationDuration(Math.max(1, mod.config().getInt("camera.cockpit-interp", 1)));
                    m.setTranslation(tc);
                    m.setLeftRotation(qm);
                });
            } else if (mine != null && rigidCockpit()) {                        // кабина жёстко на камере: курс и наклон — клиент
                // наклоны корабля относительно камеры: крен в поворотах и «недоворот» носа по вертикали — вращаем
                // ВОКРУГ КАМЕРЫ (кресла), чтобы кресло не уезжало: T = камера + R·(T0 − камера), L = R·модель
                double rollP = 0;                                                // из кабины крена нет (камера не кренится)
                double lagP = Math.clamp(shownPitch - camPitchPred, -30, 30) * mod.config().getDouble("camera.pilot-pitch-lag", 1);
                float[] qm = Rot.quat(180, lagP, rollP, modelFwd);
                float[] rb = Rot.mul(Rot.mul(Rot.mul(Rot.quat(180), Rot.quatPitch(lagP)), Rot.quatRoll(rollP)), Rot.quat(-180));
                Vec t0 = cockpitT(camPitchPred), cam = cockpitCam(camPitchPred);  // кабина наклонена с камерой (наклон — с упреждением)
                double[] dr = Rot.rotate(rb, t0.x() - cam.x(), t0.y() - cam.y(), t0.z() - cam.z());
                Vec tc = new Vec(cam.x() + dr[0], cam.y() + dr[1], cam.z() + dr[2]);
                mine.editEntityMeta(ItemDisplayMeta.class, m -> {
                    m.setTransformationInterpolationStartDelta(0);
                    m.setTransformationInterpolationDuration(Math.max(1, mod.config().getInt("camera.cockpit-interp", 1)));
                    m.setTranslation(tc);
                    m.setLeftRotation(qm);
                });
            } else if (mine != null) {                                          // курс — у клиента (billboard), отсюда только наклон и крен
                float[] qm = Rot.quat(180, shownPitch, shownRoll, modelFwd);
                Vec km = camOff(180, shownPitch, shownRoll).add(cockpitShift());
                mine.editEntityMeta(ItemDisplayMeta.class, m -> {
                    m.setTransformationInterpolationStartDelta(0);
                    m.setTransformationInterpolationDuration(Math.max(interp, mod.config().getInt("flight.pilot-view-interp", 3)));
                    m.setTranslation(new Vec(km.x(), km.y() - ry1, km.z()));
                    m.setLeftRotation(qm);
                });
            }
            return;
        }
        double rel = shownYaw - baseYaw;
        float[] q = Rot.quat(rel);
        double ry = riderY(), sh = seatH();
        for (Part p : parts) {
            if (p.display == null) continue;                                    // внутренний блок — модели нет
            Vec o = corner(p, rel, sh);
            Vec k2 = camOff(shownYaw, 0);
            Vec t = new Vec(o.x() + k2.x(), o.y() - ry + k2.y(), o.z() + k2.z());
            p.display.editEntityMeta(BlockDisplayMeta.class, m -> {
                m.setTransformationInterpolationStartDelta(0);
                m.setTransformationInterpolationDuration(interp);
                m.setTranslation(t);
                m.setLeftRotation(q);
            });
        }
    }

    /** Пересекается ли корабль (курс yaw, наклон pitch, ноги пилота в pos) с непроходимыми блоками или незагруженными чанками. */
    private boolean collides(Pos feetPos, double yaw, double pitch) {
        Pos pos = feetPos.add(camOff(yaw, pitch));                               // место пилота в корабле
        for (double[] v : pts) {
            double[] w = Rot.orient(v[0], v[1], v[2], yaw, pitch);
            int x = (int) Math.floor(pos.x() + w[0]), y = (int) Math.floor(pos.y() + w[1]), z = (int) Math.floor(pos.z() + w[2]);
            if (!instance.isChunkLoaded(x >> 4, z >> 4)) return true;
            if (solid(instance.getBlock(x, y, z))) return true;
        }
        return false;
    }

    /** Промежуточные точки между прошлой безопасной и текущей (шаг ≤ 0.5 блока) — чтобы не проскакивать стены. */
    private boolean sweptCollides(Pos from, Pos to, double yaw, double pitch) {
        double dist = from.distance(to);
        int steps = (int) Math.ceil(dist / 0.5);
        for (int i = 1; i < steps; i++) {
            double k = (double) i / steps;
            if (collides(new Pos(from.x() + (to.x() - from.x()) * k, from.y() + (to.y() - from.y()) * k, from.z() + (to.z() - from.z()) * k), yaw, pitch)) return true;
        }
        return false;
    }

    private static boolean solid(Block b) { return !b.isAir() && !b.registry().isReplaceable(); }

    /**
     * Посадка: курс округляется до 90°, корабль ставится по сетке в текущем месте. Вся новая коробка корабля
     * должна быть свободна (воздух/трава) и не задевать другие корабли — иначе false (летим дальше).
     */
    boolean land() { return land(false); }

    /** force — аварийно: наклон носа не важен (блоки всё равно ставятся ровно). */
    boolean land(boolean force) {
        if (finished) return true;
        double lim = mod.config().getDouble("flight.land-max-pitch", 30);
        if (!force && Math.abs(shipPitch) > lim) { warn("Выровняйте нос корабля (сейчас " + Math.round(-shipPitch) + "°, можно до ±" + Math.round(lim) + "°)"); return false; }
        int lagL = Math.clamp((int) Math.round(pilot.getLatency() / 50.0), 0, 40);
        Pos pp = feet(engine.getPosition()).add(camOff(histYaw[histAt(lagL)], histPitch[histAt(lagL)]));
        int q = Math.floorMod((int) Math.round((shipYaw - baseYaw) / 90.0), 4);
        int sx = (int) Math.floor(pp.x()), sy = (int) Math.floor(pp.y() - seatH() + 0.01), sz = (int) Math.floor(pp.z());
        // новое положение корабля по клетке пилота
        int rot = (ship.rot + q) & 3;
        int[] sr = Rot.xz(ship.t.seat[0], ship.t.seat[2], ship.t.sx, ship.t.sz, rot);
        Ship probe = new Ship();
        probe.t = ship.t; probe.rot = rot; probe.state = Ship.State.PARKED;
        probe.ax = sx - sr[0]; probe.ay = sy - ship.t.seat[1]; probe.az = sz - sr[1];
        int[] size = probe.size();
        int minY = instance.getCachedDimensionType().minY(), maxY = minY + instance.getCachedDimensionType().height();
        if (probe.ay < minY || probe.ay + size[1] > maxY) { warn("Здесь не сесть: корабль выходит за высоту мира"); return false; }
        String map = mod.server().mapName(instance).orElse(null);
        for (Ship other : mod.ships.values()) {
            if (other == ship || other.state != Ship.State.PARKED || !java.util.Objects.equals(other.map, map)) continue;
            int[] os = other.size();
            if (probe.ax < other.ax + os[0] && other.ax < probe.ax + size[0] && probe.ay < other.ay + os[1] && other.ay < probe.ay + size[1]
                    && probe.az < other.az + os[2] && other.az < probe.az + size[2]) { warn("Здесь не сесть: рядом корабль " + other.ownerName); return false; }
        }
        for (int y = 0; y < size[1]; y++) for (int x = 0; x < size[0]; x++) for (int z = 0; z < size[2]; z++) {
            int wx = probe.ax + x, wy = probe.ay + y, wz = probe.az + z;
            if (!instance.isChunkLoaded(wx >> 4, wz >> 4)) { warn("Здесь не сесть: мир ещё не загружен"); return false; }
            if (solid(instance.getBlock(wx, wy, wz))) { warn("Здесь не сесть: мешают блоки (x " + wx + " y " + wy + " z " + wz + ")"); return false; }
        }
        // ставим: смещение от клетки пилота, повёрнутое ровно на q × 90°
        List<Vec> pos = new ArrayList<>(parts.size());
        for (Part p : parts) {
            int[] r = turn(p.dx, p.dz, q);
            pos.add(new Vec(sx + r[0], sy + p.dy, sz + r[1]));
        }
        for (int i = 0; i < parts.size(); i++) instance.setBlock(pos.get(i), Rot.block(parts.get(i).block, q), false);
        for (Vec w : pos) instance.setBlock(w, instance.getBlock(w));             // обновить соседей
        ship.rot = rot; ship.ax = probe.ax; ship.ay = probe.ay; ship.az = probe.az;
        ship.state = Ship.State.PARKED;
        finish(new Pos(sx + 0.5, sy + seatH(), sz + 0.5, (float) (baseYaw + q * 90), pilot.getPosition().pitch()), q);
        pilot.sendMessage(Component.text("Посадка.", NamedTextColor.GREEN));
        return true;
    }

    /** Целочисленный поворот смещения (dx, dz) на q × 90° по часовой — как Rot.yaw, но без погрешностей. */
    static int[] turn(int dx, int dz, int q) {
        return switch (q & 3) {
            case 1 -> new int[]{-dz, dx};
            case 2 -> new int[]{-dx, -dz};
            case 3 -> new int[]{dz, -dx};
            default -> new int[]{dx, dz};
        };
    }

    private void warn(String t) { if (pilot.isOnline()) pilot.sendActionBar(Component.text(t, NamedTextColor.RED)); }

    /**
     * Пилот вышел / сменил карту / модуль выключается: сесть на месте (если пилот ещё в этом мире), иначе убрать
     * корабль (вернётся при призыве). Модель и состояние пилота восстанавливаются всегда.
     */
    void emergency() {
        if (finished) return;
        try {
            if (pilot.isOnline() && pilot.getInstance() == instance && land(true)) return;
        } catch (Throwable e) {
            mod.logger().error("аварийная посадка {}: {}", ship.ownerName, e.toString());
        }
        ship.state = Ship.State.STOWED;
        finish(lastSafe != null && engine != null ? feet(lastSafe).add(camOff(histYaw[histAt(0)], 0)) : pilot.getPosition(), 0);
    }

    /** Убрать модель, вернуть пилота. */
    private void finish(Pos pivot, int q) {
        finished = true;
        if (engine != null) { engine.removePassenger(pilot); engine.remove(); engine = null; }
        if (vacuumBefore != null && pilot.isOnline()) mod.vacuum().set(pilot, vacuumBefore);
        if (single != null) { pilot.removePassenger(single); single.remove(); single = null; }
        if (mine != null) { if (mine.getVehicle() != null) mine.getVehicle().removePassenger(mine); mine.remove(); mine = null; }
        if (reticle != null) { pilot.removePassenger(reticle); reticle.remove(); reticle = null; }
        for (Entity e : marks) { pilot.removePassenger(e); e.remove(); }
        marks.clear();
        if (ghastCamSent && pilot.isOnline()) pilot.sendPacket(new net.minestom.server.network.packet.server.play.CameraPacket(pilot.getEntityId()));
        if (helmetAim && pilot.isOnline()) sendHelmet(false);
        if (!Double.isNaN(moveSpeedBase) && pilot.isOnline()) pilot.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(moveSpeedBase);
        if (camEnt != null) {
            if (pilot.isOnline()) pilot.sendPacket(new net.minestom.server.network.packet.server.play.CameraPacket(pilot.getEntityId()));
            pilot.removePassenger(camEnt);
            camEnt.remove();
            camEnt = null;
        }
        for (Entity h : hubs) { pilot.removePassenger(h); }
        for (Part p : parts) if (p.display != null) p.display.remove();
        for (Entity h : hubs) h.remove();
        hubs.clear();
        parts.clear();
        if (pilot.isOnline()) {
            pilot.setInvisible(false);
            if (!Double.isNaN(pilotScaleBefore)) pilot.getAttribute(Attribute.SCALE).setBaseValue(pilotScaleBefore);
            pilot.getAttribute(Attribute.CAMERA_DISTANCE).setBaseValue(cameraBase);
            pilot.setFlyingSpeed(flySpeed);
            pilot.setGameMode(gm);
            pilot.setAllowFlying(allowFly || gm == GameMode.CREATIVE);
            pilot.setFlying(flying && pilot.isAllowFlying());
            if (pilot.getInstance() == instance) pilot.teleport(pivot);
        }
        ship.flight = null;
        mod.saveShips();
    }
}
