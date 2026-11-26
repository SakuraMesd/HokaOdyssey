package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.gen.GenBounds;
import ink.hoka.odyssey.gen.GenIdTable;
import ink.hoka.odyssey.gen.GenJson;
import ink.hoka.odyssey.gen.GenWire;
import ink.hoka.odyssey.server.tall.gen.CubeGens;
import ink.hoka.odyssey.server.tall.gen.MinestomGenIds;
import ink.hoka.odyssey.server.tall.gen.ModuleCubeGen;
import ink.hoka.odyssey.server.tall.gen.PortableGens;
import ink.hoka.odyssey.server.tall.gen.PortableProvider;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Генерация нетронутых кубов на клиенте (ядро 0.59+, протокол — {@link GenWire}). Состояние игрока: слот op 20,
 * статус op 8 (пока не «ок» — старый путь op 2/13), расхождения. Сверка: хэш в op 21 для кубов в памяти сервера,
 * выборка op 9 ({@link Verify}: генератором напрямую, без CubeStore, ≤ 20 кубов/с на сервер), op 23 по команде.
 * Больше 3 расхождений за 10 минут — игроку op 20 со slot 0 (старый путь); так у двух игроков карты — генерация на
 * клиенте для карты выключена до перезапуска.
 */
public final class ClientGen {

    private ClientGen() {}

    private static final Logger LOG = LoggerFactory.getLogger("cubes");
    /** -Dhoka.gen.client=false — генерация на клиенте выключена для всех. */
    static final boolean ON = !"false".equalsIgnoreCase(System.getProperty("hoka.gen.client"));
    /** Зона вокруг игрока (кубов), которую сервер всё равно грузит: правки, сущности, секреты, бесплатная сверка. */
    static final int ZONE = Math.max(0, Integer.getInteger("hoka.gen.zone", 1));
    static final int PENDING = -1, OK = GenWire.ST_OK;

    static final class P {
        volatile int slot;
        volatile Instance inst;
        /** −1 — ждём op 8; 0 — ок; иначе статус клиента; DISABLED — выключено сервером. */
        volatile int status = PENDING;
        volatile int threads;
        volatile boolean adminOff;
        /** Ключ текущего op 20 и ключ генератора, на который клиент уже ответил «ок» (повторный вход — без ожидания op 8). */
        volatile String key, okKey;
        final ArrayDeque<Long> mismatches = new ArrayDeque<>();
        long labels, samples, bad;
    }

    static final int DISABLED = 100;
    private static final Map<UUID, P> STATES = new ConcurrentHashMap<>();
    private static final AtomicInteger SLOTS = new AtomicInteger();
    /** Карты, где генерация на клиенте выключена (расхождения у двух игроков) — до перезапуска. */
    private static final Set<Instance> MAP_OFF = ConcurrentHashMap.newKeySet();
    /** Кто на карте уже отключён по расхождениям. */
    private static final Map<Instance, Set<UUID>> BAD_PLAYERS = new ConcurrentHashMap<>();

    /** Отладка: меток (кубов op 21), кубов с хэшем, выборок, расхождений, отключений игроков. */
    static final AtomicLong labels = new AtomicLong(), labelsHashed = new AtomicLong(), samples = new AtomicLong(),
            mismatchTotal = new AtomicLong(), disabledPlayers = new AtomicLong(), uniformLabels = new AtomicLong(), secretsSent = new AtomicLong();

    /** Ядро клиента умеет (0.59+), свет на клиенте (иначе серверу нужен свет кубов — их пришлось бы грузить). */
    static boolean coreOk(Player p) { return ON && Handshake.coreAtLeast(p, Handshake.CLIENT_GEN_CORE) && CubeStreamer.clientLight(p); }

    /** Генератор карты, если на ней возможна генерация на клиенте (переносимый, ленивая карта, раздача jar есть). */
    static ModuleCubeGen portableGen(Instance inst) {
        if (inst == null || MAP_OFF.contains(inst) || !TallWorld.isCubes(inst) || !CubeStore.lazy(inst)) return null;
        ModuleCubeGen g = CubeGens.module(inst);
        if (g == null || !g.portable()) return null;
        PortableProvider pp = g.portableProvider();
        if (pp == null || (pp.jar != null && !PortableGens.canPublish())) return null;
        if (!g.objects.isEmpty() && (!PortableGens.canPublish() || !ink.hoka.odyssey.server.objects.ObjectPacks.published(g.objects))) return null;
        return g;
    }

    /** Генерация на клиенте сейчас действует для игрока на карте inst: генератор карты или null. */
    static ModuleCubeGen active(Player p, Instance inst) {
        P s = STATES.get(p.getUuid());
        if (s == null || s.status != OK || s.slot == 0 || s.inst != inst || s.adminOff) return null;
        return portableGen(inst);
    }

    static int slot(Player p) { P s = STATES.get(p.getUuid()); return s == null ? 0 : s.slot; }

    /** Вход в инстанс (после op 12/op 19, до скелетов): op 20 — описание генератора или сброс (slot 0). */
    static void enter(Player p, Instance inst) {
        if (!coreOk(p)) return;
        P s = STATES.computeIfAbsent(p.getUuid(), u -> new P());
        ModuleCubeGen g = s.adminOff ? null : portableGen(inst);
        if (g != null && needsObjectsCore(g) && !Handshake.coreAtLeast(p, Handshake.OBJECTS_CORE)) g = null;   // ядро < 0.60 — op 2
        s.inst = inst;
        s.status = PENDING;
        if (g == null) {
            if (s.slot != 0) send(p, GenWire.encodeDef(GenWire.Def.off()));
            s.slot = 0;
            return;
        }
        s.slot = SLOTS.incrementAndGet() & 0x3FFFFFFF;
        if (s.slot == 0) s.slot = SLOTS.incrementAndGet();
        GenWire.Def d = def(g, s.slot);
        String k = key(d);
        s.key = k;
        send(p, GenWire.encodeDef(d));
        // тот же генератор, что клиент уже принял (смена карты/телепорт между мирами): клиент включит его сразу по op 20
        // (ClientGen.onDef: загружен тот же — enable до любых op 21), ждать op 8 — значит гнать первое окно op 2.
        // Если клиент всё же не сможет — пришлёт не-«ок», genOff перешлёт окно op 2.
        if (k.equals(s.okKey)) s.status = OK;
    }

    /** Генератору нужно ядро 0.60 (объекты или API 2). */
    static boolean needsObjectsCore(ModuleCubeGen g) {
        PortableProvider pp = g.portableProvider();
        return g.objects.hash() != 0 || (pp != null && pp.apiLevel >= 2);
    }

    /** Ключ генератора op 20 — как у клиента (ClientGen.key): id, версия, sha, сид, настройки, границы, пакет объектов. */
    static String key(GenWire.Def d) {
        return d.id() + "@" + d.version() + "/" + d.sha256() + "/" + d.seed() + "/" + d.optionsJson() + "/" + d.minY() + ".." + d.maxY() + "/" + d.size()
                + (d.hasObjects() ? "/obj:" + d.objectsSha256() : "");
    }

    /** op 20 для генератора g. */
    static GenWire.Def def(ModuleCubeGen g, int slot) {
        PortableProvider pp = g.portableProvider();
        GenIdTable ids = MinestomGenIds.get();
        var b = g.bounds();
        String opts = GenJson.write(g.settings.options());
        var obj = g.objects;
        return new GenWire.Def(slot, pp.id(), pp.version(), pp.filePath, pp.sha256, g.settings.seed(), opts,
                b.minY(), b.maxY(), b.size(), ids.statesCount(), ids.statesDigest(), ids.biomesDigest(), g.genFingerprint(),
                g.secretsEnabled() ? 1 : 0, obj.isEmpty() ? "" : ink.hoka.odyssey.server.objects.ObjectPacks.path(obj),
                obj.packSha256(), obj.hash());
    }

    /** C2S op 8 GEN_STATUS. */
    static void onStatus(Player p, GenWire.Status st) {
        P s = STATES.get(p.getUuid());
        if (s == null || st.slot() != s.slot || s.slot == 0) return;
        s.threads = st.threads();
        if (s.status == DISABLED) return;
        int was = s.status;
        s.status = st.status();
        s.okKey = st.status() == OK ? s.key : null;
        if (st.status() == OK) {
            if (was != OK) LOG.info("{}: генерация на клиенте включена (потоков {})", p.getUsername(), st.threads());
        } else {
            LOG.info("{}: генерация на клиенте не идёт — статус {} ({})", p.getUsername(), st.status(), statusName(st.status()));
            if (was == OK) MinecraftServer.getSchedulerManager().scheduleNextTick(() -> CubeStreamer.genOff(p));
        }
    }

    static String statusName(int s) {
        return switch (s) {
            case PENDING -> "ждём ответ";
            case 0 -> "ок";
            case 1 -> "нет jar";
            case 2 -> "расходятся номера";
            case 3 -> "расходится отпечаток";
            case 4 -> "ошибка";
            case 5 -> "выключил игрок";
            case DISABLED -> "выключено сервером";
            default -> "статус " + s;
        };
    }

    /** C2S op 9 GEN_SAMPLE: в очередь сверки. */
    static void onSample(Player p, GenWire.Sample sm) {
        P s = STATES.get(p.getUuid());
        if (s == null || sm.slot() != s.slot || s.status != OK) return;
        ModuleCubeGen g = portableGen(s.inst);
        if (g == null) return;
        for (int k = 0; k < sm.pos().length; k++) {
            samples.incrementAndGet();
            s.samples++;
            Verify.offer(new Verify.Job(p.getUuid(), s.inst, g, s.slot, sm.pos()[k], sm.hash()[k]));
        }
    }

    /** Меток op 21 игроку (статистика). */
    static void labelled(Player p, int n, int hashed, int uniform) {
        labels.addAndGet(n);
        labelsHashed.addAndGet(hashed);
        uniformLabels.addAndGet(uniform);
        P s = STATES.get(p.getUuid());
        if (s != null) s.labels += n;
    }

    /** Расхождение хэша у игрока (выборка, WANT по хэшу op 21): учесть; больше 3 за 10 минут — выключить ему. */
    static void mismatch(Player p, long pos, String how) {
        P s = STATES.get(p.getUuid());
        mismatchTotal.incrementAndGet();
        if (s == null) return;
        s.bad++;
        long now = System.currentTimeMillis();
        boolean off;
        synchronized (s.mismatches) {
            s.mismatches.addLast(now);
            while (!s.mismatches.isEmpty() && now - s.mismatches.peekFirst() > 600_000) s.mismatches.pollFirst();
            off = s.mismatches.size() > 3 && s.status == OK;
        }
        LOG.warn("{}: генерация на клиенте разошлась с сервером ({}) в кубе {} {} {}", p.getUsername(), how,
                GenWire.posX(pos), GenWire.posY(pos), GenWire.posZ(pos));
        if (!off) return;
        ModuleCubeGen g = CubeGens.module(s.inst);
        LOG.error("{}: генерация на клиенте ВЫКЛЮЧЕНА — больше 3 расхождений за 10 минут (генератор {})", p.getUsername(), g == null ? "?" : g.tag());
        disable(p);
        Instance inst = s.inst;
        if (inst != null) {
            Set<UUID> bad = BAD_PLAYERS.computeIfAbsent(inst, k -> ConcurrentHashMap.newKeySet());
            bad.add(p.getUuid());
            if (bad.size() >= 2 && MAP_OFF.add(inst)) {
                LOG.error("генерация на клиенте выключена для всей карты до перезапуска: расхождения у {} игроков (генератор {})",
                        bad.size(), g == null ? "?" : g.tag());
                for (Player o : inst.getPlayers()) if (o != p) disable(o);
            }
        }
    }

    /** Выключить генерацию игроку: op 20 slot 0, снять метки (окно перешлётся op 2). Любой поток. */
    static void disable(Player p) {
        P s = STATES.get(p.getUuid());
        if (s == null) return;
        s.okKey = null;
        if (s.slot == 0) return;
        s.status = DISABLED;
        disabledPlayers.incrementAndGet();
        send(p, GenWire.encodeDef(GenWire.Def.off()));
        s.slot = 0;
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> CubeStreamer.genOff(p));
    }

    /** /hocubes gen on|off: off — выключить; on — снять запрет (вступит при следующем входе в мир). */
    static String admin(Player p, boolean on) {
        P s = STATES.computeIfAbsent(p.getUuid(), u -> new P());
        s.adminOff = !on;
        if (!on) { disable(p); return p.getUsername() + ": генерация на клиенте выключена"; }
        if (s.inst != null && p.getInstance() == s.inst) enter(p, s.inst);
        return p.getUsername() + ": генерация на клиенте разрешена (ждём ответ клиента)";
    }

    static void quit(Player p) { STATES.remove(p.getUuid()); }

    private static void send(Player p, byte[] b) { p.sendPacket(new PluginMessagePacket(CubeStreamer.CHANNEL, b)); }

    /** Строка игрока для /hocubes player и genstats. */
    static String describe(Player p) {
        P s = STATES.get(p.getUuid());
        if (s == null) return "генерация на клиенте: " + (coreOk(p) ? "не было входа на карту" : "ядро < 0.59 или свет от сервера");
        return "генерация на клиенте: " + statusName(s.status) + (s.slot != 0 ? ", слот " + s.slot : "") + ", потоков " + s.threads
                + ", меток " + s.labels + ", выборок " + s.samples + ", расхождений " + s.bad + (s.adminOff ? ", ВЫКЛ админом" : "");
    }

    static String stats() {
        return "генерация на клиенте" + (ON ? "" : " (ВЫКЛ -Dhoka.gen.client)") + ": меток " + labels + " (с хэшем " + labelsHashed
                + ", однородных " + uniformLabels + "), секретов кубов " + secretsSent + ", выборок " + samples + " (проверено " + Verify.checked
                + ", очередь " + Verify.QUEUE.size() + ", пропущено " + Verify.dropped + "), расхождений " + mismatchTotal
                + ", отключено игроков " + disabledPlayers + ", карт выкл " + MAP_OFF.size() + ", зона ±" + ZONE;
    }

    /** /hocubes gencheck: op 23 по n кубам окна игрока с метками; хэши — в фоне. Ответ — сколько кубов. */
    static int genCheck(Player p, int n) {
        P s = STATES.get(p.getUuid());
        ModuleCubeGen g = s == null ? null : active(p, s.inst);
        if (g == null) return -1;
        long[] pos = CubeStreamer.genPositions(p, n);
        if (pos.length == 0) return 0;
        Thread.startVirtualThread(() -> {
            long[] h = new long[pos.length];
            try {
                for (int k = 0; k < pos.length; k++) h[k] = g.visHash(GenWire.posX(pos[k]), GenWire.posY(pos[k]), GenWire.posZ(pos[k]));
                send(p, GenWire.encodeCheck(pos, h));
            } catch (Throwable t) { LOG.warn("gencheck: {}", t.toString()); }
        });
        return pos.length;
    }

    // ───────────── выборочная сверка ─────────────

    /** Фоновая сверка выборки (1 виртуальный поток, ≤ RATE кубов/с на сервер; лишнее — отбрасывается). */
    static final class Verify {
        record Job(UUID player, Instance inst, ModuleCubeGen gen, int slot, long pos, long hash) {}

        static final int RATE = Math.max(1, Integer.getInteger("hoka.gen.verify-rate", 20));
        static final LinkedBlockingQueue<Job> QUEUE = new LinkedBlockingQueue<>(512);
        static volatile long checked, dropped;
        private static volatile Thread worker;

        static void offer(Job j) {
            if (!QUEUE.offer(j)) { dropped++; return; }
            if (worker == null) start();
        }

        private static synchronized void start() {
            if (worker != null) return;
            worker = Thread.ofVirtual().name("hoka-gen-verify").start(Verify::run);
        }

        private static void run() {
            long gap = 1_000_000_000L / RATE, next = System.nanoTime();
            while (true) {
                Job j;
                try { j = QUEUE.take(); } catch (InterruptedException e) { return; }
                long now = System.nanoTime();
                if (now < next) { try { Thread.sleep((next - now) / 1_000_000, (int) ((next - now) % 1_000_000)); } catch (InterruptedException e) { return; } }
                next = Math.max(now, next) + gap;
                try { check(j); } catch (Throwable t) { LOG.warn("сверка куба: {}", t.toString()); }
            }
        }

        static void check(Job j) {
            long want = j.gen().visHash(GenWire.posX(j.pos()), GenWire.posY(j.pos()), GenWire.posZ(j.pos()));
            checked++;
            if (want == j.hash()) return;
            Player p = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(j.player());
            if (p == null) return;
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                if (p.getInstance() != j.inst() || slot(p) != j.slot()) return;
                CubeStreamer.forceReal(p, j.pos());                            // правду — op 2
                mismatch(p, j.pos(), "выборка");
            });
        }
    }

    /** Границы генератора карты как в op 20 (тесты). */
    static GenBounds bounds(ModuleCubeGen g) { var b = g.bounds(); return new GenBounds(b.minY(), b.maxY(), b.size()); }
}
