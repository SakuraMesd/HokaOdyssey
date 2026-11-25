package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerChunkLoadEvent;
import net.minestom.server.event.player.PlayerChunkUnloadEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.minestom.server.network.packet.server.play.data.LightData;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кубические чанки, этап 3а (CUBIC-CHUNKS.md §4.7, упрощённо): на кубовой карте столбец уходит игроку скелетом
 * (TallChunk/CubeColumn.getFullDataPacket), а непустые кубы 16×16×16 — только в окне ±{@link #V} секций по высоте от игрока,
 * своим пакетом {@code hoka_odyssey:w}. Игрок сменил секцию по высоте — досылаем вошедшие в окно кубы и велим
 * выгрузить вышедшие. Свет — ванильным пакетом света столбца, только для отправленных кубов.
 * <pre>
 * S2C hoka_odyssey:w (NetworkBuffer):
 *   2 CUBES   varint n; n × (long SectionPos.asLong — X22|Z22|Y20 как в клиенте, short блоков, палитра блоков, палитра биомов,
 *             varint m, m × блок-энтити как в ванильном пакете чанка: byte x<<4|z, short y, varint тип, NBT)
 *   4 UNLOAD  varint n; n × long SectionPos.asLong
 *   5 WINDOW  int от, int до (секции по Y) — окно игрока; вне окна куб «не загружен»: клиент туда не двигается
 *   11 LIGHT  свет кубов компактно (LightOp) — только ядру 0.44.0+; остальным ванильный UpdateLightPacket
 *   12 CACHE  long mapKey (0 — кэш кубов на этой карте выключен), byte протокол 1 — ядру 0.51+, при входе в инстанс
 *   13 CUBES_REF varint n; n × (long pos, long H — CubeHash, varint m, m × блок-энтити как в op 2): куб из кэша клиента
 *   14 LIGHT_REF varint n; n × (long pos, long L): свет куба из кэша клиента (LightOp)
 *   15 HASH_CHECK varint n; n × (long pos, long H) — отладка: клиент сверяет живые секции и отвечает op 6
 *   16 SKY_FLOOR (0.54) varint n; n × (int cx, int cz, int floorSec): куб столбца с секцией ≥ floorSec без своей
 *             записи op 11/14 — небо 15, блоки 0 (как SKY_FULL|BLK_ZERO); явная запись сильнее; MAX_VALUE — правила нет.
 *             Идёт после op 11 того же столбца; пол у клиента только опускается (поднимать не нужно — см. skyFloor)
 * C2S hoka_odyssey:wc (этап 5, ядро 0.45.0+):
 *   2 VIEW    varint — «Дальность по высоте» из настроек клиента (кубов вверх и вниз); при входе и при смене
 *   3 WANT    (0.51) varint n; n × (long pos, byte что: 1 блоки, 2 свет) — промах кэша: прислать целиком
 *   4 HAVE    (0.51) long mapKey, varint бюджетМБ (0 — кэш выкл), varint n; n × (int cx, int cz, int y0 секция,
 *             varint len, ⌈len/8⌉ Б маска блоков, ⌈len/8⌉ Б маска света) — что лежит в кэше клиента
 *   5 EVICT   (0.51) long mapKey, varint n; n × (int cx, int cz) — столбцы вытеснены из кэша клиента
 *   6 HASH_REPORT (0.51) varint проверено, varint расхождений, varint k, k × long pos
 * </pre>
 * <p>Кэш кубов (0.51, CubeCacheModel): куб, который клиент унёс в кэш с той же версией, шлём ссылкой op 13/op 14
 * вместо op 2/op 11 — те же биты sent/LIT, тот же порядок. Клиент сверяет хэш сам; промах — WANT.</p>
 * Пустые кубы не шлются: в скелете клиент и так считает их воздухом.
 * <p>Шаг 10 (ленивая загрузка, CubeStore): шлём только READY-кубы; не загруженные в окне — просим загрузить (сначала
 * куб игрока и 26 соседей), ставший READY куб досылаем ({@link #drainReady}). Окно op 5 клиенту — только сплошь
 * загруженная полоса по Y вокруг игрока в 3×3 столбцах ({@link #safeWindow}): за ней клиент не двигается
 * (CubeFreezeMixin) — игрок не проваливается в ещё не пришедшую землю. Протокол тот же (op 2/4/5).</p>
 */
public final class CubeStreamer {

    private CubeStreamer() {}

    /** Окно по высоте по умолчанию: ±V секций от секции игрока (дальше — по дальности прорисовки игрока, {@link #vertical}). */
    static final int V = 10;

    /** Предел дальности по высоте на сервере (кубов вверх и вниз): -Dhoka.cubes.vertical-max, по умолчанию 32. */
    public static final int VERTICAL_MAX = Math.max(4, Integer.getInteger("hoka.cubes.vertical-max", 32));
    /** Ядро с настройкой «Дальность по высоте» (op VIEW). */
    static final int[] VIEW_CORE = {0, 45, 0};
    static final String CHANNEL_C2S = "hoka_odyssey:wc";
    /** Что попросил клиент (op 2 VIEW); живёт до выхода — и при смене карты. */
    private static final Map<UUID, Integer> REQUESTED = new ConcurrentHashMap<>();

    /**
     * Дальность по высоте: ядро 0.45+ — его настройка (до прихода VIEW — 10), не меньше 4 и не больше предела сервера.
     * Старое ядро — как окно его Sodium (SodiumTallMixin до 0.45): ±max(8, дальность)+1, не больше 24.
     */
    static int vertical(Player p) {
        Integer req = REQUESTED.get(p.getUuid());
        if (req != null) return Math.min(VERTICAL_MAX, Math.max(4, req));
        if (Handshake.coreAtLeast(p, VIEW_CORE)) return Math.min(VERTICAL_MAX, V);
        int d = p.getSettings().viewDistance();
        return Math.min(VERTICAL_MAX, Math.min(24, Math.max(8, d) + 1));
    }
    static final int PER_PACKET = 24;
    static final String CHANNEL = "hoka_odyssey:w";

    private static final class State {
        int sy = Integer.MIN_VALUE;
        int v = V;
        /** Окно op 5, отправленное клиенту (на ленивой карте — уже потокового, см. safeWindow). */
        int wa = Integer.MIN_VALUE, wb = Integer.MIN_VALUE;
        /** Счётчик тиков для heal. */
        int heal;
        Instance instance;
        // столбец → какие секции (индексы) отправлены. Пишут поток тика игрока (PlayerChunkLoad/Unload) и планировщик,
        // читают выгрузка (Maps, CubeStore) и свет — конкурентная карта.
        final Map<Long, BitSet> sent = new ConcurrentHashMap<>();
        /** Позиции последних действий игрока (ставил/ломал/копал/жал) — какие кубы ресинкать, если действие отменят. */
        final List<net.minestom.server.coordinate.Point> touched = new ArrayList<>();
        /** Чистка очереди Minestom (purgeQueue): размер и центр обзора после прошлой чистки, буферы. */
        int qSize = -1;
        long qCenter;
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet qSeen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        long[] qBuf = new long[0];
        /** op 16 (0.54): пол «неба по умолчанию», отправленный клиенту, по столбцам (секция Y). Нет — клиент правила не знает. */
        final Map<Long, Integer> floors = new ConcurrentHashMap<>();
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("cubes");
    /** Отладка: сколько кубов отправлено / выгружено. */
    static volatile long sentCubes, unloadedCubes;

    public static void register() {
        var ev = MinecraftServer.getGlobalEventHandler();
        ev.addListener(PlayerChunkLoadEvent.class, e -> {
            Player p = e.getPlayer();
            Instance inst = p.getInstance();
            if (!TallWorld.isCubes(inst)) return;
            State st = state(p);
            if (st.sy == Integer.MIN_VALUE) { st.sy = p.getPosition().blockY() >> 4; st.v = vertical(p); window(p, st); }
            // Minestom не вынимает столбец из своей очереди отправки, когда тот ушёл из обзора: скелет уходит уже после
            // выгрузки (PlayerChunkUnloadEvent был раньше). Клиент его выбрасывает («not in the view range») или держит
            // призраком, а у нас он остался бы в sent навсегда (выгрузки больше не будет). Не учитывать и велеть забыть.
            if (!inView(p, e.getChunkX(), e.getChunkZ())) {
                staleLoads++;
                st.sent.remove(key(e.getChunkX(), e.getChunkZ()));
                st.floors.remove(key(e.getChunkX(), e.getChunkZ()));
                p.sendPacket(new UnloadChunkPacket(e.getChunkX(), e.getChunkZ()));
                return;
            }
            Chunk c = inst.getChunk(e.getChunkX(), e.getChunkZ());
            if (c instanceof TallColumn tc) {
                // сюда приходит только настоящий скелет столбца (он стирает у игрока все кубы столбца) — окно заново.
                // Пересылку от отмены действия (refresh) перехватывает sendChunk столбца → resync одного куба.
                BitSet mask = new BitSet();
                BitSet old = st.sent.put(key(e.getChunkX(), e.getChunkZ()), mask);
                st.floors.remove(key(e.getChunkX(), e.getChunkZ()));       // op 16 — заново после света столбца
                if (old != null) {
                    cacheDrop(p, tc, old);                                  // повторный скелет: старые кубы клиент унёс в кэш
                    // слои движка клиента могли остаться — «последняя запись не всё 15» помнить и дальше
                    for (int i = old.nextSetBit(NDEF); i >= 0; i = old.nextSetBit(i + 1)) mask.set(i);
                }
                try { update(p, st, tc, mask); }
                catch (RuntimeException ex) { failed("отправка столбца " + tc.getChunkX() + " " + tc.getChunkZ(), ex); }   // недосланное дошлёт heal
                // ленивая карта: столбец пришёл скелетом (воздух) — окно сузить сразу, тем же пакетом, а не в след. тике
                if (CubeStore.lazy(inst)) window(p, st);
            }
        });
        ev.addListener(PlayerChunkUnloadEvent.class, e -> {
            State st = STATES.get(e.getPlayer().getUuid());
            if (st == null) return;
            BitSet old = st.sent.remove(key(e.getChunkX(), e.getChunkZ()));
            st.floors.remove(key(e.getChunkX(), e.getChunkZ()));           // клиент забыл столбец (Forget) — и его пол
            // кэш 0.51: клиент забывает столбец и уносит его кубы в кэш — запомнить их версии
            if (old != null && st.instance != null && st.instance.getChunk(e.getChunkX(), e.getChunkZ()) instanceof TallColumn tc)
                cacheDrop(e.getPlayer(), tc, old);
        });
        // правило записи (§4.2): действие игрока в куб, которого у него нет (не в sent), отменяем и ресинкаем этот куб.
        // Установка: Minestom сам пересылает столбец (BlockPlacementListener.refresh → sendChunk столбца → resync).
        // Ломание: при отмене Minestom шлёт только ack (клиент откатывает свой прогноз), столбец пересылает лишь при
        // ломании воздуха. Копание и нажатие он не пересылает — ресинк сразу.
        ev.addListener(net.minestom.server.event.player.PlayerBlockPlaceEvent.class, e -> {
            Player p = e.getPlayer();
            if (!TallWorld.isCubes(p.getInstance())) return;
            var pos = e.getBlockPosition();
            touch(p, pos, pos.add(0, 1, 0));                              // дверь/кровать могут залезть в куб выше
            if (!sentCube(p, pos)) { e.setCancelled(true); cancelled++; }
        });
        ev.addListener(net.minestom.server.event.player.PlayerBlockBreakEvent.class, e -> {
            Player p = e.getPlayer();
            if (!TallWorld.isCubes(p.getInstance())) return;
            touch(p, e.getBlockPosition());
            if (!sentCube(p, e.getBlockPosition())) { e.setCancelled(true); cancelled++; }
        });
        ev.addListener(net.minestom.server.event.player.PlayerStartDiggingEvent.class, e -> {
            Player p = e.getPlayer();
            if (!TallWorld.isCubes(p.getInstance()) || sentCube(p, e.getBlockPosition())) return;
            e.setCancelled(true);
            cancelled++;
            touch(p, e.getBlockPosition());
            if (p.getInstance().getChunkAt(e.getBlockPosition()) instanceof TallColumn tc) resync(p, tc);
        });
        ev.addListener(net.minestom.server.event.player.PlayerBlockInteractEvent.class, e -> {
            Player p = e.getPlayer();
            if (!TallWorld.isCubes(p.getInstance())) return;
            var pos = e.getBlockPosition();
            var to = pos.add(e.getBlockFace().toDirection().vec());       // куда клиент мог поставить блок «на глаз»
            touch(p, pos, to);                                            // и в окне: refresh до PlayerBlockPlaceEvent (упёрлись в сущность…) — ресинк только этих кубов
            if (sentCube(p, pos)) return;
            e.setCancelled(true);
            e.setBlockingItemUse(true);
            cancelled++;
            if (p.getInstance().getChunkAt(pos) instanceof TallColumn tc) resync(p, tc);
            if (p.getInstance().getChunkAt(to) instanceof TallColumn tc2 && (tc2.getChunkX() != (pos.blockX() >> 4) || tc2.getChunkZ() != (pos.blockZ() >> 4)))
                resync(p, tc2);
        });
        // Новый мир — всё заново, но НЕ по PlayerSpawnEvent: Minestom.spawnPlayer шлёт первую пачку столбцов
        // (sendPendingChunks) ДО этого события, и сброс стирал их из sent — ближние к точке появления столбцы (ромб/«крест»
        // по манхэттенской очереди) оставались без кубов. Смену мира ловит state() по st.instance.
        // Возрождение в том же мире: клиент забыл все столбцы, Minestom пришлёт их заново — sent и окно op 5 заново.
        // sy тоже заново: иначе на не-ленивой карте окно op 5 после возрождения не перешлётся (tick шлёт его только при смене sy).
        ev.addListener(net.minestom.server.event.player.PlayerRespawnEvent.class, e -> {
            State old = STATES.remove(e.getPlayer().getUuid());
            if (old != null) cacheDropAll(e.getPlayer(), old);             // клиент забыл все столбцы — они в его кэше
        });
        // Вход в другой мир: AddEntityToInstanceEvent идёт в Entity.setInstance ДО sendPendingChunks (в отличие от
        // PlayerSpawnEvent). Нужен и при A→B→A: на некубовой карте state() не зовётся, st.instance остаётся A, а при смене
        // измерения Minestom не шлёт PlayerChunkUnloadEvent — без сброса в sent висели бы столбцы первого визита
        // (watched() держал бы их в памяти навсегда), а окно op 5 не перешлось бы.
        ev.addListener(net.minestom.server.event.instance.AddEntityToInstanceEvent.class, e -> {
            if (!(e.getEntity() instanceof Player p)) return;
            State old = STATES.remove(p.getUuid());
            if (old != null) cacheDropAll(p, old);                         // столбцы прежней карты клиент унёс в кэш
            // кэш 0.51: ключ новой карты — до первого скелета (sendPendingChunks идёт после этого события)
            try { sendCacheKey(p, e.getInstance()); } catch (RuntimeException ex) { failed("op 12 игроку " + p.getUsername(), ex); }
        });
        ev.addListener(PlayerDisconnectEvent.class, e -> { STATES.remove(e.getPlayer().getUuid()); REQUESTED.remove(e.getPlayer().getUuid()); HEADINGS.remove(e.getPlayer().getUuid()); CubeCacheModel.remove(e.getPlayer().getUuid()); });
        // этап 5: объявить канал wc новому ядру — оно пришлёт VIEW (смена окна — в tick, как при смене секции)
        ev.addListener(PlayerSpawnEvent.class, e -> {
            if (e.isFirstSpawn() && Handshake.coreAtLeast(e.getPlayer(), VIEW_CORE))
                e.getPlayer().sendPluginMessage("minecraft:register", CHANNEL_C2S.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        ev.addListener(net.minestom.server.event.player.PlayerPluginMessageEvent.class, e -> {
            if (!e.getIdentifier().equals(CHANNEL_C2S)) return;
            Player p = e.getPlayer();
            if (!Handshake.coreAtLeast(p, VIEW_CORE)) return;
            try {
                byte[] m = e.getMessage();
                NetworkBuffer b = NetworkBuffer.wrap(m, 0, m.length);
                int op = b.read(NetworkBuffer.BYTE);
                if (op >= 3 && op <= 6) { if (Handshake.coreAtLeast(p, CubeCacheModel.CORE)) cacheMessage(p, op, b); return; }
                if (op != 2) return;                                           // незнакомый op — пропуск
                int v = b.read(NetworkBuffer.VAR_INT);
                REQUESTED.put(p.getUuid(), Math.max(4, Math.min(64, v)));
            } catch (Exception ex) {
                LOG.warn("{}: битый hoka_odyssey:wc ({})", p.getUsername(), ex.toString());
            }
        });
        MinecraftServer.getSchedulerManager().buildTask(CubeStreamer::tick)
                .repeat(net.minestom.server.timer.TaskSchedule.tick(1)).schedule();
    }

    private static State state(Player p) {
        State st = STATES.computeIfAbsent(p.getUuid(), u -> new State());
        if (st.instance != p.getInstance()) { st.sent.clear(); st.floors.clear(); st.sy = Integer.MIN_VALUE; st.wa = st.wb = Integer.MIN_VALUE; st.instance = p.getInstance(); }
        return st;
    }

    /** Каждый тик: дослать ставшие READY кубы; игрок сменил секцию по высоте — сдвинуть окно во всех видимых столбцах. */
    private static void tick() {
        drainReady();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Instance inst = p.getInstance();
            boolean cubes = TallWorld.isCubes(inst);
            try { heading(p, cubes); } catch (RuntimeException ex) { failed("ход игрока " + p.getUsername(), ex); }
            if (!cubes) continue;
            State st = STATES.get(p.getUuid());
            if (st == null || st.instance != inst) continue;
            try {
                wants(p, st);                                               // кэш 0.51: промахи клиента — кубы/свет целиком
                purgeQueue(p, st);                                          // устаревшие/повторные записи очереди Minestom — вон
                synchronized (st.touched) { st.touched.clear(); }          // не отменённые действия — ресинк не нужен
                int sy = p.getPosition().blockY() >> 4;
                if (sy == st.sy && vertical(p) == st.v) {
                    // раз в секунду: недосланное и незапрошенное — дослать/запросить; столбцы вплотную к игроку — 4 раза
                    // в секунду (дыра под ногами после телепорта закрывается за ≤0,25 с, а не ≤1 с)
                    if (++st.heal % 20 == 0) heal(p, st, inst, Integer.MAX_VALUE);
                    else if (st.heal % 5 == 0) heal(p, st, inst, 2);
                    if (CubeStore.lazy(inst)) window(p, st);                // загрузились кубы — окно клиента шире
                    continue;
                }
                st.sy = sy;
                st.v = vertical(p);
                window(p, st);
                // S2: свет всех столбцов — общими пакетами по 128 кубов (было: пакет на столбец, ~4,5 тыс. за переход)
                LightBatch lb = new LightBatch(p);
                BATCH.set(lb);
                try {
                    for (var en : st.sent.entrySet()) {
                        long k = en.getKey();
                        Chunk c = inst.getChunk((int) (k >> 32), (int) k);
                        // сбой одного столбца не должен оставить остальные без сдвига окна (их кубы у клиента «пропадут»)
                        if (c instanceof TallColumn tc) {
                            try { update(p, st, tc, en.getValue()); }
                            catch (RuntimeException ex) { failed("окно столбца " + tc.getChunkX() + " " + tc.getChunkZ(), ex); }
                        }
                    }
                } finally {
                    BATCH.remove();
                    lb.flush();
                }
            } catch (RuntimeException ex) {
                failed("тик игрока " + p.getUsername(), ex);
            }
        }
    }

    /** Отладка: сбоев отправки (дошлёт heal), устаревших скелетов из очереди Minestom (вне обзора — не учтены). */
    static volatile long sendFailures, staleLoads, healResent;
    private static volatile long lastFailLog;

    /** Сбой отправки: в лог (не чаще раза в 10 с, со стеком), счётчик; недосланное дошлёт heal — биты LIT ставятся после отправки. */
    private static void failed(String what, RuntimeException ex) {
        sendFailures++;
        long now = System.currentTimeMillis();
        if (now - lastFailLog < 10_000) return;
        lastFailLog = now;
        LOG.error("кубы: сбой ({}) — дошлю при подстраховке; всего сбоев {}", what, sendFailures, ex);
    }

    /** Центр обзора игрока у Minestom (Player.chunksLoadedByClient): по нему добавляются/убираются столбцы. */
    private static final java.lang.invoke.VarHandle VIEW_CENTER = viewCenterHandle();

    private static java.lang.invoke.VarHandle viewCenterHandle() {
        try {
            return java.lang.invoke.MethodHandles.privateLookupIn(Player.class, java.lang.invoke.MethodHandles.lookup())
                    .findVarHandle(Player.class, "chunksLoadedByClient", net.minestom.server.coordinate.Vec.class);
        } catch (Throwable t) {
            LOG.warn("нет доступа к Player.chunksLoadedByClient ({}) — обзор по позиции игрока", t.toString());
            return null;
        }
    }

    /** Столбец в обзоре игрока по Minestom (Чебышёв от центра обзора ≤ effectiveViewDistance). */
    static boolean inView(Player p, int cx, int cz) {
        int ccx, ccz, slack = 0;
        Object v = null;
        if (VIEW_CENTER != null) { try { v = VIEW_CENTER.get(p); } catch (Throwable ignored) {} }
        if (v instanceof net.minestom.server.coordinate.Vec c) { ccx = (int) c.x(); ccz = (int) c.z(); }
        else { ccx = p.getPosition().blockX() >> 4; ccz = p.getPosition().blockZ() >> 4; slack = 1; }   // позиция могла уйти вперёд центра
        return Math.max(Math.abs(cx - ccx), Math.abs(cz - ccz)) <= p.effectiveViewDistance() + slack;
    }

    /**
     * Кому столбец отправлен (он у игрока в sent): только им можно слать кубы, свет и правки блоков — все потребители
     * (drainReady, relight, relightCubes, sentCube) без sent всё равно ничего не шлют. Без Chunk.getViewers(): в
     * Minestom каждый его вызов (size/iterator/isEmpty) заново обходит 65×65 столбцов трекера сущностей — на 4–12 тыс.
     * столбцов CubeStore.scan и Maps.reapColumns стояли тиком по 1,3–3,8 с каждые 5–7 с (JFR srv48: 77% планировщика).
     * Кольцо 33 (его нет в getViewers — CHUNK_VIEW_DISTANCE 32) здесь есть: оно в sent.
     */
    public static List<Player> receivers(TallColumn c) {
        Instance inst = c.getInstance();
        List<Player> r = new ArrayList<>(1);
        if (inst == null || STATES.isEmpty()) return r;
        long k = key(c.getChunkX(), c.getChunkZ());
        for (Player p : inst.getPlayers()) {
            State st = STATES.get(p.getUuid());
            if (st != null && st.instance == inst && st.sent.containsKey(k)) r.add(p);
        }
        return r;
    }

    /**
     * Замена getViewers() столбца на кубовой карте: игроки, у кого столбец отправлен (sent), в обзоре Minestom
     * (inView: в т.ч. ещё ждёт в очереди отправки) или в зрительской дальности от позиции (как трекер Minestom:
     * Чебышёв ≤ CHUNK_VIEW_DISTANCE). O(игроков), без обхода трекера.
     */
    public static List<Player> seers(TallColumn c) {
        Instance inst = c.getInstance();
        List<Player> r = new ArrayList<>(1);
        if (inst == null) return r;
        int cx = c.getChunkX(), cz = c.getChunkZ();
        long k = key(cx, cz);
        for (Player p : inst.getPlayers()) {
            if (nearPos(p, cx, cz) || inView(p, cx, cz)) { r.add(p); continue; }
            State st = STATES.get(p.getUuid());
            if (st != null && st.instance == inst && st.sent.containsKey(k)) r.add(p);
        }
        return r;
    }

    /** Как зрители столбца у трекера Minestom: Чебышёв от столбца игрока ≤ CHUNK_VIEW_DISTANCE. */
    private static boolean nearPos(Player p, int cx, int cz) {
        var pos = p.getPosition();
        return Math.max(Math.abs(cx - (pos.blockX() >> 4)), Math.abs(cz - (pos.blockZ() >> 4))) <= net.minestom.server.ServerFlag.CHUNK_VIEW_DISTANCE;
    }

    /**
     * Столбец выгружать нельзя: он кому-то отправлен (sent), или он в обзоре Minestom у кого-то — в т.ч. ещё стоит в
     * очереди отправки (chunkQueue). Player.sendPendingChunks молча пропускает незагруженный столбец (без пакета, без
     * PlayerChunkLoadEvent, без повтора), и до выхода из обзора и возврата Minestom его больше не добавит: выгрузка
     * столбца из очереди = дыра у клиента навсегда (чёрная полоса в 1 столбец по кольцу 33, сервер не пускает внутрь).
     * Без getViewers() (см. receivers).
     */
    public static boolean watched(TallColumn c) {
        Instance inst = c.getInstance();
        if (inst == null) return false;
        int cx = c.getChunkX(), cz = c.getChunkZ();
        long k = key(cx, cz);
        for (Player p : inst.getPlayers()) {
            if (inView(p, cx, cz) || nearPos(p, cx, cz)) return true;
            State st = STATES.get(p.getUuid());
            if (st != null && st.instance == inst && st.sent.containsKey(k)) return true;
        }
        return false;
    }

    // ───────────── очередь отправки столбцов Minestom ─────────────

    /** Player.chunkQueue (LongArrayPriorityQueue) и её замок; поля очереди fastutil — для чистки без O(n²) dequeue. */
    private static final java.lang.invoke.VarHandle CHUNK_QUEUE, CHUNK_QUEUE_LOCK, PQ_ARRAY, PQ_SIZE, PQ_CMP;
    static {
        java.lang.invoke.VarHandle q = null, l = null, a = null, s = null, cmp = null;
        try {
            var lk = java.lang.invoke.MethodHandles.privateLookupIn(Player.class, java.lang.invoke.MethodHandles.lookup());
            q = lk.findVarHandle(Player.class, "chunkQueue", it.unimi.dsi.fastutil.longs.LongPriorityQueue.class);
            l = lk.findVarHandle(Player.class, "chunkQueueLock", java.util.concurrent.locks.ReentrantLock.class);
            var pq = java.lang.invoke.MethodHandles.privateLookupIn(it.unimi.dsi.fastutil.longs.LongArrayPriorityQueue.class, java.lang.invoke.MethodHandles.lookup());
            a = pq.findVarHandle(it.unimi.dsi.fastutil.longs.LongArrayPriorityQueue.class, "array", long[].class);
            s = pq.findVarHandle(it.unimi.dsi.fastutil.longs.LongArrayPriorityQueue.class, "size", int.class);
            try { cmp = pq.findVarHandle(it.unimi.dsi.fastutil.longs.LongArrayPriorityQueue.class, "c", it.unimi.dsi.fastutil.longs.LongComparator.class); }
            catch (Throwable t) { LOG.warn("нет доступа к компаратору очереди столбцов ({}) — без приоритета по направлению", t.toString()); }
        } catch (Throwable t) {
            LOG.warn("нет доступа к очереди столбцов Minestom ({}) — устаревшие записи очереди не чистятся", t.toString());
            q = l = a = s = cmp = null;
        }
        CHUNK_QUEUE = q; CHUNK_QUEUE_LOCK = l; PQ_ARRAY = a; PQ_SIZE = s; PQ_CMP = cmp;
    }

    // ───────────── приоритет по направлению полёта ─────────────

    /** С какой скорости (блоков/с, по горизонтали) очередь столбцов смещается вперёд по ходу: -Dhoka.cubes.ahead-speed. */
    static final float AHEAD_SPEED = Float.parseFloat(System.getProperty("hoka.cubes.ahead-speed", "8"));
    /** Вес «вперёд»: оценка столбца = манхэттен − K·(проекция на ход); позади — на столько же дальше. -Dhoka.cubes.ahead-k. */
    static final float AHEAD_K = Float.parseFloat(System.getProperty("hoka.cubes.ahead-k", "0.5"));

    /** Ход игрока для очереди: центр обзора Minestom и сглаженная скорость. Пишет наш тик, читает тик игрока (компаратор). */
    static final class Heading {
        volatile int cx, cz;
        /** Единичный вектор хода по горизонтали; active — летит быстрее AHEAD_SPEED на кубовой карте. */
        volatile float ux, uz;
        volatile boolean active;
        // только наш тик
        double px = Double.NaN, pz;
        long pt;
        float vx, vz;
    }

    private static final Map<UUID, Heading> HEADINGS = new ConcurrentHashMap<>();
    /** Отладка: сколько тиков очередь шла «вперёд по ходу». */
    static volatile long aheadTicks;

    /**
     * Порядок очереди столбцов Minestom (манхэттен от центра обзора), а в быстром полёте — со сдвигом вперёд по ходу:
     * при отставании отправки (64 столбца/тик) сначала то, куда летим, а не боковины за спиной. Ближние (≤2) — всегда
     * первыми. LongArrayPriorityQueue не куча: минимум ищется перебором при каждом dequeue, смена порядка безопасна.
     */
    private static final class AheadComparator implements it.unimi.dsi.fastutil.longs.LongComparator {
        final it.unimi.dsi.fastutil.longs.LongComparator orig;
        final Heading h;

        AheadComparator(it.unimi.dsi.fastutil.longs.LongComparator orig, Heading h) { this.orig = orig; this.h = h; }

        @Override
        public int compare(long a, long b) {
            Heading h = this.h;
            if (!h.active) return orig.compare(a, b);
            int cx = h.cx, cz = h.cz;
            float ux = h.ux, uz = h.uz;
            return Float.compare(score(a, cx, cz, ux, uz), score(b, cx, cz, ux, uz));
        }

        private static float score(long idx, int cx, int cz, float ux, float uz) {
            int dx = net.minestom.server.coordinate.CoordConversion.chunkIndexGetX(idx) - cx;
            int dz = net.minestom.server.coordinate.CoordConversion.chunkIndexGetZ(idx) - cz;
            int m = Math.abs(dx) + Math.abs(dz);
            if (m <= 2) return m - 1000f;                                    // под ногами — всегда первыми
            return m - AHEAD_K * (dx * ux + dz * uz);
        }
    }

    /** В нашем тике: скорость игрока и центр обзора; на кубовой карте — поставить компаратор в его очередь. */
    private static void heading(Player p, boolean cubes) {
        Heading h = HEADINGS.computeIfAbsent(p.getUuid(), u -> new Heading());
        if (!cubes || PQ_CMP == null || CHUNK_QUEUE == null || queueBroken || headingBroken) { h.active = false; h.px = Double.NaN; return; }
        var pos = p.getPosition();
        long now = System.nanoTime();
        if (Double.isNaN(h.px)) { h.px = pos.x(); h.pz = pos.z(); h.pt = now; }
        else {
            double dx = pos.x() - h.px, dz = pos.z() - h.pz;
            float dt = (now - h.pt) / 1e9f;
            if (dt >= 0.04f) {
                if (dx * dx + dz * dz > 64 * 64) { h.vx = h.vz = 0; }       // телепорт — не полёт
                else {
                    float a = Math.min(1f, dt * 4f);                         // сглаживание ~0,25 с
                    h.vx += a * ((float) (dx / dt) - h.vx);
                    h.vz += a * ((float) (dz / dt) - h.vz);
                }
                h.px = pos.x(); h.pz = pos.z(); h.pt = now;
            }
        }
        float sp = (float) Math.sqrt(h.vx * h.vx + h.vz * h.vz);
        long c = viewCenterKey(p);
        h.cx = (int) (c >> 32);
        h.cz = (int) c;
        if (sp >= AHEAD_SPEED) { h.ux = h.vx / sp; h.uz = h.vz / sp; h.active = true; aheadTicks++; }
        else h.active = false;
        try {
            var q = CHUNK_QUEUE.get(p);
            if (!(q instanceof it.unimi.dsi.fastutil.longs.LongArrayPriorityQueue aq)) return;
            var lock = (java.util.concurrent.locks.ReentrantLock) CHUNK_QUEUE_LOCK.get(p);
            lock.lock();
            try {
                Object cur = PQ_CMP.get(aq);
                if (!(cur instanceof AheadComparator ac) || ac.h != h) {
                    if (!(cur instanceof it.unimi.dsi.fastutil.longs.LongComparator lc)) return;   // естественный порядок — не наш случай
                    PQ_CMP.set(aq, new AheadComparator(cur instanceof AheadComparator old ? old.orig : lc, h));
                }
                if (aq.size() > 0) aq.changed();                             // закэшированный минимум — по новому порядку
            } finally { lock.unlock(); }
        } catch (RuntimeException ex) {
            h.active = false;
            headingBroken = true;                                           // поля не те — больше не трогать
            LOG.error("приоритет очереди столбцов по направлению выключен: сбой", ex);
        }
    }

    private static volatile boolean headingBroken;

    /** Отладка: записей очереди Minestom убрано — вне обзора / повторы. */
    static volatile long queueStale, queueDup;
    private static volatile boolean queueBroken;

    /**
     * Убрать из очереди отправки столбцов Minestom записи вне обзора игрока и повторы. Minestom кладёт столбец в
     * очередь при каждом входе в обзор и не вынимает при выходе: после телепорта/полёта там тысячи устаревших записей
     * (лог клиента: 8253 «Ignoring chunk … not in the view range», один столбец до 5 раз). Каждая такая запись тратит
     * пачку, которую клиент разрешил (targetChunksPerTick), — нужные столбцы ждут десятки секунд, а клиент разбирает
     * скелеты по 1876 секций впустую. Запись вне обзора Minestom всё равно не должен слать (мы бы тут же велели забыть),
     * повтор пересылает скелет и стирает у клиента пришедшие кубы. В тике, под замком очереди.
     */
    private static void purgeQueue(Player p, State st) {
        if (CHUNK_QUEUE == null || VIEW_CENTER == null || queueBroken) return;   // без точного центра обзора не чистить
        try {
            var q = (it.unimi.dsi.fastutil.longs.LongPriorityQueue) CHUNK_QUEUE.get(p);
            if (!(q instanceof it.unimi.dsi.fastutil.longs.LongArrayPriorityQueue aq)) { queueBroken = true; LOG.warn("очередь столбцов Minestom не LongArrayPriorityQueue ({}) — чистка выключена", q == null ? "null" : q.getClass().getName()); return; }
            var lock = (java.util.concurrent.locks.ReentrantLock) CHUNK_QUEUE_LOCK.get(p);
            lock.lock();
            try {
                int n = aq.size();
                if (n == 0 || n == st.qSize && st.qCenter == viewCenterKey(p)) { st.qSize = n; return; }
                long[] arr = (long[]) PQ_ARRAY.get(aq);
                int size = (int) PQ_SIZE.get(aq);
                if (size != n || arr.length < n) { queueBroken = true; LOG.warn("очередь столбцов Minestom: size {} ≠ {} — чистка выключена", size, n); return; }
                it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = st.qSeen;
                seen.clear();
                if (st.qBuf.length < n) st.qBuf = new long[Math.max(n, st.qBuf.length * 2)];
                long[] keep = st.qBuf;
                int m = 0, stale = 0, dup = 0;
                for (int i = 0; i < n; i++) {
                    long idx = arr[i];
                    int x = net.minestom.server.coordinate.CoordConversion.chunkIndexGetX(idx), z = net.minestom.server.coordinate.CoordConversion.chunkIndexGetZ(idx);
                    if (!inView(p, x, z)) { stale++; continue; }
                    if (!seen.add(idx)) { dup++; continue; }
                    keep[m++] = idx;
                }
                if (m != n) {
                    aq.clear();
                    for (int i = 0; i < m; i++) aq.enqueue(keep[i]);
                    queueStale += stale;
                    queueDup += dup;
                }
                st.qSize = aq.size();
                st.qCenter = viewCenterKey(p);
            } finally { lock.unlock(); }
        } catch (RuntimeException ex) {
            queueBroken = true;                                             // поля не те (другая версия Minestom) — не трогать больше
            LOG.error("чистка очереди столбцов Minestom выключена: сбой", ex);
        }
    }

    /** Центр обзора Minestom ключом (для «изменился ли»); нет доступа — по позиции. */
    private static long viewCenterKey(Player p) {
        Object v = null;
        if (VIEW_CENTER != null) { try { v = VIEW_CENTER.get(p); } catch (Throwable ignored) {} }
        if (v instanceof net.minestom.server.coordinate.Vec c) return key((int) c.x(), (int) c.z());
        return key(p.getPosition().blockX() >> 4, p.getPosition().blockZ() >> 4);
    }

    /**
     * Подстраховка ленивой карты: куб окна игрока, которого нет в памяти (выгрузили раньше, чем игрок подошёл; запрос
     * потерялся), никто больше не попросит — окно op 5 обрезается на нём (игрок «упирается»), у игрока дыра. Запросить.
     */
    private static void heal(Player p, State st, Instance inst, int radius) {
        boolean lazy = CubeStore.lazy(inst);
        int pcx = p.getPosition().blockX() >> 4, pcz = p.getPosition().blockZ() >> 4;
        for (var en : st.sent.entrySet()) {
            long k = en.getKey();
            int kx = (int) (k >> 32), kz = (int) k;
            if (radius != Integer.MAX_VALUE && Math.max(Math.abs(kx - pcx), Math.abs(kz - pcz)) > radius) continue;
            if (!(inst.getChunk(kx, kz) instanceof TallColumn c)) continue;
            BitSet mask = en.getValue();
            int min = c.getMinSection(), n = c.getSections().size();
            int from = Math.max(0, st.sy - st.v - min), to = Math.min(n - 1, st.sy + st.v - min);
            boolean miss = false, unsent = false;
            for (int i = from; i <= to; i++) {
                if (!c.ready(i)) { if (lazy) { c.request(i, prio(p, c.getChunkX(), i + min, c.getChunkZ())); miss = true; } }
                else if (!mask.get(LIT + i)) unsent = true;                // в памяти, а игроку не ушёл (сбой/гонка) — дослать
            }
            if (miss) healed++;
            if (unsent) {
                healResent++;
                try { update(p, st, c, mask); }
                catch (RuntimeException ex) { failed("подстраховка столбца " + c.getChunkX() + " " + c.getChunkZ(), ex); }
            }
        }
    }

    /** Отладка: сколько раз подстраховка нашла незапрошенные кубы окна. */
    static volatile long healed;

    /** Окно op 5 клиенту (шлётся только при изменении). */
    private static void window(Player p, State st) {
        int a = st.sy - st.v, b = st.sy + st.v;
        if (CubeStore.lazy(p.getInstance())) { long w = safeWindow(p, st); a = (int) (w >> 32); b = (int) w; }
        if (a == st.wa && b == st.wb) return;
        st.wa = a;
        st.wb = b;
        final int fa = a, fb = b;
        byte[] buf0 = NetworkBuffer.makeArray(buf -> {
            buf.write(NetworkBuffer.BYTE, (byte) 5);
            buf.write(NetworkBuffer.INT, fa);
            buf.write(NetworkBuffer.INT, fb);
        });
        p.sendPacket(new PluginMessagePacket(CHANNEL, buf0));
    }

    /**
     * Ленивая карта: окно для клиента — сплошная полоса секций по Y вокруг игрока, где в 3×3 столбцах вокруг все кубы
     * READY (незагруженный столбец не мешает: туда клиент и так не пойдёт). Куб игрока ещё не загружен — пустое окно
     * (от > до): клиент стоит. Ответ: от << 32 | до.
     */
    static long safeWindow(Player p, State st) {
        Instance inst = p.getInstance();
        int pcx = p.getPosition().blockX() >> 4, pcz = p.getPosition().blockZ() >> 4;
        int lo = st.sy - st.v, hi = st.sy + st.v;
        if (!layerReady(inst, pcx, pcz, st.sy)) return ((long) (st.sy + 1) << 32) | (st.sy & 0xFFFFFFFFL);
        int a = st.sy, b = st.sy;
        while (a - 1 >= lo && layerReady(inst, pcx, pcz, a - 1)) a--;
        while (b + 1 <= hi && layerReady(inst, pcx, pcz, b + 1)) b++;
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    private static boolean layerReady(Instance inst, int pcx, int pcz, int sy) {
        int i = sy - (TallWorld.MIN_Y >> 4);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (inst.getChunk(pcx + dx, pcz + dz) instanceof TallColumn tc && !tc.ready(i)) return false;
        return true;
    }

    /** Приоритет загрузки куба для игрока: расстояние по Чебышёву в кубах (0–1 — его куб и 26 соседей). */
    private static int prio(Player p, int cx, int cy, int cz) {
        var pos = p.getPosition();
        return Math.max(Math.max(Math.abs(cx - (pos.blockX() >> 4)), Math.abs(cz - (pos.blockZ() >> 4))), Math.abs(cy - (pos.blockY() >> 4)));
    }

    /** Кубы, ставшие READY (CubeStore): дослать тем, у кого столбец отправлен и куб в окне (+ свет). В тике. */
    private static void drainReady() {
        Map<CubeColumn, List<Integer>> by = null;
        for (CubeStore.Ready r; (r = CubeStore.pollReady()) != null; ) {
            if (by == null) by = new HashMap<>();
            by.computeIfAbsent(r.col(), k -> new ArrayList<>()).add(r.i());
        }
        if (by == null) return;
        for (var e : by.entrySet()) {
            CubeColumn c = e.getKey();
            if (!c.isLoaded() || !TallWorld.isCubes(c.getInstance())) continue;
            List<Section> secs = c.getSections();
            int min = c.getMinSection();
            boolean sun = c.getInstance().getCachedDimensionType().hasSkylight();
            for (Player p : receivers(c)) {                                // и кольцо 33 (его нет в getViewers)
                State st = STATES.get(p.getUuid());
                if (st == null || st.sy == Integer.MIN_VALUE || st.instance != c.getInstance()) continue;
                BitSet mask = st.sent.get(key(c.getChunkX(), c.getChunkZ()));
                if (mask == null) continue;
                try {
                    int from = Math.max(0, st.sy - st.v - min), to = Math.min(secs.size() - 1, st.sy + st.v - min);
                    List<Integer> add = new ArrayList<>(), lit = new ArrayList<>();
                    for (int i : e.getValue()) {
                        if (i < from || i > to || mask.get(LIT + i) || lit.contains(i) || !c.ready(i)) continue;   // успели выгрузить — не слать
                        lit.add(i);
                        if (secs.get(i).blockPalette().count() > 0) add.add(i);
                    }
                    if (lit.isEmpty()) continue;
                    if (!add.isEmpty()) {
                        writeCubes(p, c, add, true);
                        for (int i : add) mask.set(i);
                        sentCubes += add.size();
                    }
                    int[] fl = skyFloor(p, st, c, sun);
                    sendLight(p, c, skipSky(p, c, mask, lightNeeded(c, lit, add, mask, sun), fl[0]), sun);
                    sendFloor(p, st, c, fl[1]);                             // op 16 — после op 11 столбца
                    for (int i : lit) mask.set(LIT + i);                   // после света: сбой — heal дошлёт и куб, и свет
                } catch (RuntimeException ex) {
                    failed("досылка кубов " + c.getChunkX() + " " + c.getChunkZ() + " игроку " + p.getUsername(), ex);
                }
            }
        }
    }

    /** Отладка: пустых кубов без неба и без света блоков, которым свет op 11 не слали (клиент и так считает 0). */
    static volatile long lightSkipped;

    /**
     * Каким кубам из lit слать свет. С небом — всем (пустому нужен свет неба). Без неба (свет кубами) пустой куб без
     * света блоков — не слать: у клиента там и так «неба нет, блоки 0», а каждый лишний op 11 у него — пометка 27
     * секций на перестройку (s49/f49: света 971 тыс. кубов на 541 тыс. отправленных). Как update() без неба, только
     * пустой куб рядом с факелом свет получает. Бит LIT ставится всем lit, как раньше: смена света потом придёт
     * через relightCubes.
     */
    private static List<Integer> lightNeeded(TallColumn c, List<Integer> lit, List<Integer> add, BitSet mask, boolean sun) {
        if (sun || !CubeLight.active(c)) return lit;
        List<Integer> out = new ArrayList<>(lit.size());
        for (int i : lit) {
            if (add.contains(i) || mask.get(i) || CubeLight.blockLit((CubeColumn) c, i)) out.add(i);
            else lightSkipped++;
        }
        return out;
    }

    /** Блок в окне игрока (на кубовой карте): ставить/ломать вне окна нельзя — у игрока там куба нет. */
    public static boolean inWindow(Player p, int blockY) {
        State st = STATES.get(p.getUuid());
        if (st == null || st.sy == Integer.MIN_VALUE) return false;
        int sy = blockY >> 4;
        return sy >= st.sy - st.v && sy <= st.sy + st.v;
    }

    /** Куб с блоком (x, y, z) у игрока есть: столбец отправлен и куб в его окне (непустой отправлен или пустой со светом). */
    static boolean sentCube(Player p, net.minestom.server.coordinate.Point pos) {
        State st = STATES.get(p.getUuid());
        if (st == null || st.sy == Integer.MIN_VALUE || st.instance != p.getInstance()) return false;
        BitSet m = st.sent.get(key(pos.blockX() >> 4, pos.blockZ() >> 4));
        if (m == null) return false;
        int i = (pos.blockY() >> 4) - (TallWorld.MIN_Y >> 4);
        return i >= 0 && i < LIT && (m.get(i) || m.get(LIT + i));
    }

    /** Запас по Y к окну игрока при выгрузке кубов (шаг 11): у края окна кубы не «мигают» туда-обратно. */
    static final int UNLOAD_MARGIN = 2;

    /**
     * Шаг 11, в тике (CubeStore.scan): кубы столбца, нужные его зрителям, — окно ±(v + запас) и всё, что им
     * отправлено (sent). false — у зрителя ещё нет окна (только вошёл): столбец сейчас не трогать.
     */
    static boolean interest(TallColumn c, BitSet keep) {
        Instance inst = c.getInstance();
        if (inst == null) return true;
        int min = c.getMinSection(), n = c.getSections().size();
        int cx = c.getChunkX(), cz = c.getChunkZ();
        long k = key(cx, cz);
        // кому отправлен (и кольцо 33) — и у кого он в обзоре, но ещё ждёт в очереди Minestom: кубы окна понадобятся,
        // как только скелет уйдёт (иначе выгрузили бы и тут же грузили заново)
        for (Player p : inst.getPlayers()) {
            State st = STATES.get(p.getUuid());
            boolean sent = st != null && st.instance == inst && st.sent.containsKey(k);
            if (!sent && !inView(p, cx, cz)) continue;
            if (st == null || st.sy == Integer.MIN_VALUE || st.instance != inst) return false;
            int from = Math.max(0, st.sy - st.v - UNLOAD_MARGIN - min), to = Math.min(n - 1, st.sy + st.v + UNLOAD_MARGIN - min);
            if (from <= to) keep.set(from, to + 1);
            BitSet m = st.sent.get(k);
            if (m == null) continue;
            for (int i = m.nextSetBit(0); i >= 0 && i < NDEF; i = m.nextSetBit(i + 1)) keep.set(i >= LIT ? i - LIT : i);
        }
        return true;
    }

    /** Ники игроков, у которых куб i столбца есть (для /hocubes dump). */
    static List<String> cubeViewers(TallColumn c, int i) {
        List<String> r = new ArrayList<>();
        long k = key(c.getChunkX(), c.getChunkZ());
        for (Player p : receivers(c)) {
            State st = STATES.get(p.getUuid());
            BitSet m = st == null || st.instance != c.getInstance() ? null : st.sent.get(k);
            if (m != null && (m.get(i) || m.get(LIT + i))) r.add(p.getUsername());
        }
        return r;
    }

    private static void touch(Player p, net.minestom.server.coordinate.Point... pos) {
        State st = STATES.get(p.getUuid());
        if (st == null) return;
        synchronized (st.touched) {
            if (st.touched.size() > 64) st.touched.clear();
            java.util.Collections.addAll(st.touched, pos);
        }
    }

    /** Отладка: действий отменено правилом записи, кубов пересинхронизировано. */
    static volatile long cancelled, resyncCubes;

    /**
     * Ресинк столбца игроку без скелета (§4.2): только кубы, которых касались последние действия (op 2 CUBES + свет
     * их секций; куб вне окна — op 4 UNLOAD, у клиента он снова «не загружен»). Ничего не касались — запасной путь:
     * все кубы окна в этом столбце. false — столбец игроку ещё не отправлен (пусть идёт обычный скелет).
     */
    static boolean resync(Player p, TallColumn c) {
        State st = STATES.get(p.getUuid());
        if (st == null || st.sy == Integer.MIN_VALUE || st.instance != c.getInstance()) return false;
        BitSet mask = st.sent.get(key(c.getChunkX(), c.getChunkZ()));
        if (mask == null) return false;
        int min = c.getMinSection(), n = c.getSections().size();
        java.util.TreeSet<Integer> idx = new java.util.TreeSet<>();
        synchronized (st.touched) {
            for (var it = st.touched.iterator(); it.hasNext(); ) {
                var pt = it.next();
                if (pt.blockX() >> 4 != c.getChunkX() || pt.blockZ() >> 4 != c.getChunkZ()) continue;
                it.remove();
                int i = (pt.blockY() >> 4) - min;
                if (i >= 0 && i < n) idx.add(i);
            }
        }
        if (idx.isEmpty()) for (int i = mask.nextSetBit(LIT); i >= 0 && i < NDEF; i = mask.nextSetBit(i + 1)) idx.add(i - LIT);
        List<Integer> in = new ArrayList<>();
        List<Long> out = new ArrayList<>();
        for (int i : idx) {
            if (mask.get(i) || mask.get(LIT + i)) in.add(i);
            else out.add(pos(c.getChunkX(), i + min, c.getChunkZ()));
        }
        if (!out.isEmpty()) {
            byte[] b = NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, (byte) 4);
                buf.write(NetworkBuffer.VAR_INT, out.size());
                for (long l : out) buf.write(NetworkBuffer.LONG, l);
            });
            p.sendPacket(new PluginMessagePacket(CHANNEL, b));
        }
        if (!in.isEmpty()) {
            writeCubes(p, c, in, false);
            List<Section> secs = c.getSections();
            for (int i : in) {                                            // бит «непустой отправлен» — как есть сейчас (вне окна снимет update)
                if (secs.get(i).blockPalette().count() > 0) mask.set(i); else mask.clear(i);
            }
            boolean sun = c.getInstance().getCachedDimensionType().hasSkylight();
            if (lightOp(p)) { writeLight(p, c, full(c, in, sun), in, sun, false); resyncCubes += in.size() + out.size(); return true; }
            LightData l;
            if (CubeLight.active(c)) l = CubeLight.data((CubeColumn) c, in, true);   // шаг 12: свет кубами
            else {
                l = slice(c, c.light(), in, sun);
                if (sun) l = withFull(l, in);
            }
            if (l != null) { p.sendPacket(new UpdateLightPacket(c.getChunkX(), c.getChunkZ(), l)); lightBytes += bytes(l); }
        }
        resyncCubes += in.size() + out.size();
        return true;
    }

    /** Окно столбца: дослать непустые кубы окна, выгрузить отправленные вне окна. */
    private static void update(Player p, State st, TallColumn c, BitSet mask) {
        List<Section> secs = c.getSections();
        int min = c.getMinSection();
        int from = Math.max(0, st.sy - st.v - min), to = Math.min(secs.size() - 1, st.sy + st.v - min);
        // готовность снимаем ОДИН раз: куб, ставший READY в рабочем потоке посреди update, иначе получал бит LIT (и свет),
        // не попав в add, — drainReady его пропускал (LIT уже стоит), у игрока дыра до смены секции по Y
        BitSet rdy = new BitSet();
        List<Integer> add = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            if (!c.ready(i)) { c.request(i, prio(p, c.getChunkX(), i + min, c.getChunkZ())); continue; }   // придёт через drainReady
            rdy.set(i);
            if (!mask.get(i) && secs.get(i).blockPalette().count() > 0) add.add(i);
        }
        // свет неба нужен и пустым кубам окна (пещера под крышей тёмная); биты LIT+i — куб окна уже со светом.
        // Без неба — ещё отправленные кубы без света (прошлая отправка сорвалась после блоков).
        boolean sun = c.getInstance().getCachedDimensionType().hasSkylight();
        List<Integer> lit = new ArrayList<>(add);
        for (int i = rdy.nextSetBit(0); i >= 0; i = rdy.nextSetBit(i + 1))
            if (!mask.get(LIT + i) && !add.contains(i) && (sun || mask.get(i))) lit.add(i);
        lit.sort(null);
        mask.clear(LIT, LIT + from);
        mask.clear(LIT + to + 1, LIT + secs.size());
        for (int i = from; i <= to; i++) if (!rdy.get(i)) mask.clear(LIT + i);   // не загружен (или выгружен) — у игрока его нет (правило записи)
        List<Long> drop = new ArrayList<>();
        BitSet dropIdx = new BitSet();
        for (int i = mask.nextSetBit(0); i >= 0 && i < LIT; i = mask.nextSetBit(i + 1))
            if (i < from || i > to) { drop.add(pos(c.getChunkX(), i + min, c.getChunkZ())); dropIdx.set(i); }
        mask.clear(0, from);
        if (to + 1 < secs.size()) mask.clear(to + 1, secs.size());
        if (!drop.isEmpty()) {
            cacheDrop(p, c, dropIdx);                                       // кэш 0.51: клиент унесёт их в кэш
            unloadedCubes += drop.size();
            byte[] b = NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, (byte) 4);
                buf.write(NetworkBuffer.VAR_INT, drop.size());
                for (long l : drop) buf.write(NetworkBuffer.LONG, l);
            });
            p.sendPacket(new PluginMessagePacket(CHANNEL, b));
        }
        if (!add.isEmpty()) {
            writeCubes(p, c, add, true);
            for (int i : add) mask.set(i);
            sentCubes += add.size();
        }
        int[] fl = skyFloor(p, st, c, sun);
        // S2: столбец с непустыми кубами — свет сразу за op 2 (клиент берёт его заранее, lightAhead: без второй сборки);
        // в общую пачку — только свет пустых кубов. op 16 — в пачке, после всего света (и этого столбца).
        LightBatch lb = add.isEmpty() ? null : BATCH.get();
        if (lb != null) BATCH.remove();
        try { sendLight(p, c, skipSky(p, c, mask, lit, fl[0]), sun); }   // 0.54: небо по умолчанию — без записей
        finally { if (lb != null) BATCH.set(lb); }
        sendFloor(p, st, c, fl[1]);                                         // op 16 — после op 11 столбца
        // LIT — только после отправки: сбой выше оставит кубы без LIT, и heal их дошлёт (раньше биты ставились до
        // света, и после исключения в CubeLight куб считался отправленным навсегда)
        for (int i = rdy.nextSetBit(0); i >= 0; i = rdy.nextSetBit(i + 1)) mask.set(LIT + i);
    }

    /**
     * Кубы столбца пакетами op 2 CUBES (пустой куб — тоже: 0 блоков, у клиента станет воздухом). allowRef (кэш 0.51) —
     * куб, который у клиента в кэше той же версии (CubeCacheModel), — ссылкой op 13: хэш считается здесь заново по
     * содержимому, под тем же замком чтения, что и снимок op 2.
     */
    private static void writeCubes(Player p, TallColumn c, List<Integer> add, boolean allowRef) {
        // снимок под замком чтения столбца: запись (setBlock под замком записи, сохранение) не порвёт палитру/entries
        // посреди сериализации. Только чтение — тик не ждёт ничего, кроме короткого setBlock; слать — уже без замка.
        List<byte[]> out = new ArrayList<>();
        CubeCacheModel.P m = cacheModel(p, c);
        c.lockReadLock();
        try {
            if (m == null) encodeCubes(c, add, out);
            else {
                long key = CacheKeys.of(c.getInstance());
                int cx = c.getChunkX(), cz = c.getChunkZ();
                List<Section> secs = c.getSections();
                List<Integer> full = new ArrayList<>(add.size()), refs = new ArrayList<>();
                List<Long> hs = new ArrayList<>();
                for (int i : add) {
                    Section sec = secs.get(i);
                    long h = allowRef && sec.blockPalette().singleValue() < 0 && CubeCacheModel.hasBlocks(m, key, cx, cz, i) ? c.cubeHashFresh(i) : 0;
                    if (h != 0 && CubeCacheModel.refBlocks(m, key, cx, cz, i, h)) {
                        refs.add(i);
                        hs.add(h);
                        CubeCacheModel.refCubes++;
                        CubeCacheModel.refBytesSaved += 4096L * sec.blockPalette().bitsPerEntry() / 8 + 24;
                    } else full.add(i);
                    CubeCacheModel.sentBlocks(m, key, cx, cz, i);          // из кэша клиента он вынут (ссылкой или op 2)
                }
                if (!full.isEmpty()) encodeCubes(c, full, out);
                if (!refs.isEmpty()) encodeRefs(c, refs, hs, out);
            }
        } finally { c.unlockReadLock(); }
        for (byte[] b : out) p.sendPacket(new PluginMessagePacket(CHANNEL, b));
    }

    /** Блоки с данными столбца по кубам (только с типом блок-энтити — их клиент создаёт). Под замком чтения. */
    private static Map<Integer, List<Map.Entry<Integer, net.minestom.server.instance.block.Block>>> blockEntities(TallColumn c) {
        int min = c.getMinSection();
        Map<Integer, List<Map.Entry<Integer, net.minestom.server.instance.block.Block>>> bes = new HashMap<>();
        for (var e : c.dataBlocks().entrySet()) {
            if (e.getValue().registry().blockEntityType() == null) continue;
            int y = net.minestom.server.coordinate.CoordConversion.chunkBlockIndexGetY(e.getKey());
            bes.computeIfAbsent((y >> 4) - min, k -> new ArrayList<>()).add(e);
        }
        return bes;
    }

    private static void writeBlockEntities(NetworkBuffer buf, List<Map.Entry<Integer, net.minestom.server.instance.block.Block>> list) {
        buf.write(NetworkBuffer.VAR_INT, list.size());
        for (var e : list) {
            int k = e.getKey();
            var blk = e.getValue();
            int bx = net.minestom.server.coordinate.CoordConversion.chunkBlockIndexGetX(k) & 15;
            int bz = net.minestom.server.coordinate.CoordConversion.chunkBlockIndexGetZ(k) & 15;
            buf.write(NetworkBuffer.BYTE, (byte) (bx << 4 | bz));
            buf.write(NetworkBuffer.SHORT, (short) net.minestom.server.coordinate.CoordConversion.chunkBlockIndexGetY(k));
            buf.write(net.minestom.server.instance.block.BlockEntityType.NETWORK_TYPE, blk.registry().blockEntityType());
            buf.write(NetworkBuffer.NBT, net.minestom.server.utils.block.BlockUtils.extractClientNbt(blk));
        }
    }

    private static void encodeCubes(TallColumn c, List<Integer> add, List<byte[]> out) {
        List<Section> secs = c.getSections();
        int min = c.getMinSection();
        var biomeSer = Palette.biomeSerializer(MinecraftServer.getBiomeRegistry().size());
        var bes = blockEntities(c);
        for (int s = 0; s < add.size(); s += PER_PACKET) {
            List<Integer> part = add.subList(s, Math.min(add.size(), s + PER_PACKET));
            byte[] b = NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, (byte) 2);
                buf.write(NetworkBuffer.VAR_INT, part.size());
                for (int i : part) {
                    Section sec = secs.get(i);
                    buf.write(NetworkBuffer.LONG, pos(c.getChunkX(), i + min, c.getChunkZ()));
                    buf.write(NetworkBuffer.SHORT, (short) sec.blockPalette().count());
                    buf.write(Palette.BLOCK_SERIALIZER, sec.blockPalette());
                    buf.write(biomeSer, sec.biomePalette());
                    writeBlockEntities(buf, bes.getOrDefault(i, List.of()));
                }
            });
            out.add(b);
        }
    }

    /** S2C op 13 CUBES_REF: кубов на пакет. */
    static final int REF_PER_PACKET = 128;

    /** op 13: pos, версия H, блок-энтити (как в op 2) — блоки и биомы клиент берёт из своего кэша. */
    static void encodeRefs(TallColumn c, List<Integer> idx, List<Long> hs, List<byte[]> out) {
        int min = c.getMinSection();
        var bes = blockEntities(c);
        for (int s = 0; s < idx.size(); s += REF_PER_PACKET) {
            final int from = s, to = Math.min(idx.size(), s + REF_PER_PACKET);
            out.add(NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, (byte) 13);
                buf.write(NetworkBuffer.VAR_INT, to - from);
                for (int k = from; k < to; k++) {
                    int i = idx.get(k);
                    buf.write(NetworkBuffer.LONG, pos(c.getChunkX(), i + min, c.getChunkZ()));
                    buf.write(NetworkBuffer.LONG, hs.get(k));
                    writeBlockEntities(buf, bes.getOrDefault(i, List.of()));
                }
            }));
        }
    }

    /** Блоки столбца сменились: свет всех кубов окна игрока заново (и пустых — под новой/снятой крышей). */
    static void relight(Player p, TallColumn c) { relight(p, c, null); }

    /**
     * Свет окна игрока после правок столбца. before — свет до правок: тогда шлём только кубы, где он стал другим
     * (поставили блок — обычно 1–3 куба, а не всё окно ~100 КБ). null — всё окно, как раньше.
     */
    static void relight(Player p, TallColumn c, LightData before) {
        State st = STATES.get(p.getUuid());
        if (st == null || st.sy == Integer.MIN_VALUE || !st.sent.containsKey(key(c.getChunkX(), c.getChunkZ()))) return;
        int min = c.getMinSection(), n = c.getSections().size();
        List<Integer> idx = new ArrayList<>();
        for (int i = Math.max(0, st.sy - st.v - min); i <= Math.min(n - 1, st.sy + st.v - min); i++) idx.add(i);
        boolean sun = c.getInstance().getCachedDimensionType().hasSkylight();
        if (lightOp(p)) {                                              // op 11: изменившиеся кубы целиком (небо и блоки)
            LightData l = full(c, idx, sun);
            LightData o = before == null ? null : (sun ? withFull(slice(c, before, idx, sun), idx) : slice(c, before, idx, sun));
            List<Integer> ch = before == null ? idx : LightOp.changed(o == null ? new LightData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of()) : o, l, idx);
            writeLight(p, c, l, ch, sun, true);
            return;
        }
        LightData l = slice(c, c.light(), idx, sun);
        if (sun) l = withFull(l, idx);                                 // пустым с полным небом — явный «весь 15», иначе останется старая тень
        if (l != null && before != null) {
            LightData o = slice(c, before, idx, sun);
            if (sun) o = withFull(o, idx);
            l = diff(o, l);
        }
        if (l != null) { p.sendPacket(new UpdateLightPacket(c.getChunkX(), c.getChunkZ(), l)); lightBytes += bytes(l); }
    }

    /**
     * Шаг 12 (CubeLight): свет кубов столбца сменился — игроку только те, что у него есть со светом (биты LIT),
     * «всё 15» — явным массивом, пропавший свет блоков — битом «пусто».
     */
    static void relightCubes(Player p, CubeColumn c, BitSet changed) {
        State st = STATES.get(p.getUuid());
        if (st == null || st.sy == Integer.MIN_VALUE || st.instance != c.getInstance()) return;
        BitSet mask = st.sent.get(key(c.getChunkX(), c.getChunkZ()));
        if (mask == null) return;
        List<Integer> idx = new ArrayList<>();
        for (int i = changed.nextSetBit(0); i >= 0 && i < LIT; i = changed.nextSetBit(i + 1)) if (mask.get(LIT + i)) idx.add(i);
        if (idx.isEmpty()) return;
        if (lightOp(p)) { writeLight(p, c, CubeLight.data(c, idx, true), idx, c.getInstance().getCachedDimensionType().hasSkylight(), true); return; }
        LightData l = CubeLight.data(c, idx, true);
        if (l != null) { p.sendPacket(new UpdateLightPacket(c.getChunkX(), c.getChunkZ(), l)); lightBytes += bytes(l); }
    }

    /** Сколько байт света разослано (для /hocubes stats). */
    static volatile long lightBytes;

    private static long bytes(LightData l) { return 2048L * (l.skyLight().size() + l.blockLight().size()); }

    /**
     * Только то, что изменилось: кубы, у которых массив неба/блоков другой. Свет блоков пропал совсем (убрали факел) —
     * бит «пусто» (emptyBlockMask), иначе у игрока останется старое свечение. null — ничего не изменилось.
     */
    private static LightData diff(LightData o, LightData l) {
        java.util.Map<Integer, byte[]> os = arrays(o == null ? null : o.skyMask(), o == null ? null : o.skyLight());
        java.util.Map<Integer, byte[]> ob = arrays(o == null ? null : o.blockMask(), o == null ? null : o.blockLight());
        java.util.Map<Integer, byte[]> ns = arrays(l.skyMask(), l.skyLight()), nb = arrays(l.blockMask(), l.blockLight());
        BitSet sky = new BitSet(), block = new BitSet(), es = new BitSet(), eb = new BitSet();
        List<byte[]> skyArr = new ArrayList<>(), blockArr = new ArrayList<>();
        for (var e : new java.util.TreeMap<>(ns).entrySet())
            if (!java.util.Arrays.equals(os.get(e.getKey()), e.getValue())) { sky.set(e.getKey()); skyArr.add(e.getValue()); }
        for (var e : new java.util.TreeMap<>(nb).entrySet())
            if (!java.util.Arrays.equals(ob.get(e.getKey()), e.getValue())) { block.set(e.getKey()); blockArr.add(e.getValue()); }
        for (int b : ob.keySet()) if (!nb.containsKey(b)) eb.set(b);           // свет блоков исчез
        for (int b : os.keySet()) if (!ns.containsKey(b)) es.set(b);           // (небо без массива при солнце не бывает — withFull)
        if (l.emptySkyMask() != null) { BitSet x = (BitSet) l.emptySkyMask().clone(); es.or(x); }
        if (l.emptyBlockMask() != null) { BitSet x = (BitSet) l.emptyBlockMask().clone(); eb.or(x); }
        es.andNot(sky);
        eb.andNot(block);
        if (sky.isEmpty() && block.isEmpty() && es.isEmpty() && eb.isEmpty()) return null;
        return new LightData(sky, block, es, eb, skyArr, blockArr);
    }

    private static java.util.Map<Integer, byte[]> arrays(BitSet mask, List<byte[]> arr) {
        java.util.Map<Integer, byte[]> m = new java.util.HashMap<>();
        if (mask == null) return m;
        int n = 0;
        for (int b = mask.nextSetBit(0); b >= 0; b = mask.nextSetBit(b + 1), n++) m.put(b, arr.get(n));
        return m;
    }

    /** Куб без массива неба в окне — пометить «весь свет 15» массивом из 0xFF (крышу сняли — тень должна уйти). */
    private static LightData withFull(LightData l, List<Integer> idx) {
        BitSet sky = l == null ? new BitSet() : (BitSet) l.skyMask().clone();
        List<byte[]> arr = l == null ? new ArrayList<>() : new ArrayList<>(l.skyLight());
        byte[] full = new byte[2048];
        java.util.Arrays.fill(full, (byte) 0xFF);
        for (int i : idx) {
            if (sky.get(i + 1)) continue;
            int pos = 0;
            for (int b = sky.nextSetBit(0); b >= 0 && b < i + 1; b = sky.nextSetBit(b + 1)) pos++;
            sky.set(i + 1);
            arr.add(pos, full);
        }
        return new LightData(sky, l == null ? new BitSet() : l.blockMask(), l == null ? new BitSet() : l.emptySkyMask(),
                l == null ? new BitSet() : l.emptyBlockMask(), arr, l == null ? List.of() : l.blockLight());
    }

    private static void sendLight(Player p, TallColumn c, List<Integer> idx, boolean sun) {
        if (idx.isEmpty()) return;
        if (lightOp(p)) { writeLight(p, c, full(c, idx, sun), idx, sun, true); return; }
        LightData l = CubeLight.active(c) ? CubeLight.data((CubeColumn) c, idx, false) : slice(c, c.light(), idx, sun);
        if (l != null) p.sendPacket(new UpdateLightPacket(c.getChunkX(), c.getChunkZ(), l));
    }

    /** Шаг 14: ядро 0.44.0+ берёт свет op 11 (LightOp), остальные — ванильный UpdateLightPacket. */
    static boolean lightOp(Player p) { return Handshake.coreAtLeast(p, LightOp.CORE); }

    /** Свет кубов idx «явно» (каждому кубу — небо; без массива блоков — 0). */
    private static LightData full(TallColumn c, List<Integer> idx, boolean sun) {
        if (CubeLight.active(c)) return CubeLight.data((CubeColumn) c, idx, true);
        LightData l = slice(c, c.light(), idx, sun);
        return sun ? withFull(l, idx) : l;
    }

    /**
     * op 11 LIGHT: каждый куб idx — полная запись (небо и блоки). Кэш 0.51 (allowRef): запись длиннее LightOp.REF_MIN,
     * чья L совпала с последней у клиента (CubeCacheModel), — ссылкой op 14.
     */
    private static void writeLight(Player p, TallColumn c, LightData l, List<Integer> idx, boolean sun, boolean allowRef) {
        if (idx.isEmpty()) return;
        List<LightOp.Rec> recs = LightOp.records(c.getChunkX(), c.getChunkZ(), c.getMinSection(), l, idx, sun);
        if (floorOp(p)) {                                                   // op 16: какая запись у клиента последней
            State st = STATES.get(p.getUuid());
            BitSet mask = st == null || st.instance != c.getInstance() ? null : st.sent.get(key(c.getChunkX(), c.getChunkZ()));
            if (mask != null) for (LightOp.Rec r : recs) mask.set(NDEF + r.i(), !LightOp.isSkyFull(r));
        }
        LightBatch batch = BATCH.get();
        if (batch != null && batch.p != p) batch = null;
        CubeCacheModel.P m = cacheModel(p, c);
        if (m == null) {
            if (batch != null) { batch.full.addAll(recs); return; }
            for (byte[] b : LightOp.pack(recs)) p.sendPacket(new PluginMessagePacket(CHANNEL, b));
            return;
        }
        long key = CacheKeys.of(c.getInstance());
        int cx = c.getChunkX(), cz = c.getChunkZ();
        List<LightOp.Rec> full = new ArrayList<>(recs.size()), refs = new ArrayList<>();
        for (LightOp.Rec r : recs) {
            if (allowRef && r.bytes().length > LightOp.REF_MIN && CubeCacheModel.refLight(m, key, cx, cz, r.i(), r.light())) {
                refs.add(r);
                CubeCacheModel.refLight++;
                CubeCacheModel.refLightSaved += r.bytes().length - 16;
            } else full.add(r);
            // короткие записи ссылкой не ходят — в модели их не держим (иначе окно ±32 съело бы её предел)
            if (r.bytes().length > LightOp.REF_MIN) CubeCacheModel.sentLight(m, key, cx, cz, r.i(), r.light());
            else CubeCacheModel.forgetLight(m, key, cx, cz, r.i());
        }
        if (batch != null) { batch.full.addAll(full); batch.refs.addAll(refs); return; }
        if (!full.isEmpty()) for (byte[] b : LightOp.pack(full)) p.sendPacket(new PluginMessagePacket(CHANNEL, b));
        if (!refs.isEmpty()) for (byte[] b : LightOp.packRefs(refs)) p.sendPacket(new PluginMessagePacket(CHANNEL, b));
    }

    // ───────────── S2: свет многих столбцов общими пакетами ─────────────

    /** Пачка op 11/14/16 одного игрока (смена секции по Y): в tick, шлётся в конце прохода по столбцам. */
    private static final class LightBatch {
        final Player p;
        final List<LightOp.Rec> full = new ArrayList<>(), refs = new ArrayList<>();
        final List<int[]> floors = new ArrayList<>();

        LightBatch(Player p) { this.p = p; }

        /** Порядок как у одиночной отправки: op 11, op 14, затем op 16 (пол — после света своих столбцов). */
        void flush() {
            if (!full.isEmpty()) for (byte[] b : LightOp.pack(full)) p.sendPacket(new PluginMessagePacket(CHANNEL, b));
            if (!refs.isEmpty()) for (byte[] b : LightOp.packRefs(refs)) p.sendPacket(new PluginMessagePacket(CHANNEL, b));
            if (!floors.isEmpty()) writeFloors(p, floors);
        }
    }

    /** Пачка текущего потока (только tick при смене секции); null — слать сразу. */
    private static final ThreadLocal<LightBatch> BATCH = new ThreadLocal<>();

    // ───────────── op 16 SKY_FLOOR (0.54): небо по умолчанию ─────────────

    /** С какой версии ядро знает op 16. */
    static final int[] FLOOR_CORE = {0, 54, 0};
    static final byte OP_FLOOR = 16;
    /** -Dhoka.light.floor=false — op 16 выключен (всем свет как ядру 0.53). */
    static final boolean FLOOR_ON = !"false".equalsIgnoreCase(System.getProperty("hoka.light.floor"));
    /** -Dhoka.light.floorcheck=true — отладка: свет пропущенных кубов всё же считать и сверять с «всё 15». */
    static final boolean FLOOR_CHECK = Boolean.getBoolean("hoka.light.floorcheck");
    /** Отладка: кубов без записи (небо по умолчанию), полов отправлено, расхождений floorcheck. */
    static volatile long skyDefault, floorsSent, floorMismatch;
    private static volatile long lastMismatchLog;
    private static final int[] NO_FLOOR = {Integer.MAX_VALUE, Integer.MIN_VALUE};

    static boolean floorOp(Player p) { return FLOOR_ON && Handshake.coreAtLeast(p, FLOOR_CORE); }

    /**
     * op 16 для столбца: [порог пропуска — индекс куба (MAX — не пропускать), пол для отправки (MIN — не слать)].
     * Порог — свежий пол сервера (CubeLight.skyFloor): кубы не ниже него сейчас «всё 15». Пол клиента только
     * опускаем: куб, пропущенный под прежним полом, был «всё 15», а смена его света потом приходит явной записью
     * (relightCubes) — поднимать пол не нужно. Порог = max(пол клиента, свежий) = свежий.
     */
    private static int[] skyFloor(Player p, State st, TallColumn c, boolean sun) {
        if (!sun || !(c instanceof CubeColumn cc) || !CubeLight.active(c) || !floorOp(p)) return NO_FLOOR;
        int now = CubeLight.skyFloor(cc);
        if (now == Integer.MAX_VALUE) return NO_FLOOR;
        Integer old = st.floors.get(key(c.getChunkX(), c.getChunkZ()));
        int thr = Math.max(0, now - c.getMinSection());
        return new int[]{thr, old == null || now < old ? now : Integer.MIN_VALUE};
    }

    /** Кубы lit без записей у ядра 0.54: не ниже порога и последняя запись у клиента «всё 15» (нет бита NDEF). */
    private static List<Integer> skipSky(Player p, TallColumn c, BitSet mask, List<Integer> lit, int thr) {
        if (thr == Integer.MAX_VALUE || lit.isEmpty()) return lit;
        List<Integer> out = new ArrayList<>(lit.size()), skipped = new ArrayList<>();
        List<Section> secs = c.getSections();
        for (int i : lit) {
            // непустой куб — всегда явно: кэш floorInfo мог не успеть за кубом, ставшим READY в рабочем потоке
            if (i >= thr && !mask.get(NDEF + i) && !mask.get(i) && secs.get(i).blockPalette().count() == 0) skipped.add(i);
            else out.add(i);
        }
        if (skipped.isEmpty()) return lit;
        if (FLOOR_CHECK) {                                                  // отладка: свет всё же посчитать и сверить
            var recs = LightOp.records(c.getChunkX(), c.getChunkZ(), c.getMinSection(), CubeLight.data((CubeColumn) c, skipped, true), skipped, true);
            List<Integer> bad = new ArrayList<>();
            for (LightOp.Rec r : recs) if (!LightOp.isSkyFull(r)) bad.add(r.i());
            if (!bad.isEmpty()) {
                floorMismatch += bad.size();
                long now = System.currentTimeMillis();
                if (now - lastMismatchLog >= 5_000) {
                    lastMismatchLog = now;
                    LOG.warn("op 16: столбец {} {} — кубы {} выше пола {} не «всё 15» (шлю явно); всего расхождений {}", c.getChunkX(), c.getChunkZ(),
                            bad.stream().map(i -> i + c.getMinSection()).toList(), thr + c.getMinSection(), floorMismatch);
                }
                skipped.removeAll(bad);
                out.addAll(bad);
                out.sort(null);
            }
        }
        // кэш 0.51: как у короткой записи «всё 15» — свет куба в модели клиента не держим
        CubeCacheModel.P m = cacheModel(p, c);
        if (m != null) {
            long key = CacheKeys.of(c.getInstance());
            for (int i : skipped) CubeCacheModel.forgetLight(m, key, c.getChunkX(), c.getChunkZ(), i);
        }
        skyDefault += skipped.size();
        return out;
    }

    /** op 16 столбца (floor = MIN — не слать); запоминает пол клиента. После op 11 этого столбца. */
    private static void sendFloor(Player p, State st, TallColumn c, int floor) {
        if (floor == Integer.MIN_VALUE) return;
        st.floors.put(key(c.getChunkX(), c.getChunkZ()), floor);
        int[] e = {c.getChunkX(), c.getChunkZ(), floor};
        LightBatch b = BATCH.get();
        if (b != null && b.p == p) { b.floors.add(e); return; }
        writeFloors(p, List.of(e));
    }

    private static void writeFloors(Player p, List<int[]> fl) {
        for (int s = 0; s < fl.size(); s += 1024) {
            List<int[]> part = fl.subList(s, Math.min(fl.size(), s + 1024));
            p.sendPacket(new PluginMessagePacket(CHANNEL, NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, OP_FLOOR);
                buf.write(NetworkBuffer.VAR_INT, part.size());
                for (int[] e : part) { buf.write(NetworkBuffer.INT, e[0]); buf.write(NetworkBuffer.INT, e[1]); buf.write(NetworkBuffer.INT, e[2]); }
            })));
        }
        floorsSent += fl.size();
    }

    // ───────────── кэш кубов клиента (0.51) ─────────────

    /** Модель кэша игрока, если ссылки ему можно слать для этого столбца (ядро 0.51+, HAVE с бюджетом, кубовая карта, не верфь). */
    private static CubeCacheModel.P cacheModel(Player p, TallColumn c) {
        if (!CubeCacheModel.ENABLED || c.getChunkX() * 16 >= TallChunk.YARD_X0) return null;
        CubeCacheModel.P m = CubeCacheModel.active(p.getUuid());
        if (m == null || !Handshake.coreAtLeast(p, CubeCacheModel.CORE) || CacheKeys.of(c.getInstance()) == 0) return null;
        return m;
    }

    /** op 12 CACHE: ключ карты (0 — кэш на ней выключен). Ядру 0.51+, при входе в инстанс (до первого скелета). */
    static void sendCacheKey(Player p, Instance inst) {
        if (!Handshake.coreAtLeast(p, CubeCacheModel.CORE)) return;
        long key = CacheKeys.of(inst);
        p.sendPacket(new PluginMessagePacket(CHANNEL, NetworkBuffer.makeArray(buf -> {
            buf.write(NetworkBuffer.BYTE, (byte) 12);
            buf.write(NetworkBuffer.LONG, key);
            buf.write(NetworkBuffer.BYTE, (byte) 1);
        })));
    }

    /**
     * Кубы mask (биты &lt; LIT — непустые, отправленные) ушли от клиента в его кэш: запомнить их версии. Однородные
     * (одно значение блоков) ссылкой не шлются — op 2 у них и так ~15 Б. Под замком чтения столбца.
     */
    private static void cacheDrop(Player p, TallColumn c, BitSet mask) {
        if (mask == null || mask.isEmpty()) return;
        CubeCacheModel.P m = cacheModel(p, c);
        if (m == null) return;
        long key = CacheKeys.of(c.getInstance());
        int cx = c.getChunkX(), cz = c.getChunkZ();
        c.lockReadLock();
        try {
            List<Section> secs = c.getSections();
            for (int i = mask.nextSetBit(0); i >= 0 && i < LIT && i < secs.size(); i = mask.nextSetBit(i + 1)) {
                if (!c.inMemory(i) || secs.get(i).blockPalette().singleValue() >= 0) continue;
                CubeCacheModel.cachedBlocks(m, key, cx, cz, i, c.cubeHash(i));
            }
        } catch (RuntimeException ex) {
            failed("кэш: версии столбца " + cx + " " + cz, ex);
        } finally { c.unlockReadLock(); }
    }

    /** Все столбцы прежнего состояния игрока ушли в его кэш (смена карты, возрождение). */
    private static void cacheDropAll(Player p, State st) {
        if (st.instance == null || CubeCacheModel.active(p.getUuid()) == null) return;
        for (var en : st.sent.entrySet()) {
            long k = en.getKey();
            if (st.instance.getChunk((int) (k >> 32), (int) k) instanceof TallColumn tc) cacheDrop(p, tc, en.getValue());
        }
    }

    /** SectionPos.asLong → X, Y, Z (как pos). */
    static int posX(long l) { return (int) (l >> 42); }
    static int posY(long l) { return (int) (l << 44 >> 44); }
    static int posZ(long l) { return (int) (l << 22 >> 42); }

    /** C2S wc ops 3–6 (поток тика игрока). */
    static void cacheMessage(Player p, int op, NetworkBuffer b) {
        UUID u = p.getUuid();
        switch (op) {
            case 3 -> {                                                     // WANT
                CubeCacheModel.P m = CubeCacheModel.get(u);
                if (m == null) return;
                int n = Math.min(4096, b.read(NetworkBuffer.VAR_INT));
                for (int k = 0; k < n; k++) {
                    long pos = b.read(NetworkBuffer.LONG);
                    int what = b.read(NetworkBuffer.BYTE);
                    if (m.wants.size() < 65536) m.wants.add(new long[]{pos, what});
                }
            }
            case 4 -> {                                                     // HAVE
                long key = b.read(NetworkBuffer.LONG);
                int mb = b.read(NetworkBuffer.VAR_INT);
                CubeCacheModel.P m = CubeCacheModel.getOrCreate(u);
                CubeCacheModel.budget(m, mb);
                int n = Math.min(65536, b.read(NetworkBuffer.VAR_INT));
                int min = TallWorld.MIN_Y >> 4;
                for (int k = 0; k < n; k++) {
                    int cx = b.read(NetworkBuffer.INT), cz = b.read(NetworkBuffer.INT), y0 = b.read(NetworkBuffer.INT);
                    int len = b.read(NetworkBuffer.VAR_INT);
                    if (len < 0 || len > TallWorld.HEIGHT >> 4) throw new IllegalArgumentException("HAVE: длина " + len);
                    byte[] bm = b.read(NetworkBuffer.FixedRawBytes((len + 7) >> 3));
                    byte[] lm = b.read(NetworkBuffer.FixedRawBytes((len + 7) >> 3));
                    if (mb > 0 && key != 0) CubeCacheModel.seed(m, key, cx, cz, y0 - min, bm, lm, len);
                }
            }
            case 5 -> {                                                     // EVICT
                CubeCacheModel.P m = CubeCacheModel.get(u);
                long key = b.read(NetworkBuffer.LONG);
                int n = b.read(NetworkBuffer.VAR_INT);
                for (int k = 0; k < n; k++) {
                    int cx = b.read(NetworkBuffer.INT), cz = b.read(NetworkBuffer.INT);
                    if (m != null) CubeCacheModel.evict(m, key, cx, cz);
                }
            }
            case 6 -> {                                                     // HASH_REPORT
                int checked = b.read(NetworkBuffer.VAR_INT), bad = b.read(NetworkBuffer.VAR_INT), k = Math.min(16, b.read(NetworkBuffer.VAR_INT));
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < k; j++) {
                    long pos = b.read(NetworkBuffer.LONG);
                    sb.append(j == 0 ? " — например " : ", ").append(posX(pos)).append(' ').append(posY(pos)).append(' ').append(posZ(pos));
                }
                CubeCacheModel.hashChecks += checked;
                CubeCacheModel.hashBad += bad;
                String msg = "сверка хэшей кубов: проверено " + checked + ", расхождений " + bad + sb;
                LOG.info("{}: {}", p.getUsername(), msg);
                p.sendMessage(net.kyori.adventure.text.Component.text(msg, bad == 0 ? net.kyori.adventure.text.format.NamedTextColor.GREEN
                        : net.kyori.adventure.text.format.NamedTextColor.RED));
            }
            default -> {}
        }
    }

    /**
     * Тик: WANT клиента (промах кэша) — куб/свет целиком, без ссылки. Столбца нет в sent или бита нет — op 4/Forget уже
     * в пути, клиент ожидание снимет сам; куб не в памяти — снять биты, дошлёт heal/update.
     */
    private static void wants(Player p, State st) {
        CubeCacheModel.P m = CubeCacheModel.get(p.getUuid());
        if (m == null || m.wants.isEmpty() || st.sy == Integer.MIN_VALUE) return;
        Map<Long, List<long[]>> by = new HashMap<>();
        int n = 0;
        for (long[] w; n < 512 && (w = m.wants.poll()) != null; n++)
            by.computeIfAbsent(key(posX(w[0]), posZ(w[0])), k -> new ArrayList<>()).add(w);
        boolean sun = st.instance.getCachedDimensionType().hasSkylight();
        for (var e : by.entrySet()) {
            long k = e.getKey();
            BitSet mask = st.sent.get(k);
            if (mask == null || !(st.instance.getChunk((int) (k >> 32), (int) k) instanceof TallColumn c)) {
                CubeCacheModel.wantIgnored += e.getValue().size();
                continue;
            }
            int min = c.getMinSection(), size = c.getSections().size();
            java.util.TreeSet<Integer> blocks = new java.util.TreeSet<>(), light = new java.util.TreeSet<>();
            for (long[] w : e.getValue()) {
                int i = posY(w[0]) - min;
                if (i < 0 || i >= size) continue;
                if (!c.ready(i)) { mask.clear(i); mask.clear(LIT + i); CubeCacheModel.wantIgnored++; continue; }
                if ((w[1] & 1) != 0) { if (mask.get(i)) { blocks.add(i); CubeCacheModel.wantBlocks++; } else CubeCacheModel.wantIgnored++; }
                if ((w[1] & 2) != 0) { if (mask.get(LIT + i)) { light.add(i); CubeCacheModel.wantLight++; } else CubeCacheModel.wantIgnored++; }
            }
            try {
                if (!blocks.isEmpty()) writeCubes(p, c, new ArrayList<>(blocks), false);
                if (!light.isEmpty()) { List<Integer> li = new ArrayList<>(light); writeLight(p, c, full(c, li, sun), li, sun, false); }
            } catch (RuntimeException ex) {
                failed("WANT столбца " + c.getChunkX() + " " + c.getChunkZ(), ex);
            }
        }
    }

    /** /hocubes cachecheck: op 15 — версии непустых неоднородных кубов, отправленных игроку; клиент сверит и ответит op 6. */
    static int cacheCheck(Player p) {
        State st = STATES.get(p.getUuid());
        if (st == null || st.instance == null || !Handshake.coreAtLeast(p, CubeCacheModel.CORE)) return -1;
        List<long[]> all = new ArrayList<>();
        for (var en : st.sent.entrySet()) {
            long k = en.getKey();
            if (!(st.instance.getChunk((int) (k >> 32), (int) k) instanceof TallColumn c)) continue;
            BitSet mask = en.getValue();
            int min = c.getMinSection();
            c.lockReadLock();
            try {
                List<Section> secs = c.getSections();
                for (int i = mask.nextSetBit(0); i >= 0 && i < LIT && i < secs.size() && all.size() < 8192; i = mask.nextSetBit(i + 1))
                    if (c.inMemory(i) && secs.get(i).blockPalette().singleValue() < 0)
                        all.add(new long[]{pos(c.getChunkX(), i + min, c.getChunkZ()), c.cubeHashFresh(i)});
            } finally { c.unlockReadLock(); }
        }
        for (int s = 0; s < all.size(); s += 512) {
            List<long[]> part = all.subList(s, Math.min(all.size(), s + 512));
            p.sendPacket(new PluginMessagePacket(CHANNEL, NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, (byte) 15);
                buf.write(NetworkBuffer.VAR_INT, part.size());
                for (long[] e : part) { buf.write(NetworkBuffer.LONG, e[0]); buf.write(NetworkBuffer.LONG, e[1]); }
            })));
        }
        return all.size();
    }

    /** Биты маски столбца от LIT и выше — «у куба окна уже есть свет». */
    private static final int LIT = 4096;
    /**
     * Биты от NDEF (0.54, op 16): последняя запись света куба, отправленная игроку, — не «всё 15». Такой куб явной
     * записью шлём и выше пола: у клиента мог остаться слой движка с тенью (op 4 его не стирает). Живут и вне окна.
     */
    private static final int NDEF = 2 * LIT;

    /** Свет столбца только для этих секций (бит маски света = индекс секции + 1: нулевой — под миром). */
    private static LightData slice(TallColumn c, LightData full, List<Integer> idx, boolean sun) {
        BitSet want = new BitSet();
        for (int i : idx) want.set(i + 1);
        BitSet sky = new BitSet(), block = new BitSet();
        List<byte[]> skyArr = new ArrayList<>(), blockArr = new ArrayList<>();
        int n = 0;
        for (int b = full.skyMask().nextSetBit(0); b >= 0; b = full.skyMask().nextSetBit(b + 1), n++)
            if (want.get(b)) { sky.set(b); skyArr.add(full.skyLight().get(n)); }
        if (sun) {                                                    // пустые кубы без своего массива — как у игры:
            List<Integer> extra = new ArrayList<>();                  // нижний слой ближайшего массива выше
            for (int i : idx) if (!full.skyMask().get(i + 1)) extra.add(i);
            for (int i : extra) {
                byte[] a = derivedSky(full, i);
                if (a == null) continue;                              // выше ничего / всё 15 — клиент и так считает 15
                int pos = 0;                                          // вставить по возрастанию бита
                for (int b = sky.nextSetBit(0); b >= 0 && b < i + 1; b = sky.nextSetBit(b + 1)) pos++;
                sky.set(i + 1);
                skyArr.add(pos, a);
            }
        }
        n = 0;
        for (int b = full.blockMask().nextSetBit(0); b >= 0; b = full.blockMask().nextSetBit(b + 1), n++)
            if (want.get(b)) { block.set(b); blockArr.add(full.blockLight().get(n)); }
        BitSet es = (BitSet) full.emptySkyMask().clone(); es.and(want);
        BitSet eb = (BitSet) full.emptyBlockMask().clone(); eb.and(want);
        if (sky.isEmpty() && block.isEmpty() && es.isEmpty() && eb.isEmpty()) return null;
        return new LightData(sky, block, es, eb, skyArr, blockArr);
    }

    /** Свет неба пустой секции i: нижний слой ближайшей секции выше с массивом, повторённый 16 раз (null — всё 15). */
    private static byte[] derivedSky(LightData full, int i) {
        int b = full.skyMask().nextSetBit(i + 2);
        if (b < 0) return null;
        int n = 0;
        for (int k = full.skyMask().nextSetBit(0); k >= 0 && k < b; k = full.skyMask().nextSetBit(k + 1)) n++;
        byte[] src = full.skyLight().get(n);
        boolean all15 = true;
        for (int k = 0; k < 128; k++) if (src[k] != (byte) 0xFF) { all15 = false; break; }
        if (all15) return null;
        byte[] out = new byte[2048];
        for (int y = 0; y < 16; y++) System.arraycopy(src, 0, out, y * 128, 128);
        return out;
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    /** SectionPos.asLong клиента: X 22 бита (старшие), Z 22, Y 20 (младшие). */
    static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFF);
    }

    /** Очереди света кубами (для DebugTab): см. CubeLight.queued. */
    public static int[] lightQueued() { return CubeLight.queued(); }

    static String stats() {
        return "кубов отправлено " + sentCubes + ", выгружено " + unloadedCubes + ", игроков на кубовых картах " + STATES.size()
                + ", свет при правках: " + (lightBytes / 1024) + " КБ всего; " + LightOp.stats()
                + "; сбоев отправки " + sendFailures + ", подстраховка дослала столбцов " + healResent + ", скелетов вне обзора " + staleLoads
                + "; очередь Minestom: убрано вне обзора " + queueStale + ", повторов " + queueDup + (queueBroken || CHUNK_QUEUE == null || VIEW_CENTER == null ? " (чистка ВЫКЛ)" : "")
                + "; вперёд по ходу тиков " + aheadTicks + (headingBroken || PQ_CMP == null ? " (ВЫКЛ)" : "")
                + "; пустых без света op 11 не слано " + lightSkipped
                + "; op 16: кубов без записи " + skyDefault + ", полов отправлено " + floorsSent + (FLOOR_ON ? "" : " (ВЫКЛ)")
                + (FLOOR_CHECK ? ", расхождений floorcheck " + floorMismatch : "")
                + "; правило записи: отменено " + cancelled + ", кубов ресинк " + resyncCubes
                + "; кубов загружено " + CubeStore.loads.get() + ", в очереди " + CubeStore.queued()
                + ", ошибок чтения " + CubeStore.loadErrors.get() + ", запись в незагруженный " + CubeStore.violations.get()
                + "; " + CubeCacheModel.stats();
    }
}
