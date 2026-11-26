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
 *   17 HEIGHTS (0.56) varint n; n × (int cx, int cz, 256 × short h, индекс z*16+x): MOTION_BLOCKING как у ванильной
 *             карты высот — Y над верхним блокирующим (верх + 1), блоков нет — TallWorld.MIN_Y. После скелета столбца
 *             и при каждой смене (в т.ч. вне окна). Последний пришедший — верный.
 *   0.56: op 11/14 идут и для кольца ±1 куб за окном ±v (биты HALO) — блоков там у клиента нет, свет держим свежим
 *   18 HEIGHTS2 (0.57, вместо op 17) varint n; n × (int cx, int cz, byte flags, motion, sky), индекс z*16+x:
 *             motion — как op 17 (Y над верхним блокирующим движение, нет — TallWorld.MIN_Y);
 *             sky — skyTop+1: в клетке с Y ≥ sky небо 15 (HeightIndex.SKY; нет — TallWorld.MIN_Y);
 *             flags bit0 — motion одно на столбец (1 short, иначе 256 short); bit2 — sky == motion поэлементно
 *             (sky не шлётся); иначе bit1 — sky одно на столбец (1 short), без bit1 — 256 short.
 *             После скелета и при любой смене motion или skyTop (в т.ч. вне окна). Последний пришедший — верный.
 *             На ленивой карте skyTop над незагруженными кубами — оценка сверху (уточнится при загрузке куба).
 *   19 LIGHT_MODE (0.57) byte режим: 1 — свет считает клиент (сервер не шлёт op 11/14/16), 0 — свет от сервера.
 *             При каждом входе в инстанс, до первого скелета (рядом с op 12).
 *   0.57 со светом на клиенте (clientLight): op 11/14/16 и UpdateLightPacket не шлются вовсе; блоки (op 2/13) идут
 *             в окне ±(v+1) — кольцо ±1 за окном op 5 приходит блоками (клиент сам светит край окна); op 4 — за ±(v+1)
 * C2S hoka_odyssey:wc (этап 5, ядро 0.45.0+):
 *   2 VIEW    varint — «Дальность по высоте» из настроек клиента (кубов вверх и вниз); при входе и при смене;
 *             0.62: + varint «горы целиком» — до скольких кубов от игрока слать полосу поверхности (0 — выкл)
 *   3 WANT    (0.51) varint n; n × (long pos, byte что: 1 блоки, 2 свет) — промах кэша: прислать целиком
 *   4 HAVE    (0.51) long mapKey, varint бюджетМБ (0 — кэш выкл), varint n; n × (int cx, int cz, int y0 секция,
 *             varint len, ⌈len/8⌉ Б маска блоков, ⌈len/8⌉ Б маска света) — что лежит в кэше клиента
 *   5 EVICT   (0.51) long mapKey, varint n; n × (int cx, int cz) — столбцы вытеснены из кэша клиента
 *   6 HASH_REPORT (0.51) varint проверено, varint расхождений, varint k, k × long pos
 *   7 LAG     (0.57) varint задержка_мс, varint макс_мс_за_5с, varint очередь — раз в секунду: сколько ответ на Ping
 *             ждал главного потока клиента (DebugTab)
 *   8 GEN_STATUS, 9 GEN_SAMPLE (0.59) — см. GenWire / ClientGen
 * 0.62 полоса поверхности (горы целиком): ядру 0.62+ со светом на клиенте, кроме окна ±(v+1), идут и кубы полосы
 *   поверхности каждого столбца (surface): от нижней видимой клетки (свой низ SURFACE и края соседей, утёсы) до
 *   верха SURFACE, не дальше ±S кубов от игрока (VIEW, -Dhoka.cubes.surface-max). Пустые не шлются (как всегда),
 *   нетронутые — метками op 21. Окно op 5 прежнее: в полосу вне окна игрок не ходит, свет там — формула F клиента.
 * 0.59 генерация нетронутых кубов на клиенте (ClientGen, формат — ink.hoka.odyssey.gen.GenWire): S2C op 20 GEN_DEF при
 *   входе в инстанс (после op 12/19, до скелетов); пока клиент не ответил op 8 «ок» — всё по-старому. Дальше куб окна
 *   ±(v+1), которого нет на диске и который не менялся (CubeColumn.touched), у переносимого генератора НЕ грузится и
 *   уходит меткой op 21 (бит GENB маски); сервер грузит только зону ±ZONE кубов вокруг игрока (правки, сущности,
 *   секреты op 22, хэш в op 21). Первая правка нетронутого куба — op 2 всем, у кого он меткой (drainTouched).
 *   op 4 снимает и метки. op 20 со slot 0 — генерация выключена: метки сняты, окно перешлётся op 2.
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
    /** 0.62: предел полосы поверхности (кубов вверх и вниз от игрока): -Dhoka.cubes.surface-max, 0 — выкл. */
    public static final int SURFACE_MAX = Math.max(0, Integer.getInteger("hoka.cubes.surface-max", 128));
    /** Ядро с полосой поверхности (горы целиком). */
    static final int[] SURFACE_CORE = {0, 62, 0};
    /** Полоса ниже своей клетки не глубже (кубов): отвесная стена у пропасти — дальше не шлём. */
    static final int SURFACE_DEPTH = 32;
    /** Что попросил клиент (второе число op 2 VIEW). */
    private static final Map<UUID, Integer> SURF_REQ = new ConcurrentHashMap<>();
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
        /** 0.62: предел полосы поверхности (кубов от игрока), 0 — полосы нет. */
        int s;
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
        /**
         * Сдвиг окна по Y порциями (shift): столбцы (ключи sent), которым ещё нужен update() под текущее окно, ближние
         * к игроку первыми; pendAt — следующий. Новая смена Y до конца прохода — список заново из всего sent.
         */
        long[] pend = new long[0];
        int pendAt, pendN, shiftTicks;
        /** op 17 (0.56) для пришедших скелетов: шлёт тик пачками (после скелета — порядок соблюдён). */
        final java.util.concurrent.ConcurrentLinkedQueue<Long> heightsQ = new java.util.concurrent.ConcurrentLinkedQueue<>();
        /** 0.59: WANT клиента по кубам-меткам (хэш op 21 не сошёлся / не сгенерировал) — правду op 2 (тик). */
        final java.util.concurrent.ConcurrentLinkedQueue<Long> genWants = new java.util.concurrent.ConcurrentLinkedQueue<>();
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
            if (st.sy == Integer.MIN_VALUE) { st.sy = p.getPosition().blockY() >> 4; st.v = vertical(p); st.s = surface(p); window(p, st); }
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
                    for (int i = old.nextSetBit(NDEF); i >= 0 && i < HALO; i = old.nextSetBit(i + 1)) mask.set(i);   // HALO — нет: свет заново
                }
                try { update(p, st, tc, mask); }
                catch (RuntimeException ex) { failed("отправка столбца " + tc.getChunkX() + " " + tc.getChunkZ(), ex); }   // недосланное дошлёт heal
                if (heightsOp(p)) st.heightsQ.add(key(e.getChunkX(), e.getChunkZ()));   // 0.56: высоты MOTION_BLOCKING (дождь/снег) — пачкой в тике
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
            try { sendLightMode(p, e.getInstance()); } catch (RuntimeException ex) { failed("op 19 игроку " + p.getUsername(), ex); }
            try { ClientGen.enter(p, e.getInstance()); } catch (RuntimeException ex) { failed("op 20 игроку " + p.getUsername(), ex); }
        });
        ev.addListener(PlayerDisconnectEvent.class, e -> { STATES.remove(e.getPlayer().getUuid()); REQUESTED.remove(e.getPlayer().getUuid()); SURF_REQ.remove(e.getPlayer().getUuid()); HEADINGS.remove(e.getPlayer().getUuid()); CubeCacheModel.remove(e.getPlayer().getUuid()); ClientGen.quit(e.getPlayer()); });
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
                if (op == 7) {                                                 // LAG (0.57): задержка главного потока клиента
                    if (!Handshake.coreAtLeast(p, Handshake.CLIENT_LIGHT_CORE)) return;
                    int last = b.read(NetworkBuffer.VAR_INT), max = b.read(NetworkBuffer.VAR_INT), q = b.read(NetworkBuffer.VAR_INT);
                    ink.hoka.odyssey.server.DebugTab.clientLag(p, last, max, q);
                    return;
                }
                if (op == 8 || op == 9) {                                      // 0.59: генерация на клиенте
                    if (!Handshake.coreAtLeast(p, Handshake.CLIENT_GEN_CORE)) return;
                    if (op == 8) ClientGen.onStatus(p, ink.hoka.odyssey.gen.GenWire.decodeStatus(m));
                    else ClientGen.onSample(p, ink.hoka.odyssey.gen.GenWire.decodeSample(m));
                    return;
                }
                if (op != 2) return;                                           // незнакомый op — пропуск
                int v = b.read(NetworkBuffer.VAR_INT);
                REQUESTED.put(p.getUuid(), Math.max(4, Math.min(64, v)));
                if (b.readableBytes() > 0) SURF_REQ.put(p.getUuid(), Math.max(0, Math.min(1024, b.read(NetworkBuffer.VAR_INT))));   // 0.62
            } catch (Exception ex) {
                LOG.warn("{}: битый hoka_odyssey:wc ({})", p.getUsername(), ex.toString());
            }
        });
        MinecraftServer.getSchedulerManager().buildTask(CubeStreamer::tick)
                .repeat(net.minestom.server.timer.TaskSchedule.tick(1)).schedule();
    }

    private static State state(Player p) {
        State st = STATES.computeIfAbsent(p.getUuid(), u -> new State());
        if (st.instance != p.getInstance()) { st.sent.clear(); st.floors.clear(); st.heightsQ.clear(); st.pendAt = st.pendN = 0; st.sy = Integer.MIN_VALUE; st.wa = st.wb = Integer.MIN_VALUE; st.instance = p.getInstance(); }
        return st;
    }

    /** Каждый тик: дослать ставшие READY кубы; игрок сменил секцию по высоте — сдвинуть окно во всех видимых столбцах (порциями). */
    private static void tick() {
        drainReady();
        drainTouched();                                                    // 0.59: первая правка куба-метки — op 2
        drainHeights();
        long deadline = System.nanoTime() + SHIFT_BUDGET_NS;              // общий бюджет сдвига окна на тик
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Instance inst = p.getInstance();
            boolean cubes = TallWorld.isCubes(inst);
            try { heading(p, cubes); } catch (RuntimeException ex) { failed("ход игрока " + p.getUsername(), ex); }
            if (!cubes) continue;
            State st = STATES.get(p.getUuid());
            if (st == null || st.instance != inst) continue;
            try {
                wants(p, st);                                               // кэш 0.51: промахи клиента — кубы/свет целиком
                genWants(p, st);                                            // 0.59: куб-метка не сошёлся у клиента — правду op 2
                if (!st.heightsQ.isEmpty()) {                               // op 17 пришедших скелетов
                    List<int[]> hs = new ArrayList<>();
                    for (Long k; (k = st.heightsQ.poll()) != null; )      // высоты — на сейчас (не старее op 17 из drainHeights)
                        if (st.sent.containsKey(k) && inst.getChunk((int) (k >> 32), (int) (long) k) instanceof TallColumn tc) hs.add(heights(tc));
                    if (!hs.isEmpty()) writeHeights(p, hs);
                }
                purgeQueue(p, st);                                          // устаревшие/повторные записи очереди Minestom — вон
                synchronized (st.touched) { st.touched.clear(); }          // не отменённые действия — ресинк не нужен
                int sy = p.getPosition().blockY() >> 4;
                if (sy != st.sy || vertical(p) != st.v || surface(p) != st.s) {
                    st.sy = sy;
                    st.v = vertical(p);
                    st.s = surface(p);
                    window(p, st);
                    plan(p, st);                                            // все столбцы sent — в очередь сдвига, ближние первыми
                }
                if (st.pendAt < st.pendN) {                                 // идёт сдвиг — он сам и есть досылка (heal не нужен)
                    shift(p, st, inst, deadline);
                    if (CubeStore.lazy(inst)) window(p, st);
                    continue;
                }
                // раз в секунду: недосланное и незапрошенное — дослать/запросить; столбцы вплотную к игроку — 4 раза
                // в секунду (дыра под ногами после телепорта закрывается за ≤0,25 с, а не ≤1 с)
                if (++st.heal % 20 == 0) heal(p, st, inst, Integer.MAX_VALUE);
                else if (st.heal % 5 == 0) heal(p, st, inst, 2);
                if (CubeStore.lazy(inst)) window(p, st);                    // загрузились кубы — окно клиента шире
            } catch (RuntimeException ex) {
                failed("тик игрока " + p.getUsername(), ex);
            }
        }
    }

    /** Бюджет сдвига окна по Y на тик (на всех игроков): -Dhoka.cubes.shift-us, по умолчанию 1500 мкс. */
    static final long SHIFT_BUDGET_NS = 1000L * Math.max(100, Integer.getInteger("hoka.cubes.shift-us", 1500));
    /** Столбцов за тик игроку не меньше этого: сдвиг идёт, даже если бюджет съел другой игрок. */
    static final int SHIFT_MIN = 8;
    /** Отладка: столбцов сдвинуто порциями; последний законченный проход — столбцов и тиков; прервано новой сменой Y. */
    static volatile long shiftCols, shiftRestarts;
    static volatile int lastShiftCols, lastShiftTicks;

    /**
     * Смена секции по Y: все столбцы sent — в очередь сдвига по расстоянию от игрока (Чебышёв, ближние первыми;
     * подсчётом, O(n)). Незаконченный прошлый проход отбрасывается: update() идемпотентен к текущему окну — уже
     * сдвинутые столбцы сдвинутся ещё раз (снимут/дошлют разницу), остальные — сразу в новое окно. Что у клиента,
     * описывает маска sent: кубы не теряются, вне окна их выгрузит update(). Пока столбец ждёт, у клиента в нём
     * старое окно (кубы сверх нового держит и CubeStore: они в sent — interest).
     */
    private static void plan(Player p, State st) {
        long[] keys = new long[st.sent.size() + 64];
        int n = 0;
        for (Long k : st.sent.keySet()) { if (n == keys.length) keys = java.util.Arrays.copyOf(keys, n * 2); keys[n++] = k; }
        int pcx = p.getPosition().blockX() >> 4, pcz = p.getPosition().blockZ() >> 4;
        st.pend = order(keys, n, pcx, pcz);
        if (st.pendAt < st.pendN) shiftRestarts++;
        st.pendAt = 0;
        st.pendN = n;
        st.shiftTicks = 0;
    }

    /** Первые n ключей по Чебышёву от (pcx, pcz), ближние первыми (сортировка подсчётом). */
    static long[] order(long[] keys, int n, int pcx, int pcz) {
        int[] d = new int[n];
        int maxD = 0;
        for (int j = 0; j < n; j++) {
            long k = keys[j];
            d[j] = (int) Math.min(4095L, Math.max(Math.abs((long) (int) (k >> 32) - pcx), Math.abs((long) (int) k - pcz)));
            if (d[j] > maxD) maxD = d[j];
        }
        int[] at = new int[maxD + 2];
        for (int j = 0; j < n; j++) at[d[j] + 1]++;
        for (int j = 1; j < at.length; j++) at[j] += at[j - 1];
        long[] out = new long[n];
        for (int j = 0; j < n; j++) out[at[d[j]]++] = keys[j];
        return out;
    }

    /** Порция сдвига: столбцы из очереди, пока не вышел бюджет тика (не меньше SHIFT_MIN). Свет пустых — общей пачкой. */
    private static void shift(Player p, State st, Instance inst, long deadline) {
        LightBatch lb = new LightBatch(p);
        BATCH.set(lb);
        int done = 0;
        try {
            while (st.pendAt < st.pendN) {
                if (done >= SHIFT_MIN && System.nanoTime() >= deadline) break;
                long k = st.pend[st.pendAt++];
                BitSet mask = st.sent.get(k);
                if (mask == null) continue;                                 // столбец уже забыт (выгрузка/вне обзора)
                // сбой одного столбца не должен оставить остальные без сдвига окна; недосланное дошлёт heal
                if (inst.getChunk((int) (k >> 32), (int) k) instanceof TallColumn tc) {
                    try { update(p, st, tc, mask); }
                    catch (RuntimeException ex) { failed("окно столбца " + tc.getChunkX() + " " + tc.getChunkZ(), ex); }
                    done++;
                }
            }
        } finally {
            BATCH.remove();
            lb.flush();
        }
        shiftCols += done;
        st.shiftTicks++;
        // проход закончен: следующая подстраховка — полная, через секунду (как раньше после смены секции)
        if (st.pendAt >= st.pendN) { lastShiftCols = st.pendN; lastShiftTicks = st.shiftTicks; st.heal = 0; }
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

    /**
     * Обычная карта (Maps.reapColumns): чанк в обзоре Minestom у кого-то из игроков карты (в т.ч. ждёт в очереди
     * отправки) или в зрительской дальности от позиции (+1 — позиция могла уйти вперёд центра). O(игроков).
     */
    public static boolean nearAnyPlayer(Instance inst, int cx, int cz) {
        for (Player p : inst.getPlayers()) {
            if (inView(p, cx, cz)) return true;
            var pos = p.getPosition();
            int d = Math.max(Math.abs(cx - (pos.blockX() >> 4)), Math.abs(cz - (pos.blockZ() >> 4)));
            if (d <= Math.max(p.effectiveViewDistance(), net.minestom.server.ServerFlag.CHUNK_VIEW_DISTANCE) + 1) return true;
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
            Span sp = span(p, st, c, ext(p));                               // 0.62: и полоса поверхности
            boolean miss = false, unsent = false;
            var g = genFor(p, st, c);
            for (int i = sp.lo(), iEnd = sp.hi(); i <= iEnd; i++) {
                if (!sp.has(i)) continue;
                if (g != null && genCube(g, c, i, mask)) {                 // 0.59: куб — меткой; грузим только зону
                    boolean zone = zone(p, c.getChunkX(), i + min, c.getChunkZ());
                    if (!c.ready(i)) { if (zone) { c.request(i, prio(p, c.getChunkX(), i + min, c.getChunkZ())); miss = true; } }
                    else if (mask.get(GENB + i)) {
                        mask.set(LIT + i);                                  // в памяти — правило записи пускает
                        if (zone) genSecrets(p, g, c, i, mask);
                    }
                    if (!mask.get(GENB + i)) unsent = true;                // метка не ушла — update пошлёт op 21
                    continue;
                }
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
        if (!layerReady(inst, st, pcx, pcz, st.sy)) return ((long) (st.sy + 1) << 32) | (st.sy & 0xFFFFFFFFL);
        int a = st.sy, b = st.sy;
        while (a - 1 >= lo && layerReady(inst, st, pcx, pcz, a - 1)) a--;
        while (b + 1 <= hi && layerReady(inst, st, pcx, pcz, b + 1)) b++;
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    /** Слой sy в 3×3 столбцах «есть у клиента»: в памяти сервера или (0.59) ушёл меткой — клиент сам не пустит в ждущий генерации куб. */
    private static boolean layerReady(Instance inst, State st, int pcx, int pcz, int sy) {
        int i = sy - (TallWorld.MIN_Y >> 4);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            if (inst.getChunk(pcx + dx, pcz + dz) instanceof TallColumn tc && !tc.ready(i)) {
                BitSet m = st.sent.get(key(pcx + dx, pcz + dz));
                if (m == null || i < 0 || i >= LIT || !m.get(GENB + i)) return false;
            }
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
                    boolean cl = clientLight(p);
                    int e1 = cl ? 1 : 0;
                    Span sp = span(p, st, c, e1);                           // 0.62: и полоса поверхности
                    int from = sp.from(), to = sp.to();
                    List<Integer> add = new ArrayList<>(), lit = new ArrayList<>(), halo = new ArrayList<>();
                    boolean ho = haloOp(p);
                    List<Integer> regen = null;
                    for (int i : e.getValue()) {
                        if (mask.get(GENB + i)) {                           // 0.59: у клиента меткой — блоки не слать
                            if (!sp.has(i) || !c.ready(i)) continue;
                            mask.set(LIT + i);                              // в памяти — правило записи пускает
                            if (mask.get(GENR + i)) { mask.clear(GENR + i); if (regen == null) regen = new ArrayList<>(); regen.add(i); }
                            var g = genFor(p, st, c);
                            if (g != null && zone(p, c.getChunkX(), i + min, c.getChunkZ())) genSecrets(p, g, c, i, mask);
                            continue;
                        }
                        if ((i == from - 1 || i == to + 1) && ho && !mask.get(HALO + i) && !halo.contains(i) && c.ready(i)) { halo.add(i); continue; }   // кольцо ±1 (0.56)
                        if (!sp.has(i) || mask.get(LIT + i) || lit.contains(i) || !c.ready(i)) continue;   // успели выгрузить — не слать
                        lit.add(i);
                        if (secs.get(i).blockPalette().count() > 0) add.add(i);
                    }
                    if (regen != null) {                                    // правку игрока в куб-метку отменили — откатить op 2
                        writeCubes(p, c, regen, false);
                        for (int i : regen) if (secs.get(i).blockPalette().count() > 0) mask.set(i);
                    }
                    if (lit.isEmpty() && halo.isEmpty()) continue;
                    if (!add.isEmpty()) {
                        writeCubes(p, c, add, true);
                        for (int i : add) mask.set(i);
                        sentCubes += add.size();
                    }
                    if (!cl) {                                              // 0.57: свет считает клиент
                        List<Integer> all = new ArrayList<>(lit);           // кольцо — только свет (блоков у клиента там нет)
                        all.addAll(halo);
                        all.sort(null);
                        int[] fl = skyFloor(p, st, c, sun);
                        sendLight(p, c, skipSky(p, c, mask, lightNeeded(c, all, add, mask, sun), fl[0]), sun);
                        sendFloor(p, st, c, fl[1]);                         // op 16 — после op 11 столбца
                    }
                    for (int h : halo) mask.set(HALO + h);
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
            int mg = UNLOAD_MARGIN + ext(p);                                   // 0.57: кольцо ±1 блоками — запас от него
            int from = Math.max(0, st.sy - st.v - mg - min), to = Math.min(n - 1, st.sy + st.v + mg - min);
            if (from <= to) keep.set(from, to + 1);
            if (st.s > 0) {                                                     // 0.62: полоса поверхности (запрошенные ещё не READY)
                Span sp = span(p, st, c, mg);
                if (sp.band()) keep.set(sp.bLo(), sp.bHi() + 1);
            }
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
            if (mask.get(GENB + i) && !c.ready(i)) {                       // 0.59: метка, у сервера куба нет — op 2, когда загрузится
                mask.set(GENR + i);
                c.request(i, 0);
                continue;
            }
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
            if (clientLight(p)) { resyncCubes += in.size() + out.size(); return true; }   // 0.57: свет пересчитает клиент
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

    // ───────────── 0.62: полоса поверхности (горы целиком) ─────────────

    /** Предел полосы поверхности игроку (кубов от его куба); 0 — полосы нет (старое ядро, свет от сервера, выкл). */
    static int surface(Player p) {
        if (SURFACE_MAX == 0 || !clientLight(p) || !Handshake.coreAtLeast(p, SURFACE_CORE)) return 0;
        Integer r = SURF_REQ.get(p.getUuid());
        return Math.min(SURFACE_MAX, r == null ? SURFACE_MAX : r);
    }

    /**
     * Кубы столбца игроку (индексы секций): окно from..to и полоса поверхности bLo..bHi (bLo &gt; bHi — нет).
     * Полоса — от нижней видимой клетки до верха SURFACE столбца, обрезана пределом ±s от игрока.
     */
    record Span(int from, int to, int bLo, int bHi) {
        boolean has(int i) { return i >= from && i <= to || i >= bLo && i <= bHi; }
        boolean band() { return bLo <= bHi; }
        int lo() { return band() ? Math.min(from, bLo) : from; }
        int hi() { return band() ? Math.max(to, bHi) : to; }
        /** Сколько кубов полосы вне окна. */
        int extra() {
            if (!band()) return 0;
            int n = bHi - bLo + 1;
            int a = Math.max(from, bLo), b = Math.min(to, bHi);
            return a <= b ? n - (b - a + 1) : n;
        }
        /** Снять в m биты base+i всех i ∈ [0, n) вне окна и полосы. */
        void clearOutside(BitSet m, int base, int n) {
            int a = Math.max(0, lo()), b = Math.min(n - 1, hi());
            if (a > b) { m.clear(base, base + n); return; }
            m.clear(base, base + a);
            if (b + 1 < n) m.clear(base + b + 1, base + n);
            if (!band()) return;
            if (bLo > to + 1) m.clear(base + Math.max(0, to + 1), base + Math.min(n, bLo));            // щель окно..полоса
            else if (bHi < from - 1) m.clear(base + Math.max(0, bHi + 1), base + Math.min(n, from)); // щель полоса..окно
        }
    }

    /** Окно столбца с запасом extra (кубов) и полоса поверхности игрока. */
    static Span span(Player p, State st, TallColumn c, int extra) {
        int min = c.getMinSection(), n = c.getSections().size();
        int from = Math.max(0, st.sy - st.v - extra - min), to = Math.min(n - 1, st.sy + st.v + extra - min);
        if (st.s <= 0) return new Span(from, to, 1, 0);
        long b = band(c);
        if (b == NO_BAND) return new Span(from, to, 1, 0);
        int lo = Math.max(Math.max(0, st.sy - st.s - min), Math.floorDiv((int) (b >> 32), 16) - min);
        int hi = Math.min(Math.min(n - 1, st.sy + st.s - min), Math.floorDiv((int) b, 16) - min);
        return new Span(from, to, lo, hi);
    }

    static final long NO_BAND = Long.MIN_VALUE;

    /**
     * Полоса поверхности столбца в блоках: низ &lt;&lt; 32 | верх, NO_BAND — блоков нет. Верх — max SURFACE;
     * низ — min SURFACE и по краям — клетки ниже соседа видны сбоку до его верха + 1 (утёс), не глубже SURFACE_DEPTH кубов.
     * Высоты на ленивой карте над незагруженными кубами — оценка сверху (уточнится — heal дошлёт).
     */
    static long band(TallColumn c) {
        Instance inst = c.getInstance();
        return band(c, inst == null ? (x, z) -> null : (x, z) -> inst.getChunk(x, z) instanceof TallColumn t ? t : null);
    }

    /** То же с поиском соседних столбцов nb (тесты). */
    static long band(TallColumn c, java.util.function.BiFunction<Integer, Integer, TallColumn> nb) {
        HeightIndex h = c.st().heights;
        int mn = Integer.MAX_VALUE, mx = HeightIndex.NONE;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int v = h.get(HeightIndex.Kind.SURFACE, x, z);
            if (v == HeightIndex.NONE) continue;
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        if (mx == HeightIndex.NONE) return NO_BAND;
        int cx = c.getChunkX(), cz = c.getChunkZ(), floor = mn - SURFACE_DEPTH * 16;
        for (int d = 0; d < 4; d++) {
            int dx = d == 0 ? 1 : d == 1 ? -1 : 0, dz = d == 2 ? 1 : d == 3 ? -1 : 0;
            TallColumn nc = nb.apply(cx + dx, cz + dz);
            if (nc == null) continue;
            HeightIndex nh = nc.st().heights;
            for (int k = 0; k < 16; k++) {
                int ox = dx == 1 ? 15 : dx == -1 ? 0 : k, oz = dz == 1 ? 15 : dz == -1 ? 0 : k;   // своя крайняя клетка
                int own = h.get(HeightIndex.Kind.SURFACE, ox, oz);
                if (own == HeightIndex.NONE) continue;
                int nv = nh.get(HeightIndex.Kind.SURFACE, (ox + dx) & 15, (oz + dz) & 15);   // клетка соседа рядом
                if (nv < own) mn = Math.min(mn, Math.max(floor, nv + 1));
            }
        }
        return ((long) mn << 32) | (mx & 0xFFFFFFFFL);
    }

    /** Отладка: кубов полосы вне окна (по последним update), столбцов с полосой. */
    static volatile long surfaceCubes, surfaceCols;

    /**
     * Окно столбца: дослать непустые кубы окна, выгрузить отправленные вне окна. Под замком состояния игрока: метка op 21
     * (бит GENB ставится ДО проверки «тронут») и op 2 первой правки (drainTouched) не обгоняют друг друга.
     */
    private static void update(Player p, State st, TallColumn c, BitSet mask) {
        synchronized (st) { updateLocked(p, st, c, mask); }
    }

    private static void updateLocked(Player p, State st, TallColumn c, BitSet mask) {
        List<Section> secs = c.getSections();
        int min = c.getMinSection();
        boolean cl = clientLight(p);                                        // 0.57: блоки окна ±(v+1), свет не слать
        int e1 = cl ? 1 : 0;
        Span sp = span(p, st, c, e1);                                       // 0.62: окно + полоса поверхности
        int from = sp.from(), to = sp.to();
        if (sp.band()) { surfaceCols++; surfaceCubes += sp.extra(); }
        // готовность снимаем ОДИН раз: куб, ставший READY в рабочем потоке посреди update, иначе получал бит LIT (и свет),
        // не попав в add, — drainReady его пропускал (LIT уже стоит), у игрока дыра до смены секции по Y
        BitSet rdy = new BitSet();
        List<Integer> add = new ArrayList<>();
        // 0.59: нетронутые кубы переносимого генератора — меткой op 21 (клиент генерирует сам), грузим только зону
        var g = genFor(p, st, c);
        List<ink.hoka.odyssey.gen.GenWire.Cube> labels = null;
        List<Integer> zoneReady = null;
        int cx = c.getChunkX(), cz = c.getChunkZ();
        for (int i = sp.lo(), iEnd = sp.hi(); i <= iEnd; i++) {
            if (!sp.has(i)) continue;
            if (g != null && genCube(g, c, i, mask)) {
                int cy = i + min;
                boolean zone = zone(p, cx, cy, cz);
                if (zone && !c.ready(i)) c.request(i, prio(p, cx, cy, cz));
                if (!mask.get(GENB + i)) {
                    mask.set(GENB + i);                                     // сначала бит, потом «тронут?» (гонка с drainTouched)
                    if (((CubeColumn) c).touched(i)) mask.clear(GENB + i);
                    else {
                        if (labels == null) labels = new ArrayList<>();
                        labels.add(label(g, c, i));
                    }
                }
                if (mask.get(GENB + i)) {
                    if (c.ready(i)) {
                        rdy.set(i);                                         // LIT: в памяти — правило записи пускает
                        if (zone) { if (zoneReady == null) zoneReady = new ArrayList<>(); zoneReady.add(i); }
                    }
                    continue;
                }
            } else if (mask.get(GENB + i)) mask.clear(GENB + i);           // тронут / выключено — дальше по-старому
            if (!c.ready(i)) { c.request(i, prio(p, cx, i + min, cz)); continue; }
            rdy.set(i);
            if (!mask.get(i) && secs.get(i).blockPalette().count() > 0) add.add(i);
        }
        // свет неба нужен и пустым кубам окна (пещера под крышей тёмная); биты LIT+i — куб окна уже со светом.
        // Без неба — ещё отправленные кубы без света (прошлая отправка сорвалась после блоков).
        boolean sun = c.getInstance().getCachedDimensionType().hasSkylight();
        List<Integer> lit = new ArrayList<>(add);
        // HALO (0.56): свет куба, свежий у клиента с кольца ±1 — входя в окно, второй раз его не слать
        for (int i = rdy.nextSetBit(0); i >= 0; i = rdy.nextSetBit(i + 1))
            if (!mask.get(LIT + i) && !mask.get(HALO + i) && !add.contains(i) && (sun || mask.get(i))) lit.add(i);
        // кольцо ±1 куб за окном (0.56): у клиента там слой света, по которому он освещает грани края окна, —
        // держать свежим (relightCubes по битам HALO). Был в окне (LIT) или уже в кольце (HALO) — свет у клиента свежий.
        List<Integer> halo = new ArrayList<>(2);
        BitSet haloNow = new BitSet();
        if (haloOp(p)) for (int h : new int[]{from - 1, to + 1}) {
            if (h < 0 || h >= secs.size() || !c.ready(h)) continue;        // не в памяти — свет придёт с drainReady
            haloNow.set(h);
            if (!mask.get(LIT + h) && !mask.get(HALO + h) && (sun || CubeLight.active(c) && CubeLight.blockLit((CubeColumn) c, h))) halo.add(h);
        }
        lit.addAll(halo);
        lit.sort(null);
        mask.clear(HALO, HALO + secs.size());
        for (int h = haloNow.nextSetBit(0); h >= 0; h = haloNow.nextSetBit(h + 1))
            if (!halo.contains(h)) mask.set(HALO + h);                     // свет у клиента уже свежий; новые — после отправки
        sp.clearOutside(mask, LIT, secs.size());
        for (int i = sp.lo(), iEnd = sp.hi(); i <= iEnd; i++) if (sp.has(i) && !rdy.get(i)) mask.clear(LIT + i);   // не загружен (или выгружен) — у игрока его нет (правило записи)
        List<Long> drop = new ArrayList<>();
        BitSet dropIdx = new BitSet();
        for (int i = mask.nextSetBit(0); i >= 0 && i < LIT; i = mask.nextSetBit(i + 1))
            if (!sp.has(i)) { drop.add(pos(c.getChunkX(), i + min, c.getChunkZ())); dropIdx.set(i); }
        for (int b = mask.nextSetBit(GENB); b >= 0 && b < GENB + LIT; b = mask.nextSetBit(b + 1)) {   // 0.59: метки вне окна — тоже op 4
            int i = b - GENB;
            if (sp.has(i)) continue;
            if (!dropIdx.get(i)) drop.add(pos(c.getChunkX(), i + min, c.getChunkZ()));
            mask.clear(b);
            mask.clear(GENR + i);
            mask.clear(SECR + i);
        }
        sp.clearOutside(mask, 0, secs.size());
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
        if (labels != null) sendLabels(p, c, labels);                       // op 21 — после op 4 и до op 2 столбца
        if (zoneReady != null) for (int i : zoneReady) genSecrets(p, g, c, i, mask);
        if (!add.isEmpty()) {
            writeCubes(p, c, add, true);
            for (int i : add) mask.set(i);
            sentCubes += add.size();
        }
        if (!cl) {
            int[] fl = skyFloor(p, st, c, sun);
            // S2: столбец с непустыми кубами — свет сразу за op 2 (клиент берёт его заранее, lightAhead: без второй сборки);
            // в общую пачку — только свет пустых кубов. op 16 — в пачке, после всего света (и этого столбца).
            LightBatch lb = add.isEmpty() ? null : BATCH.get();
            if (lb != null) BATCH.remove();
            try { sendLight(p, c, skipSky(p, c, mask, lit, fl[0]), sun); }   // 0.54: небо по умолчанию — без записей
            finally { if (lb != null) BATCH.set(lb); }
            sendFloor(p, st, c, fl[1]);                                     // op 16 — после op 11 столбца
        }
        // LIT — только после отправки: сбой выше оставит кубы без LIT, и heal их дошлёт (раньше биты ставились до
        // света, и после исключения в CubeLight куб считался отправленным навсегда)
        for (int i = rdy.nextSetBit(0); i >= 0; i = rdy.nextSetBit(i + 1)) mask.set(LIT + i);
        for (int h : halo) mask.set(HALO + h);
    }

    /** Тесты: update() с уже отправленной маской (как повторный update того же столбца). */
    static BitSet updateForTest(Player p, TallColumn c, int sy, int v, BitSet mask) {
        State st = new State();
        st.sy = sy;
        st.v = v;
        st.s = surface(p);
        st.instance = c.getInstance();
        update(p, st, c, mask);
        return mask;
    }

    /** Тесты: update() столбца игроку с окном sy ± v (состояние вне STATES); ответ — маска sent. */
    static BitSet updateForTest(Player p, TallColumn c, int sy, int v) {
        State st = new State();
        st.sy = sy;
        st.v = v;
        st.s = surface(p);
        st.instance = c.getInstance();
        BitSet mask = new BitSet();
        update(p, st, c, mask);
        return mask;
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
        if (clientLight(p)) return;                                     // 0.57: свет считает клиент
        State st = STATES.get(p.getUuid());
        if (st == null || st.sy == Integer.MIN_VALUE || !st.sent.containsKey(key(c.getChunkX(), c.getChunkZ()))) return;
        int min = c.getMinSection(), n = c.getSections().size();
        List<Integer> idx = new ArrayList<>();
        for (int i = Math.max(0, st.sy - st.v - min); i <= Math.min(n - 1, st.sy + st.v - min); i++) idx.add(i);
        boolean sun = c.getInstance().getCachedDimensionType().hasSkylight();
        BitSet hm = st.sent.get(key(c.getChunkX(), c.getChunkZ()));
        if (hm != null && haloOp(p)) {                                 // и кольцо ±1 за окном (0.56)
            int lo = st.sy - st.v - min - 1, hi = st.sy + st.v - min + 1;
            if (lo >= 0 && hm.get(HALO + lo)) idx.add(0, lo);
            if (hi < n && hm.get(HALO + hi)) idx.add(hi);
        }
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
        if (clientLight(p)) return;                                     // 0.57: свет считает клиент
        State st = STATES.get(p.getUuid());
        if (st == null || st.sy == Integer.MIN_VALUE || st.instance != c.getInstance()) return;
        BitSet mask = st.sent.get(key(c.getChunkX(), c.getChunkZ()));
        if (mask == null) return;
        List<Integer> idx = new ArrayList<>();
        for (int i = changed.nextSetBit(0); i >= 0 && i < LIT; i = changed.nextSetBit(i + 1)) if (mask.get(LIT + i) || mask.get(HALO + i)) idx.add(i);   // и кольцо ±1 (0.56)
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
        if (idx.isEmpty() || clientLight(p)) return;
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
        if (idx.isEmpty() || clientLight(p)) return;
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

    static boolean floorOp(Player p) { return FLOOR_ON && Handshake.coreAtLeast(p, FLOOR_CORE) && !clientLight(p); }

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

    // ───────────── 0.56: кольцо света ±1 и op 17 HEIGHTS ─────────────

    /** С какой версии ядро берёт свет кольца ±1 за окном и op 17. */
    static final int[] HALO_CORE = {0, 56, 0};
    static final byte OP_HEIGHTS = 17;
    /** -Dhoka.light.halo=false — кольцо выключено. */
    static final boolean HALO_ON = !"false".equalsIgnoreCase(System.getProperty("hoka.light.halo"));

    static boolean haloOp(Player p) { return HALO_ON && lightOp(p) && Handshake.coreAtLeast(p, HALO_CORE) && !clientLight(p); }

    static boolean heightsOp(Player p) { return Handshake.coreAtLeast(p, HALO_CORE); }

    // ───────────── 0.57: свет считает клиент ─────────────

    static final byte OP_HEIGHTS2 = 18, OP_LIGHT_MODE = 19;
    /** -Dhoka.light.client=false — ядру 0.57 свет по-прежнему шлёт сервер (op 19 с режимом 0). */
    static final boolean CLIENT_LIGHT_ON = !"false".equalsIgnoreCase(System.getProperty("hoka.light.client"));

    /** Ядро 0.57+ и свет на клиенте не выключен: свет не слать, кольцо ±1 — блоками, op 18 вместо op 17. */
    static boolean clientLight(Player p) { return CLIENT_LIGHT_ON && Handshake.coreAtLeast(p, Handshake.CLIENT_LIGHT_CORE); }

    /** На сколько кубов окно блоков шире окна op 5: 1 — кольцо для света на клиенте. */
    static int ext(Player p) { return clientLight(p) ? 1 : 0; }

    /** Отладка: столбцов op 18 разослано. */
    static volatile long heights2Sent;

    /** op 19 LIGHT_MODE ядру 0.57+: при входе в инстанс, до скелетов. Свет на клиенте — только на кубовой карте. */
    static void sendLightMode(Player p, Instance inst) {
        if (!Handshake.coreAtLeast(p, Handshake.CLIENT_LIGHT_CORE)) return;
        byte mode = (byte) (clientLight(p) && TallWorld.isCubes(inst) ? 1 : 0);
        p.sendPacket(new PluginMessagePacket(CHANNEL, new byte[]{OP_LIGHT_MODE, mode}));
    }

    /** Столбцы, у которых сменилась карта высот MOTION_BLOCKING (CubeColumn) — разослать op 17 в тике. */
    private static final java.util.Set<TallColumn> HEIGHTS_DIRTY = ConcurrentHashMap.newKeySet();
    /** Столбцов op 17 за тик не больше (массовая правка — остаток следующим тиком). */
    static final int HEIGHTS_PER_TICK = 256;
    /** Отладка: столбцов op 17 разослано. */
    static volatile long heightsSent;

    /** Высоты MOTION_BLOCKING столбца сменились (любой поток). Слать будет тик — только ядрам 0.56+. */
    static void heightsChanged(TallColumn c) {
        if (TallWorld.isCubes(c.getInstance())) HEIGHTS_DIRTY.add(c);
    }

    /**
     * Запись op 17 столбца: {cx, cz, 256 высот}. Высота — как значение ванильной карты MOTION_BLOCKING: Y первого
     * блока над верхним «блокирующим движение» (верх + 1); блоков нет — TallWorld.MIN_Y. Индекс z*16+x.
     * Для кубов не в памяти (ленивая карта) верх — оценка сверху (как HeightIndex), уточнится при загрузке.
     */
    static int[] heights(TallColumn c) {
        int[] e = new int[2 + 512];                                         // + skyTop+1 для op 18 (e[258..513])
        e[0] = c.getChunkX();
        e[1] = c.getChunkZ();
        HeightIndex h = c.st().heights;
        for (int i = 0; i < 256; i++) {
            int y = h.get(HeightIndex.Kind.MOTION, i & 15, i >> 4);
            e[2 + i] = y == HeightIndex.NONE ? TallWorld.MIN_Y : y + 1;
            int t = h.get(HeightIndex.Kind.SKY, i & 15, i >> 4);
            e[258 + i] = t == HeightIndex.NONE ? TallWorld.MIN_Y : t + 1;
        }
        return e;
    }

    /** op 17 HEIGHTS: varint n; n × (int cx, int cz, 256 × short высота). По 64 столбца в пакете. */
    static void writeHeights(Player p, List<int[]> cols) {
        if (clientLight(p)) {                                               // 0.57: op 18 (и небо)
            for (byte[] b : encodeHeights2(cols)) p.sendPacket(new PluginMessagePacket(CHANNEL, b));
            heights2Sent += cols.size();
            return;
        }
        for (int s = 0; s < cols.size(); s += 64) {
            List<int[]> part = cols.subList(s, Math.min(cols.size(), s + 64));
            p.sendPacket(new PluginMessagePacket(CHANNEL, NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, OP_HEIGHTS);
                buf.write(NetworkBuffer.VAR_INT, part.size());
                for (int[] e : part) {
                    buf.write(NetworkBuffer.INT, e[0]);
                    buf.write(NetworkBuffer.INT, e[1]);
                    for (int i = 2; i < 258; i++) buf.write(NetworkBuffer.SHORT, (short) e[i]);
                }
            })));
        }
        heightsSent += cols.size();
    }

    /** op 18 HEIGHTS2 (формат — в шапке класса): записи heights() по 64 столбца в пакете. */
    static List<byte[]> encodeHeights2(List<int[]> cols) {
        List<byte[]> out = new ArrayList<>();
        for (int s = 0; s < cols.size(); s += 64) {
            List<int[]> part = cols.subList(s, Math.min(cols.size(), s + 64));
            out.add(NetworkBuffer.makeArray(buf -> {
                buf.write(NetworkBuffer.BYTE, OP_HEIGHTS2);
                buf.write(NetworkBuffer.VAR_INT, part.size());
                for (int[] e : part) {
                    boolean mOne = true, sOne = true, same = true;
                    for (int i = 0; i < 256; i++) {
                        if (e[2 + i] != e[2]) mOne = false;
                        if (e[258 + i] != e[258]) sOne = false;
                        if (e[258 + i] != e[2 + i]) same = false;
                    }
                    int flags = (mOne ? 1 : 0) | (!same && sOne ? 2 : 0) | (same ? 4 : 0);
                    buf.write(NetworkBuffer.INT, e[0]);
                    buf.write(NetworkBuffer.INT, e[1]);
                    buf.write(NetworkBuffer.BYTE, (byte) flags);
                    if (mOne) buf.write(NetworkBuffer.SHORT, (short) e[2]);
                    else for (int i = 0; i < 256; i++) buf.write(NetworkBuffer.SHORT, (short) e[2 + i]);
                    if (same) continue;
                    if (sOne) buf.write(NetworkBuffer.SHORT, (short) e[258]);
                    else for (int i = 0; i < 256; i++) buf.write(NetworkBuffer.SHORT, (short) e[258 + i]);
                }
            }));
        }
        return out;
    }

    /** Тик: сменившиеся высоты — тем, кому столбец отправлен (ядро 0.56+); и вне окна (крышу построили выше). */
    private static void drainHeights() {
        if (HEIGHTS_DIRTY.isEmpty()) return;
        Map<Player, List<int[]>> by = null;
        int n = 0;
        for (var it = HEIGHTS_DIRTY.iterator(); it.hasNext() && n < HEIGHTS_PER_TICK; ) {
            TallColumn c = it.next();
            it.remove();
            if (!c.isLoaded()) continue;
            int[] e = null;
            for (Player p : receivers(c)) {
                if (!heightsOp(p)) continue;
                if (e == null) { e = heights(c); n++; }
                if (by == null) by = new HashMap<>();
                by.computeIfAbsent(p, k -> new ArrayList<>()).add(e);
            }
        }
        if (by == null) return;
        for (var en : by.entrySet()) {
            try { writeHeights(en.getKey(), en.getValue()); }
            catch (RuntimeException ex) { failed("op 17 игроку " + en.getKey().getUsername(), ex); }
        }
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
                State st = STATES.get(u);                                   // 0.59: и кубы-метки (хэш не сошёлся)
                int n = Math.min(4096, b.read(NetworkBuffer.VAR_INT));
                for (int k = 0; k < n; k++) {
                    long pos = b.read(NetworkBuffer.LONG);
                    int what = b.read(NetworkBuffer.BYTE);
                    if (m != null && m.wants.size() < 65536) m.wants.add(new long[]{pos, what});
                    if (st != null && (what & 1) != 0 && st.genWants.size() < 4096) st.genWants.add(pos);
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
                if (!c.ready(i)) { mask.clear(i); mask.clear(LIT + i); mask.clear(HALO + i); CubeCacheModel.wantIgnored++; continue; }
                if ((w[1] & 1) != 0) { if (mask.get(i)) { blocks.add(i); CubeCacheModel.wantBlocks++; } else CubeCacheModel.wantIgnored++; }
                if ((w[1] & 2) != 0) { if (mask.get(LIT + i) || mask.get(HALO + i)) { light.add(i); CubeCacheModel.wantLight++; } else CubeCacheModel.wantIgnored++; }
            }
            try {
                if (!blocks.isEmpty()) writeCubes(p, c, new ArrayList<>(blocks), false);
                if (!light.isEmpty() && !clientLight(p)) { List<Integer> li = new ArrayList<>(light); writeLight(p, c, full(c, li, sun), li, sun, false); }
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
    /**
     * Биты от HALO (0.56): куб кольца ±1 за окном, свет которого у клиента свежий (шлём и правки — relightCubes).
     * Клиент освещает грани края окна по слою соседа за окном; без кольца там оставался свет с прошлого раза.
     */
    private static final int HALO = 3 * LIT;

    /** Биты от GENB (0.59): куб ушёл клиенту меткой op 21 — клиент генерирует его сам (блоков сервер не слал). */
    private static final int GENB = 4 * LIT;
    /** Биты от GENX (0.59): куб этому игроку — только настоящими блоками (хэш разошёлся / клиент не смог). */
    private static final int GENX = 5 * LIT;
    /** Биты от SECR (0.59): секреты куба-метки (op 22) игроку досланы. */
    private static final int SECR = 6 * LIT;
    /** Биты от GENR (0.59): правку в куб-метку отменили, а куба у сервера нет — откатить op 2, когда загрузится. */
    private static final int GENR = 7 * LIT;

    // ───────────── 0.59: генерация нетронутых кубов на клиенте ─────────────

    /** Генератор для меток в этом столбце: генерация на клиенте у игрока включена, столбец ленивый, иначе null. */
    private static ink.hoka.odyssey.server.tall.gen.ModuleCubeGen genFor(Player p, State st, TallColumn c) {
        if (!(c instanceof CubeColumn cc) || !cc.isLazy()) return null;
        return ClientGen.active(p, st.instance);
    }

    /** Куб i — меткой: не тронут, игроку не велено «только блоками», генератор считает его непустым. */
    private static boolean genCube(ink.hoka.odyssey.server.tall.gen.ModuleCubeGen g, TallColumn c, int i, BitSet mask) {
        if (i < 0 || i >= LIT || mask.get(GENX + i) || ((CubeColumn) c).touched(i)) return false;
        return g.hasBlocks(c.getChunkX(), i + c.getMinSection(), c.getChunkZ());
    }

    /** Куб в зоне ±ZONE вокруг куба игрока (её сервер грузит и при генерации на клиенте). */
    private static boolean zone(Player p, int cx, int cy, int cz) {
        var pos = p.getPosition();
        int z = ClientGen.ZONE;
        return Math.abs(cx - (pos.blockX() >> 4)) <= z && Math.abs(cz - (pos.blockZ() >> 4)) <= z && Math.abs(cy - (pos.blockY() >> 4)) <= z;
    }

    /** Метка куба: однородный (индекс/генератор) — заливкой; в памяти сервера (не тронут, без секретов) — с хэшем. */
    private static ink.hoka.odyssey.gen.GenWire.Cube label(ink.hoka.odyssey.server.tall.gen.ModuleCubeGen g, TallColumn c, int i) {
        int cx = c.getChunkX(), cy = i + c.getMinSection(), cz = c.getChunkZ();
        int[] u = g.uniform(cx, cy, cz);
        if (u != null) return new ink.hoka.odyssey.gen.GenWire.Cube(cy, 0, u[0], u[1]);
        long h = 0;
        if (c.ready(i) && !g.mayHaveSecrets(cx, cy, cz)) {
            c.lockReadLock();
            try { if (c.ready(i)) h = c.cubeHash(i); } finally { c.unlockReadLock(); }
        }
        return new ink.hoka.odyssey.gen.GenWire.Cube(cy, h, -1, 0);
    }

    /** op 21 одного столбца. */
    private static void sendLabels(Player p, TallColumn c, List<ink.hoka.odyssey.gen.GenWire.Cube> labels) {
        byte[] b = ink.hoka.odyssey.gen.GenWire.encodeCubes(ClientGen.slot(p),
                List.of(new ink.hoka.odyssey.gen.GenWire.Col(c.getChunkX(), c.getChunkZ(), labels)));
        p.sendPacket(new PluginMessagePacket(CHANNEL, b));
        int hashed = 0, uni = 0;
        for (var l : labels) { if (l.hash() != 0) hashed++; if (l.uniformState() >= 0) uni++; }
        ClientGen.labelled(p, labels.size(), hashed, uni);
    }

    /** Секреты куба-метки зоны (op 22) — один раз (бит SECR). Генерирует куб заново с секретами (только если они возможны). */
    private static void genSecrets(Player p, ink.hoka.odyssey.server.tall.gen.ModuleCubeGen g, TallColumn c, int i, BitSet mask) {
        int cx = c.getChunkX(), cy = i + c.getMinSection(), cz = c.getChunkZ();
        if (mask.get(SECR + i) || !g.mayHaveSecrets(cx, cy, cz)) return;
        mask.set(SECR + i);
        try {
            var a = g.visible(cx, cy, cz, true);
            var sec = a.secretList();
            var data = a.dataList();
            if (sec.isEmpty() && data.isEmpty()) return;
            int[] idx = new int[sec.size()], st = new int[sec.size()];
            int k = 0;
            for (var e : sec.entrySet()) { idx[k] = e.getKey(); st[k++] = e.getValue(); }
            int[] di = new int[data.size()];
            String[] sn = new String[data.size()];
            k = 0;
            for (var e : data.entrySet()) { di[k] = e.getKey(); sn[k++] = e.getValue(); }
            byte[] b = ink.hoka.odyssey.gen.GenWire.encodeSecrets(List.of(new ink.hoka.odyssey.gen.GenWire.Secret(pos(cx, cy, cz), idx, st, di, sn)));
            p.sendPacket(new PluginMessagePacket(CHANNEL, b));
            ClientGen.secretsSent.incrementAndGet();
        } catch (RuntimeException ex) {
            mask.clear(SECR + i);
            failed("секреты куба " + cx + " " + cy + " " + cz, ex);
        }
    }

    /** Нетронутые кубы, ставшие тронутыми (CubeColumn.firstTouch, любой поток): тик дошлёт op 2 тем, у кого они меткой. */
    private record Touch(CubeColumn c, int i) {}

    private static final java.util.concurrent.ConcurrentLinkedQueue<Touch> TOUCHED = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Отладка: кубов-меток дослано op 2 после первой правки; правдой по WANT/выборке. */
    static volatile long genTouched, genForced;

    static void firstTouch(CubeColumn c, int i) {
        if (STATES.isEmpty() || c.getInstance() == null || !TallWorld.isCubes(c.getInstance())) return;
        TOUCHED.add(new Touch(c, i));
    }

    /** Тик: первая правка куба-метки — op 2 каждому, у кого он меткой (один раз за жизнь куба). */
    private static void drainTouched() {
        for (Touch t; (t = TOUCHED.poll()) != null; ) {
            CubeColumn c = t.c();
            int i = t.i();
            if (!c.isLoaded() || c.getInstance() == null) continue;
            long k = key(c.getChunkX(), c.getChunkZ());
            for (Player p : receivers(c)) {
                State st = STATES.get(p.getUuid());
                if (st == null || st.instance != c.getInstance()) continue;
                BitSet mask = st.sent.get(k);
                if (mask == null) continue;
                synchronized (st) {
                    if (!mask.get(GENB + i)) continue;
                    mask.clear(GENB + i);
                    mask.clear(GENR + i);
                    mask.clear(SECR + i);
                    try {
                        if (c.ready(i)) {
                            writeCubes(p, c, List.of(i), false);
                            if (c.getSections().get(i).blockPalette().count() > 0) mask.set(i); else mask.clear(i);
                            mask.set(LIT + i);
                            genTouched++;
                        } else mask.clear(LIT + i);                        // дошлёт heal/drainReady
                    } catch (RuntimeException ex) {
                        mask.clear(LIT + i);
                        failed("op 2 первой правки " + c.getChunkX() + " " + c.getChunkZ(), ex);
                    }
                }
            }
        }
    }

    /** Куб pos этому игроку — только блоками (GENX): метку снять, в памяти — op 2 сейчас, иначе загрузить (дошлёт drainReady). */
    static void forceReal(Player p, long pos) {
        State st = STATES.get(p.getUuid());
        if (st == null || st.instance == null || st.sy == Integer.MIN_VALUE) return;
        int x = posX(pos), y = posY(pos), z = posZ(pos);
        BitSet mask = st.sent.get(key(x, z));
        if (mask == null || !(st.instance.getChunk(x, z) instanceof TallColumn c)) return;
        int i = y - c.getMinSection();
        if (i < 0 || i >= LIT) return;
        synchronized (st) {
            mask.set(GENX + i);
            if (!mask.get(GENB + i)) return;
            mask.clear(GENB + i);
            mask.clear(GENR + i);
            mask.clear(LIT + i);
            genForced++;
            if (c.ready(i)) {
                try {
                    writeCubes(p, c, List.of(i), false);
                    if (c.getSections().get(i).blockPalette().count() > 0) mask.set(i);
                    mask.set(LIT + i);
                } catch (RuntimeException ex) { failed("op 2 по расхождению", ex); }
            } else c.request(i, prio(p, x, y, z));
        }
    }

    /** Тик: WANT клиента по кубам-меткам — хэш op 21 не сошёлся (или не сгенерировал): правду op 2 и расхождение. */
    private static void genWants(Player p, State st) {
        if (st.genWants.isEmpty()) return;
        int n = 0;
        for (Long pos; n < 512 && (pos = st.genWants.poll()) != null; n++) {
            BitSet mask = st.sent.get(key(posX(pos), posZ(pos)));
            if (mask == null) continue;
            int i = posY(pos) - (TallWorld.MIN_Y >> 4);
            if (i < 0 || i >= LIT || !mask.get(GENB + i)) continue;
            forceReal(p, pos);
            ClientGen.mismatch(p, pos, "хэш op 21");
        }
    }

    /** Генерация игроку выключена (op 20 slot 0): снять метки — heal/update перешлют окно op 2 (LIT снят). */
    static void genOff(Player p) {
        State st = STATES.get(p.getUuid());
        if (st == null) return;
        synchronized (st) {
            for (BitSet mask : st.sent.values()) {
                for (int b = mask.nextSetBit(GENB); b >= 0 && b < GENB + LIT; b = mask.nextSetBit(b + 1)) mask.clear(LIT + (b - GENB));
                mask.clear(GENB, GENB + LIT);
                mask.clear(SECR, SECR + LIT);
                mask.clear(GENR, GENR + LIT);
            }
        }
        st.heal = 19;                                                       // полная подстраховка — в следующий тик
    }

    /** До n кубов-меток игрока (для op 23), ближние столбцы первыми. */
    static long[] genPositions(Player p, int n) {
        State st = STATES.get(p.getUuid());
        if (st == null) return new long[0];
        int pcx = p.getPosition().blockX() >> 4, pcz = p.getPosition().blockZ() >> 4;
        long[] keys = new long[st.sent.size()];
        int m = 0;
        for (Long k : st.sent.keySet()) { if (m == keys.length) break; keys[m++] = k; }
        long[] ord = order(keys, m, pcx, pcz);
        List<Long> out = new ArrayList<>();
        int min = TallWorld.MIN_Y >> 4;
        for (long k : ord) {
            BitSet mask = st.sent.get(k);
            if (mask == null) continue;
            for (int b = mask.nextSetBit(GENB); b >= 0 && b < GENB + LIT && out.size() < n; b = mask.nextSetBit(b + 1))
                out.add(pos((int) (k >> 32), b - GENB + min, (int) k));
            if (out.size() >= n) break;
        }
        long[] r = new long[out.size()];
        for (int j = 0; j < r.length; j++) r[j] = out.get(j);
        return r;
    }

    /** Тесты: маска столбца игрока (или null). */
    static BitSet maskForTest(Player p, int cx, int cz) {
        State st = STATES.get(p.getUuid());
        return st == null ? null : st.sent.get(key(cx, cz));
    }

    static int genbBit(int i) { return GENB + i; }

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
                + "; сдвиг окна: столбцов " + shiftCols + ", последний " + lastShiftCols + " за " + lastShiftTicks + " тик., прервано " + shiftRestarts
                + "; op 17 столбцов " + heightsSent + ", op 18 столбцов " + heights2Sent + (CLIENT_LIGHT_ON ? "" : " (свет на клиенте ВЫКЛ)")
                + "; полоса поверхности: обходов столбцов " + surfaceCols + ", кубов вне окна " + surfaceCubes + (SURFACE_MAX == 0 ? " (ВЫКЛ)" : " (до ±" + SURFACE_MAX + ")")
                + "; правило записи: отменено " + cancelled + ", кубов ресинк " + resyncCubes
                + "; кубов загружено " + CubeStore.loads.get() + ", в очереди " + CubeStore.queued()
                + ", ошибок чтения " + CubeStore.loadErrors.get() + ", запись в незагруженный " + CubeStore.violations.get()
                + "; " + CubeCacheModel.stats()
                + "; " + ClientGen.stats() + ", op 2 после первой правки " + genTouched + ", правдой по расхождению " + genForced;
    }
}
