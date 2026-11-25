package ink.hoka.odyssey.client;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Кэш кубов (ядро 0.51): кубы, ушедшие из обзора (op 4, Forget, закрытие мира, повторный скелет), остаются здесь —
 * секция как есть (палитры, без копии) и последний свет (ServerLight.Cube). Сервер шлёт ссылку «куб X версии H»
 * (op 13 / op 14): совпал хэш содержимого (CubeHash) — куб берётся отсюда сразу, иначе WANT (+1 RTT). Устаревший
 * куб показать нельзя: хэш считается по тому, что лежит в кэше.
 * <p>Ключ — сервер + карта (op 12 mapKey) + столбец; LRU по столбцам с лимитом памяти (CubeCacheSetting). Вытесненный
 * столбец текущего сервера — серверу EVICT. Только главный поток (кроме {@link #current}).</p>
 */
public final class CubeCache {

    private CubeCache() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");

    /** Пространство имён кэша: адрес сервера + ключ карты. */
    public record Ns(String server, long map) {}

    private record ColKey(Ns ns, int x, int z) {}

    /** Куб в кэше: блоки (секция мира, своя — ни с кем не делится) и/или свет. */
    private static final class Ent {
        LevelChunkSection sec;
        PalettedContainerFactory f;
        long h;                                                         // 0 — ещё не посчитан
        int bytes;
        ServerLight.Cube light;
        long l;
        int lbytes;
    }

    private static final class Col {
        final Int2ObjectOpenHashMap<Ent> cubes = new Int2ObjectOpenHashMap<>();
        long bytes;
    }

    private static final LinkedHashMap<ColKey, Col> LRU = new LinkedHashMap<>(256, 0.75f, true);
    private static long bytes;
    private static int cubesBlocks, cubesLight;

    /** Пространство имён для новых скелетов (op 12, по порядку пакетов на главном потоке); null — сервер без кэша. */
    private static volatile Ns current;
    /** Метка каждого живого столбца: под каким ns он пришёл (столбцы прежней карты не попадут в кэш новой). */
    private static final WeakHashMap<LevelChunk, Ns> LABELS = new WeakHashMap<>();
    /** Мир, столбцы которого помечены (смена — унести всё в кэш, см. sync). */
    private static ClientLevel lastLevel;

    /** Промахи: pos → что ждём (WANT). */
    private static final class Want { int what; long sentAt; int tries; }
    private static final Long2ObjectOpenHashMap<Want> WAITING = new Long2ObjectOpenHashMap<>();
    /** EVICT серверу: ключ карты → столбцы (только текущего сервера). */
    private static final Map<Long, List<long[]>> EVICT = new java.util.HashMap<>();
    private static long lastEvict;
    private static boolean haveDue;
    /** Отложенный HAVE после смены настройки (мс; 0 — нет). */
    private static long haveAt;
    private static boolean autoOff, selfOk = CubeHash.selfTest();

    public static long hits, missNone, missHash, lightHits, lightMiss, wants, evicted, captured, refs, lightRefs;

    static final int WANT_BLOCKS = 1, WANT_LIGHT = 2;
    static final int OP_WANT = 3, OP_HAVE = 4, OP_EVICT = 5, OP_REPORT = 6;
    /** Предел C2S-пакета (ServerboundCustomPayload ≤ 32767). */
    private static final int MAX_C2S = 30_000;

    static Ns current() { return current; }

    /** Кэш включён: настройка > 0, сервер дал ключ карты, хэш совпал с эталоном, не выключен автоматически. */
    static boolean on(Ns ns) {
        return ns != null && ns.map() != 0 && budget() > 0 && selfOk && !autoOff;
    }

    static long budget() { return (long) CubeCacheSetting.get() << 20; }

    private static String server() {
        var sd = Minecraft.getInstance().getCurrentServer();
        return sd == null || sd.ip == null ? "local" : sd.ip.toLowerCase(java.util.Locale.ROOT);
    }

    // ───────────── op 12 ─────────────

    /** op 12 CACHE (главный поток, по порядку пакетов): ключ карты; HAVE — что у нас есть для неё. */
    static void onKey(long map) {
        current = map == 0 ? new Ns(server(), 0) : new Ns(server(), map);
        haveDue = true;
        sendHave();
    }

    /** Канал wc объявлен сервером — отложенный HAVE. */
    public static void channelReady() { if (haveDue) sendHave(); }

    /** Настройку сменили — сообщить серверу (бюджет 0 — он забудет модель); лишнее — вытеснить. */
    public static void settingChanged() {
        trim();
        haveAt = System.currentTimeMillis() + 500;                     // ползунок зовёт на каждый шаг — серверу одно значение
    }

    // ───────────── метки и захват ─────────────

    /** Скелет пришёл (SparseLevelChunkMixin): метка столбца — текущее пространство имён. */
    public static void label(LevelChunk c) {
        if (YardColumns.isYard(c.getPos().x)) return;
        Ns ns = current;
        if (ns != null) LABELS.put(c, ns); else LABELS.remove(c);
    }

    /** Повторный скелет живого столбца: его прежние кубы — в кэш (до замены). */
    public static void captureSection(LevelChunk c, int idx, LevelChunkSection old) {
        Ns ns = LABELS.get(c);
        if (!on(ns)) return;
        putBlocks(ns, c.getPos().x, c.getSectionYFromSectionIndex(idx), c.getPos().z, old, c.getLevel().palettedContainerFactory());
        trim();
    }

    /** op 4: куб уходит из окна — секция и последний свет в кэш. */
    static void captureCube(ClientLevel level, LevelChunk c, int x, int y, int z, LevelChunkSection old) {
        ServerLight.Cube l = ServerLight.takeLatest(level, SectionPos.asLong(x, y, z));
        Ns ns = LABELS.get(c);
        if (!on(ns) || YardColumns.isYard(x)) return;
        putBlocks(ns, x, y, z, old, level.palettedContainerFactory());
        if (l != null) putLight(ns, x, y, z, l);
        trim();
    }

    /** Столбец выгружен (ClientLevel.unload: Forget, вытеснение из хранилища): все его кубы и свет — в кэш. */
    public static void captureColumn(ClientLevel level, LevelChunk c) {
        Ns ns = LABELS.remove(c);
        int x = c.getPos().x, z = c.getPos().z;
        var light = ServerLight.takeLatestColumn(level, x, z);
        if (!on(ns) || YardColumns.isYard(x)) return;
        LevelChunkSection[] secs = c.getSections();
        var f = level.palettedContainerFactory();
        for (int i = 0; i < secs.length; i++) putBlocks(ns, x, c.getSectionYFromSectionIndex(i), z, secs[i], f);
        if (light != null) for (var e : light.int2ObjectEntrySet()) putLight(ns, x, e.getIntKey(), z, e.getValue());
        trim();
    }

    /**
     * Мир сменился (новый ClientLevel, выход): столбцы прежнего — в кэш. Звать на главном потоке до того, как новый мир
     * начнёт получать свет/кубы (CubeReceiver.handle, кадр, DISCONNECT).
     */
    public static void sync(ClientLevel now) {
        if (now == lastLevel) return;
        ClientLevel old = lastLevel;
        lastLevel = now;
        if (old == null) return;
        int n = 0;
        List<LevelChunk> cols = new ArrayList<>();
        for (var e : LABELS.entrySet()) if (e.getKey().getLevel() == old) cols.add(e.getKey());
        for (LevelChunk c : cols) {
            var live = old.getChunkSource().getChunk(c.getPos().x, c.getPos().z, ChunkStatus.FULL, false);
            if (live == c) { captureColumn(old, c); n++; }
            else LABELS.remove(c);
        }
        WAITING.clear();                                               // кубы прежнего мира в очереди CubeQueue выбросит сама
        if (n > 0) LOG.info("кэш кубов: мир закрыт, столбцов в кэш {}, всего {}", n, debugLine());
    }

    private static Col col(Ns ns, int x, int z, boolean create) {
        ColKey k = new ColKey(ns, x, z);
        Col c = LRU.get(k);
        if (c == null && create) LRU.put(k, c = new Col());
        return c;
    }

    /** Блоки куба — в кэш (своя секция с блоками, не однородная). */
    private static void putBlocks(Ns ns, int x, int y, int z, LevelChunkSection s, PalettedContainerFactory f) {
        if (s == null || s instanceof SparseSections.Shared || s.hasOnlyAir()) return;
        if (s.getStates().getSerializedSize() <= 8) return;             // одно значение: сервер шлёт такие целиком (~15 Б)
        Col c = col(ns, x, z, true);
        Ent e = c.cubes.get(y);
        if (e == null) c.cubes.put(y, e = new Ent());
        if (e.sec != null) { c.bytes -= e.bytes; bytes -= e.bytes; cubesBlocks--; }
        e.sec = s;
        e.f = f;
        e.h = 0;
        e.bytes = s.getSerializedSize() + 512;                      // объекты палитр/хранилища сверх сетевой формы
        c.bytes += e.bytes;
        bytes += e.bytes;
        cubesBlocks++;
        captured++;
    }

    private static void putLight(Ns ns, int x, int y, int z, ServerLight.Cube l) {
        if (shortLight(l)) return;                                      // запись ≤ 24 Б сервер шлёт целиком — не держать
        Col c = col(ns, x, z, true);
        Ent e = c.cubes.get(y);
        if (e == null) c.cubes.put(y, e = new Ent());
        if (e.light != null) { c.bytes -= e.lbytes; bytes -= e.lbytes; cubesLight--; }
        e.light = l;
        e.l = 0;
        e.lbytes = (l.sd() == null ? 0 : l.sd().length) + (l.bd() == null ? 0 : l.bd().length) + 48;
        c.bytes += e.lbytes;
        bytes += e.lbytes;
        cubesLight++;
    }

    /** Свет, который сервер ссылкой не шлёт (LightOp.REF_MIN): небо 0/15/ровная формула и блоки «всё 0». */
    private static boolean shortLight(ServerLight.Cube l) {
        if (l.blk() == 3) return false;
        if (l.sky() == 1 || l.sky() == 2) return true;
        if (l.sky() != 0 || l.sd() == null) return false;
        byte d = l.sd()[0];
        for (byte b : l.sd()) if (b != d) return false;
        return true;
    }

    /** Лимит памяти: старейшие столбцы — прочь (текущего сервера — EVICT). */
    private static void trim() {
        long max = budget();
        if (autoOff || !selfOk) max = 0;
        if (bytes <= max) return;
        String srv = current == null ? null : current.server();
        for (Iterator<Map.Entry<ColKey, Col>> it = LRU.entrySet().iterator(); it.hasNext() && bytes > max; ) {
            var en = it.next();
            Col c = en.getValue();
            dropCol(c);
            it.remove();
            evicted++;
            ColKey k = en.getKey();
            if (k.ns().server().equals(srv) && k.ns().map() != 0)
                EVICT.computeIfAbsent(k.ns().map(), m -> new ArrayList<>()).add(new long[]{k.x(), k.z()});
        }
    }

    private static void dropCol(Col c) {
        bytes -= c.bytes;
        for (Ent e : c.cubes.values()) { if (e.sec != null) cubesBlocks--; if (e.light != null) cubesLight--; }
    }

    // ───────────── ссылки op 13 / op 14 ─────────────

    /**
     * op 13: секция куба pos версии h — из кэша (вынимается из него) или null (промах: WANT). f — фабрика палитр мира
     * (другая — секция пересобирается через сетевую форму).
     */
    static LevelChunkSection takeBlocks(Ns ns, long pos, long h, PalettedContainerFactory f) {
        refs++;
        int x = SectionPos.x(pos), y = SectionPos.y(pos), z = SectionPos.z(pos);
        Col c = ns == null ? null : col(ns, x, z, false);
        Ent e = c == null ? null : c.cubes.get(y);
        if (e == null || e.sec == null) { missNone++; want(pos, WANT_BLOCKS); return null; }
        LevelChunkSection s = e.sec;
        PalettedContainerFactory ef = e.f;
        long eh = e.h;
        try { if (eh == 0) eh = CubeHash.of(s); }
        catch (RuntimeException ex) { eh = -1; }
        // блоки из кэша вынимаем в любом случае: совпало — в мир, нет — устарели
        c.bytes -= e.bytes;
        bytes -= e.bytes;
        cubesBlocks--;
        e.sec = null;
        e.f = null;
        if (e.light == null) removeEnt(ns, x, z, c, y);
        winRefs++;
        if (eh != h) { missHash++; winMiss++; want(pos, WANT_BLOCKS); autoCheck(); return null; }
        hits++;
        if (winRefs >= 200 && !autoCheck()) resets = 0;                // окно без беды — счёт сбросов заново
        unwait(pos, WANT_BLOCKS);
        if (ef == f || f == null) return s;
        return rebuild(s, f);
    }

    /** Секция под другую фабрику палитр (другой мир того же сервера): через сетевую форму. */
    private static LevelChunkSection rebuild(LevelChunkSection s, PalettedContainerFactory f) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(s.getSerializedSize()));
        s.write(buf);
        LevelChunkSection n = new LevelChunkSection(f);
        n.read(buf);
        return n;
    }

    /** 0.52: свет куба из кэша, не вынимая (заранее, для слоя, пока ссылка op 14 ждёт в очереди); null — нет/другой. */
    static ServerLight.Cube peekLight(Ns ns, long pos, long l) {
        if (ns == null) return null;
        Col c = col(ns, SectionPos.x(pos), SectionPos.z(pos), false);
        Ent e = c == null ? null : c.cubes.get(SectionPos.y(pos));
        if (e == null || e.light == null) return null;
        if (e.l == 0) e.l = CubeHash.light(e.light);
        return e.l == l ? e.light : null;
    }

    /** op 14: свет куба pos версии l — из кэша, иначе живой (тот же уже у мира), иначе null (WANT). */
    static ServerLight.Cube takeLight(ClientLevel level, Ns ns, long pos, long l) {
        lightRefs++;
        int x = SectionPos.x(pos), y = SectionPos.y(pos), z = SectionPos.z(pos);
        Col c = ns == null ? null : col(ns, x, z, false);
        Ent e = c == null ? null : c.cubes.get(y);
        if (e != null && e.light != null) {
            ServerLight.Cube cube = e.light;
            if (e.l == 0) e.l = CubeHash.light(cube);
            long el = e.l;
            c.bytes -= e.lbytes;
            bytes -= e.lbytes;
            cubesLight--;
            e.light = null;
            if (e.sec == null) removeEnt(ns, x, z, c, y);
            if (el == l) { lightHits++; unwait(pos, WANT_LIGHT); return cube; }
        }
        ServerLight.Cube live = ServerLight.latest(level, pos);
        if (live != null && CubeHash.light(live) == l) { lightHits++; unwait(pos, WANT_LIGHT); return live; }
        lightMiss++;
        want(pos, WANT_LIGHT);
        return null;
    }

    private static void removeEnt(Ns ns, int x, int z, Col c, int y) {
        c.cubes.remove(y);
        if (c.cubes.isEmpty()) LRU.remove(new ColKey(ns, x, z));
    }

    /** Пришёл куб целиком (op 2): кэшированные блоки этой позиции — прочь, ждать нечего. */
    static void gotBlocks(Ns ns, long pos) {
        unwait(pos, WANT_BLOCKS);
        dropPart(ns, pos, true);
    }

    /** Пришёл свет целиком (op 11). */
    static void gotLight(Ns ns, long pos) {
        unwait(pos, WANT_LIGHT);
        dropPart(ns, pos, false);
    }

    private static void dropPart(Ns ns, long pos, boolean blocks) {
        if (ns == null || LRU.isEmpty()) return;
        int x = SectionPos.x(pos), y = SectionPos.y(pos), z = SectionPos.z(pos);
        ColKey k = new ColKey(ns, x, z);
        Col c = LRU.get(k);
        Ent e = c == null ? null : c.cubes.get(y);
        if (e == null) return;
        if (blocks && e.sec != null) { c.bytes -= e.bytes; bytes -= e.bytes; cubesBlocks--; e.sec = null; e.f = null; }
        if (!blocks && e.light != null) { c.bytes -= e.lbytes; bytes -= e.lbytes; cubesLight--; e.light = null; }
        if (e.sec == null && e.light == null) removeEnt(ns, x, z, c, y);
    }

    /** Автовыключение: после 200 ссылок промахов по хэшу больше половины — хэши клиента и сервера разошлись. */
    private static boolean autoCheck() {
        if (autoOff || winRefs < 200) return false;
        boolean bad = winMiss * 2 > winRefs;
        winRefs = winMiss = 0;
        if (!bad) return false;
        Ns ns = current;
        // карту перегенерировали / сменили генератор при том же ключе — весь кэш карты устарел: выбросить его и модель
        // сервера (HAVE 0, затем HAVE с бюджетом). Три раза подряд — хэши клиента и сервера разошлись: выключить.
        if (++resets >= 3) {
            autoOff = true;
            LOG.warn("кэш кубов: промахов по хэшу больше половины ссылок {} раза подряд — хэши разошлись с сервером, кэш выключен до перезапуска ({})", resets, debugLine());
            trim();
        } else {
            LOG.warn("кэш кубов: промахов по хэшу больше половины — кэш карты устарел, сбрасываю ({})", debugLine());
            for (var it = LRU.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                if (!en.getKey().ns().equals(ns)) continue;
                dropCol(en.getValue());
                it.remove();
            }
            if (ns != null && ClientPlayNetworking.canSend(Net.WC.TYPE))
                send(buf -> { buf.writeByte(OP_HAVE); buf.writeLong(ns.map()); buf.writeVarInt(0); buf.writeVarInt(0); });   // сервер забудет модель
        }
        haveDue = true;
        sendHave();
        return true;
    }

    /** Окно автопроверки: ссылок и промахов по хэшу с прошлой проверки; сбросов кэша подряд. */
    private static int winRefs, winMiss, resets;

    // ───────────── ожидание (WANT) ─────────────

    static void want(long pos, int what) {
        Want w = WAITING.get(pos);
        if (w == null) WAITING.put(pos, w = new Want());
        if ((w.what & what) != what) { w.what |= what; w.sentAt = 0; w.tries = 0; }
    }

    static void unwait(long pos, int what) {
        if (WAITING.isEmpty()) return;
        Want w = WAITING.get(pos);
        if (w == null) return;
        w.what &= ~what;
        if (w.what == 0) WAITING.remove(pos);
    }

    /** op 4 / столбец сменился: ждать нечего (сервер куб и так не пришлёт). */
    static void unwaitAll(long pos) { if (!WAITING.isEmpty()) WAITING.remove(pos); }

    public static void columnChanged(int x, int z) {
        if (WAITING.isEmpty()) return;
        WAITING.long2ObjectEntrySet().removeIf(e -> SectionPos.x(e.getLongKey()) == x && SectionPos.z(e.getLongKey()) == z);
    }

    /** Блоки куба ждём от сервера после промаха (CubeFreezeMixin: туда не двигаться). */
    public static boolean waiting(int x, int y, int z) {
        if (WAITING.isEmpty()) return false;
        Want w = WAITING.get(SectionPos.asLong(x, y, z));
        return w != null && (w.what & WANT_BLOCKS) != 0;
    }

    // ───────────── каждый кадр: WANT, EVICT ─────────────

    /** Кадр (FrameWatchMixin, после CubeQueue.run): WANT — сразу, повтор через 2 с; EVICT — пачкой раз в 250 мс. */
    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        sync(mc.level);
        if (!WAITING.isEmpty()) flushWants(mc.level);
        long now = System.currentTimeMillis();
        if (haveAt != 0 && now >= haveAt) { haveAt = 0; haveDue = true; sendHave(); }
        if (!EVICT.isEmpty() && now - lastEvict >= 250) { lastEvict = now; flushEvict(); }
    }

    private static void flushWants(ClientLevel level) {
        if (!ClientPlayNetworking.canSend(Net.WC.TYPE)) return;
        long now = System.nanoTime();
        List<long[]> out = new ArrayList<>();
        for (var it = WAITING.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            var en = it.next();
            Want w = en.getValue();
            // не бросать, пока столбец у нас: брошенный WANT = дыра навсегда (сервер считает куб отправленным).
            // Ответ (op 2/op 11), op 4, новый скелет или Forget снимают ожидание сами; повтор 2, 4, 8, 16 с.
            if (w.sentAt != 0 && now - w.sentAt < (2_000_000_000L << Math.min(3, Math.max(0, w.tries - 1)))) continue;
            if (level == null || level.getChunkSource().getChunk(SectionPos.x(en.getLongKey()), SectionPos.z(en.getLongKey()), ChunkStatus.FULL, false) == null) { it.remove(); continue; }
            // только свет (пустой куб ушёл из окна без op 4 — сервер не ответит): после 6 попыток — L2/L3, дошлёт при возврате
            if (w.what == WANT_LIGHT && w.tries >= 6) { it.remove(); continue; }
            w.sentAt = now;
            w.tries++;
            out.add(new long[]{en.getLongKey(), w.what});
        }
        for (int s = 0; s < out.size(); s += 2000) {
            List<long[]> part = out.subList(s, Math.min(out.size(), s + 2000));
            send(buf -> {
                buf.writeByte(OP_WANT);
                buf.writeVarInt(part.size());
                for (long[] w : part) { buf.writeLong(w[0]); buf.writeByte((int) w[1]); }
            });
            wants += part.size();
        }
    }

    private static void flushEvict() {
        if (!ClientPlayNetworking.canSend(Net.WC.TYPE)) { EVICT.clear(); return; }
        for (var e : EVICT.entrySet()) {
            List<long[]> l = e.getValue();
            for (int s = 0; s < l.size(); s += 3000) {
                List<long[]> part = l.subList(s, Math.min(l.size(), s + 3000));
                send(buf -> {
                    buf.writeByte(OP_EVICT);
                    buf.writeLong(e.getKey());
                    buf.writeVarInt(part.size());
                    for (long[] c : part) { buf.writeInt((int) c[0]); buf.writeInt((int) c[1]); }
                });
            }
        }
        EVICT.clear();
    }

    /**
     * HAVE: бюджет и до 2048 ближайших к игроку столбцов текущей карты из кэша (маски блоков и света). Сервер засевает
     * ими свою модель (после переподключения / перезапуска сервера).
     */
    private static void sendHave() {
        Ns ns = current;
        if (ns == null || !ClientPlayNetworking.canSend(Net.WC.TYPE)) return;
        haveDue = false;
        int mb = budget() > 0 && selfOk && !autoOff ? CubeCacheSetting.get() : 0;   // бюджет — независимо от карты (лобби без кэша не стирает модель)
        var p = Minecraft.getInstance().player;
        int px = p == null ? 0 : p.chunkPosition().x, pz = p == null ? 0 : p.chunkPosition().z;
        List<Map.Entry<ColKey, Col>> cols = new ArrayList<>();
        if (on(ns)) for (var e : LRU.entrySet()) if (e.getKey().ns().equals(ns)) cols.add(e);
        cols.sort(java.util.Comparator.comparingInt(e -> Math.max(Math.abs(e.getKey().x() - px), Math.abs(e.getKey().z() - pz))));
        if (cols.size() > 2048) cols = new ArrayList<>(cols.subList(0, 2048));
        java.util.Collections.reverse(cols);                            // модель сервера — LRU: ближние последними (вытесняются позже)
        List<byte[]> recs = new ArrayList<>();
        for (var e : cols) {
            Col c = e.getValue();
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int y : c.cubes.keySet()) { lo = Math.min(lo, y); hi = Math.max(hi, y); }
            if (lo > hi) continue;
            int len = hi - lo + 1;
            byte[] bm = new byte[(len + 7) >> 3], lm = new byte[(len + 7) >> 3];
            for (var ce : c.cubes.int2ObjectEntrySet()) {
                int k = ce.getIntKey() - lo;
                if (ce.getValue().sec != null) bm[k >> 3] |= (byte) (1 << (k & 7));
                if (ce.getValue().light != null) lm[k >> 3] |= (byte) (1 << (k & 7));
            }
            FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer(16 + bm.length * 2));
            b.writeInt(e.getKey().x());
            b.writeInt(e.getKey().z());
            b.writeInt(lo);
            b.writeVarInt(len);
            b.writeBytes(bm);
            b.writeBytes(lm);
            byte[] r = new byte[b.readableBytes()];
            b.readBytes(r);
            recs.add(r);
        }
        int s = 0;
        do {                                                            // хоть один пакет — с бюджетом
            int from = s, size = 20;
            while (s < recs.size() && size + recs.get(s).length < MAX_C2S) size += recs.get(s++).length;
            final int to = s;
            final List<byte[]> all = recs;
            send(buf -> {
                buf.writeByte(OP_HAVE);
                buf.writeLong(ns.map());
                buf.writeVarInt(mb);
                buf.writeVarInt(to - from);
                for (int k = from; k < to; k++) buf.writeBytes(all.get(k));
            });
        } while (s < recs.size());
    }

    /** op 15 HASH_CHECK (отладка, /hocubes cachecheck): сверить живые секции с версиями сервера — ответ op 6. */
    static void hashCheck(ClientLevel level, long[] pos, long[] hs) {
        int bad = 0, checked = 0;
        List<Long> worst = new ArrayList<>();
        for (int i = 0; i < pos.length; i++) {
            int x = SectionPos.x(pos[i]), y = SectionPos.y(pos[i]), z = SectionPos.z(pos[i]);
            LevelChunk c = (LevelChunk) level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            int idx = level.getSectionIndexFromSectionY(y);
            if (c == null || idx < 0 || idx >= c.getSections().length) continue;
            checked++;
            long h;
            try { h = CubeHash.of(c.getSections()[idx]); } catch (RuntimeException e) { h = -1; }
            if (h != hs[i]) { bad++; if (worst.size() < 16) worst.add(pos[i]); }
        }
        final int fc = checked, fb = bad;
        send(buf -> {
            buf.writeByte(OP_REPORT);
            buf.writeVarInt(fc);
            buf.writeVarInt(fb);
            buf.writeVarInt(worst.size());
            for (long p : worst) buf.writeLong(p);
        });
        LOG.info("кэш кубов: сверка хэшей — проверено {}, расхождений {}", checked, bad);
    }

    private interface W { void write(FriendlyByteBuf b); }

    private static void send(W w) {
        if (!ClientPlayNetworking.canSend(Net.WC.TYPE)) return;
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer(64));
        w.write(b);
        byte[] d = new byte[b.readableBytes()];
        b.readBytes(d);
        ClientPlayNetworking.send(new Net.WC(d));
    }

    /** Выход с сервера: новое соединение — HAVE заново (кэш в памяти остаётся). */
    public static void disconnected() {
        current = null;
        haveDue = false;
        haveAt = 0;
        winRefs = winMiss = 0;
        WAITING.clear();
        EVICT.clear();
    }

    // ───────────── отладка ─────────────

    /** Строка F3 (null — кэш не используется). */
    public static String debugLine() {
        Ns ns = current;
        if (ns == null && LRU.isEmpty()) return null;
        String key = ns == null || ns.map() == 0 ? "—" : Long.toHexString(ns.map()).substring(0, Math.min(4, Long.toHexString(ns.map()).length())) + "…";
        return "Hoka кэш: ns " + key + (autoOff ? " ВЫКЛ(хэши)" : !selfOk ? " ВЫКЛ(эталон)" : "") + ", столбцов " + LRU.size()
                + ", кубов " + cubesBlocks + " (свет " + cubesLight + "), " + (bytes >> 20) + "/" + CubeCacheSetting.get() + " МБ"
                + ", попаданий " + hits + "+" + lightHits + "св, промахов " + (missNone + missHash) + " (нет " + missNone + " / хэш " + missHash
                + ", свет " + lightMiss + "), запросов " + wants + ", ждём " + WAITING.size() + ", вытеснено " + evicted;
    }
}
