package ink.hoka.odyssey.client;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Кубические чанки, этап 3а (CUBIC-CHUNKS.md §3.2, упрощённо): сервер шлёт столбец скелетом (все секции — воздух),
 * а непустые кубы — пакетом {@code hoka_odyssey:w} только в окне по высоте вокруг игрока. Здесь куб кладётся в
 * секцию столбца (общие пустые секции — SparseSections), выгруженный куб снова становится пустым. Sodium видит
 * изменение через setSectionDirty и перестраивает секцию. Применяется на главном потоке.
 * <p>op 11 LIGHT (шаг 14) — свет кубов, разбирает и применяет ServerLight. Незнакомый op — пропуск с одной записью
 * в лог на код, блок-сущности не трогаем.</p>
 * <p>0.51, кэш кубов (CubeCache): op 12 CACHE — ключ карты (сразу, по порядку со скелетами), op 13 CUBES_REF — куб из
 * кэша (как op 2, блок-энтити в пакете), op 14 LIGHT_REF — свет из кэша (как op 11), op 15 HASH_CHECK — сверка.</p>
 */
public final class CubeReceiver {

    private CubeReceiver() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");
    public static long received, unloaded, dropped, fromCache;
    /** Незнакомые коды операций — в лог по одному разу. */
    private static final java.util.Set<Integer> UNKNOWN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Окно загруженных кубов по Y (секции) от сервера; мир, к которому оно относится (другой мир — окна нет). */
    private static volatile ClientLevel windowLevel;
    private static volatile int from, to;

    /** Кубовая карта и секция Y вне окна — куб не загружен: туда не двигаться. */
    public static boolean unloadedY(net.minecraft.world.level.Level level, int sectionY) {
        return level == windowLevel && level != null && (sectionY < from || sectionY > to);
    }

    /** Есть ли окно для этого мира (кубовая карта). */
    public static boolean active(net.minecraft.world.level.Level level) { return level != null && level == windowLevel; }

    /** Блок-сущность из op 2 (разобрана заранее, создаётся на главном потоке). */
    record BE(int xz, int y, net.minecraft.world.level.block.entity.BlockEntityType<?> type, net.minecraft.nbt.CompoundTag tag) {}

    /**
     * Разобранный пакет {@code hoka_odyssey:w} (0.49). Секции op 2 и свет op 11 разбираются на сетевом потоке (при
     * декодировании, см. Net.W), на главный поток приходит готовое; применяет CubeQueue по бюджету кадра.
     */
    static final class Parsed {
        final int op;
        /** Фабрика палитр, которой разобраны секции op 2 (сверяется с миром на главном потоке); null — секций нет. */
        final net.minecraft.world.level.chunk.PalettedContainerFactory f;
        int a, b;                                // op 5: from, to
        int[] yard;                              // op 6: пары x, z
        int[] floor;                             // op 16: тройки cx, cz, пол неба (секция)
        long[] pos;                              // op 2 / op 4 / op 11 / op 13 / op 14: кубы
        long[] hash;                             // op 13: версии H; op 14: L; op 15: H
        long[] check;                            // op 15: позиции (не очередь кубов — без поколений)
        long map;                                // op 12
        CubeCache.Ns ns;                         // op 2/11/13/14: пространство имён кэша на момент прихода
        LevelChunkSection[] secs;                // op 2
        BE[][] bes;                              // op 2: блок-сущности куба (null — нет)
        ServerLight.Batch light;                 // op 11
        Parsed(int op, net.minecraft.world.level.chunk.PalettedContainerFactory f) { this.op = op; this.f = f; }
    }

    /** Сетевой поток (декодер Net.W): разобрать заранее; null — не вышло (разберём на главном). */
    static Object preparse(byte[] data) {
        try {
            ClientLevel l = Minecraft.getInstance().level;
            return parse(data, l == null ? null : l.palettedContainerFactory());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Разбор пакета без побочных эффектов. f == null и в пакете есть секции — null. */
    static Parsed parse(byte[] data, net.minecraft.world.level.chunk.PalettedContainerFactory f) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        int op = buf.readByte();
        switch (op) {
            case 5 -> { Parsed r = new Parsed(op, null); r.a = buf.readInt(); r.b = buf.readInt(); return r; }
            case 6 -> {
                int m = buf.readVarInt();
                Parsed r = new Parsed(op, null);
                r.yard = new int[m * 2];
                for (int i = 0; i < m * 2; i++) r.yard[i] = buf.readInt();
                return r;
            }
            case 11 -> { Parsed r = new Parsed(op, null); r.light = ServerLight.parse(buf); r.pos = r.light.pos(); return r; }
            case 16 -> {                                                // 0.54: SKY_FLOOR
                int m = buf.readVarInt();
                Parsed r = new Parsed(op, null);
                r.floor = new int[m * 3];
                for (int i = 0; i < m * 3; i++) r.floor[i] = buf.readInt();
                return r;
            }
            case 12 -> { Parsed r = new Parsed(op, null); r.map = buf.readLong(); return r; }
            case 14, 15 -> {
                int n = buf.readVarInt();
                Parsed r = new Parsed(op, null);
                long[] ps = new long[n];
                r.hash = new long[n];
                for (int i = 0; i < n; i++) { ps[i] = buf.readLong(); r.hash[i] = buf.readLong(); }
                if (op == 14) r.pos = ps; else r.check = ps;
                return r;
            }
            case 13 -> {
                int n = buf.readVarInt();
                Parsed r = new Parsed(op, null);
                r.pos = new long[n];
                r.hash = new long[n];
                r.bes = new BE[n][];
                for (int i = 0; i < n; i++) {
                    r.pos[i] = buf.readLong();
                    r.hash[i] = buf.readLong();
                    r.bes[i] = readBes(buf);
                }
                return r;
            }
            case 4 -> {
                int n = buf.readVarInt();
                Parsed r = new Parsed(op, null);
                r.pos = new long[n];
                for (int i = 0; i < n; i++) r.pos[i] = buf.readLong();
                return r;
            }
            case 2 -> {
                if (f == null) return null;
                int n = buf.readVarInt();
                Parsed r = new Parsed(op, f);
                r.pos = new long[n];
                r.secs = new LevelChunkSection[n];
                r.bes = new BE[n][];
                for (int i = 0; i < n; i++) {
                    r.pos[i] = buf.readLong();
                    r.secs[i] = SparseSections.read(f, buf);
                    r.bes[i] = readBes(buf);
                }
                return r;
            }
            default -> { return new Parsed(op, null); }
        }
    }

    /** Блок-энтити куба (op 2 / op 13): varint m, m × (byte x<<4|z, short y, varint тип, NBT); null — нет. */
    private static BE[] readBes(FriendlyByteBuf buf) {
        int m = buf.readVarInt();
        if (m == 0) return null;
        BE[] be = new BE[m];
        for (int j = 0; j < m; j++) {
            int xz = buf.readByte() & 0xFF;
            int by = buf.readShort();
            var type = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.byId(buf.readVarInt());
            be[j] = new BE(xz, by, type, buf.readNbt());
        }
        return be;
    }

    static void handle(byte[] data) { handle(data, null); }

    /** Пакет пришёл (главный поток): разобранное (или разобрать сейчас) — в очередь применения CubeQueue. */
    static void handle(byte[] data, Object pre) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) { mc.execute(() -> handle(data, pre)); return; }
        ClientLevel level = mc.level;
        CubeCache.sync(level);                                          // мир сменился — прежний в кэш (до света/кубов нового)
        if (level == null) {                                            // op 12 может прийти и без мира
            if (data.length > 0 && data[0] == 12) try { CubeCache.onKey(parse(data, null).map); } catch (Exception ignored) {}
            return;
        }
        Parsed p;
        try {
            p = pre instanceof Parsed q && (q.f == null || q.f == level.palettedContainerFactory()) ? q
                    : parse(data, level.palettedContainerFactory());
        } catch (Exception e) {
            LOG.warn("кубы: битый пакет ({})", e.toString());
            return;
        }
        if (p == null) return;
        if (p.op == 12) { CubeCache.onKey(p.map); return; }            // сразу: по порядку со скелетами (метки столбцов)
        if (p.op != 2 && p.op != 4 && p.op != 5 && p.op != 6 && p.op != 11 && p.op != 13 && p.op != 14 && p.op != 15 && p.op != 16) {   // незнакомая операция (сервер новее): пропустить,
            if (UNKNOWN.add(p.op)) LOG.warn("кубы: незнакомая операция {} — пропускаю (ядро старше сервера)", p.op);   // блок-энтити не трогать
            return;
        }
        p.ns = CubeCache.current();
        CubeQueue.add(level, p);
    }

    /** Применить op 5 / op 6 / op 15 / op 16 (главный поток, по очереди CubeQueue). */
    static void applySimple(ClientLevel level, Parsed p) {
        if (p.op == 5) { windowLevel = level; from = p.a; to = p.b; serverLight(level); return; }
        if (p.op == 15) { CubeCache.hashCheck(level, p.check, p.hash); return; }
        if (p.op == 16) { ServerLight.floor(level, p.floor); return; }
        if (p.op == 6) for (int i = 0; i + 1 < p.yard.length; i += 2) YardColumns.forget(level, p.yard[i], p.yard[i + 1]);   // забыть столбцы верфи кораблей
    }

    /**
     * 0.52: режим «свет от сервера» — с окна (op 5) / первого куба высокого мира, а не с первого op 11: до него ваниль
     * заливала слои неба 15 и считала небо по столбцу (вход, смена мира).
     */
    private static void serverLight(ClientLevel level) {
        if (TallWorld.net && level.getSectionsCount() > 64 && ServerLight.level != level) ServerLight.activate(level);
    }

    /** Применить один куб op 2 / op 4 (главный поток, по очереди CubeQueue). stale — столбец сменился после прихода пакета. */
    static void applyCube(ClientLevel level, Parsed p, int i, boolean stale) {
        long pos = p.pos[i];
        if (stale) { CubeQueue.stale++; return; }
        serverLight(level);
        int x = SectionPos.x(pos), y = SectionPos.y(pos), z = SectionPos.z(pos);
        LevelChunk c = YardColumns.isYard(x) ? YardColumns.getOrCreate(level, x, z)   // верфь кораблей — своя карта
                : (LevelChunk) level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        int idx = level.getSectionIndexFromSectionY(y);
        boolean ok = c != null && idx >= 0 && idx < c.getSections().length;
        if (p.op == 13) {                                               // 0.51: куб из кэша
            if (!ok) { dropped++; return; }
            LevelChunkSection sec = CubeCache.takeBlocks(p.ns, pos, p.hash[i], level.palettedContainerFactory());
            if (sec == null) return;                                    // промах: секция как есть, WANT ушёл (CubeCache)
            removeBlockEntities(c, y);
            put(level, c, idx, x, y, z, sec);
            BE[] be = p.bes[i];
            if (be != null) for (BE e : be)
                loadBlockEntity(level, c, new net.minecraft.core.BlockPos((x << 4) + (e.xz() >> 4), e.y(), (z << 4) + (e.xz() & 15)), e.type(), e.tag());
            received++;
            fromCache++;
            return;
        }
        if (ok) removeBlockEntities(c, y);
        if (p.op == 2) {
            CubeCache.gotBlocks(p.ns, pos);
            if (ok) {
                put(level, c, idx, x, y, z, p.secs[i]);
                BE[] be = p.bes[i];
                if (be != null) for (BE e : be)
                    loadBlockEntity(level, c, new net.minecraft.core.BlockPos((x << 4) + (e.xz() >> 4), e.y(), (z << 4) + (e.xz() & 15)), e.type(), e.tag());
                received++;
            } else dropped++;
        } else {
            CubeCache.unwaitAll(pos);
            if (ok) {
                LevelChunkSection old = c.getSections()[idx];
                CubeCache.captureCube(level, c, x, y, z, old);          // 0.51: куб и его свет — в кэш
                put(level, c, idx, x, y, z, SparseSections.fallback(level.palettedContainerFactory()));
                unloaded++;
            } else dropped++;
        }
    }

    private static void removeBlockEntities(LevelChunk c, int sy) {
        var gone = new java.util.ArrayList<net.minecraft.core.BlockPos>();
        for (var pos : c.getBlockEntities().keySet()) if (SectionPos.blockToSectionCoord(pos.getY()) == sy) gone.add(pos);
        for (var pos : gone) c.removeBlockEntity(pos);
    }

    /** Как в LevelChunk.replaceWithPacketData: создать блок-энтити по блоку и загрузить данные. */
    private static void loadBlockEntity(ClientLevel level, LevelChunk c, net.minecraft.core.BlockPos pos,
                                        net.minecraft.world.level.block.entity.BlockEntityType<?> type, net.minecraft.nbt.CompoundTag tag) {
        var be = c.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        if (be == null || tag == null || be.getType() != type) return;
        try (var rep = new net.minecraft.util.ProblemReporter.ScopedCollector(be.problemPath(), LOG)) {
            be.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(rep, level.registryAccess(), tag));
        }
    }

    private static void put(ClientLevel level, LevelChunk c, int idx, int x, int y, int z, LevelChunkSection sec) {
        LevelChunkSection old = c.getSections()[idx];
        c.getSections()[idx] = sec;
        boolean empty = sec.hasOnlyAir();
        if (old.hasOnlyAir() != empty) level.getChunkSource().getLightEngine().updateSectionStatus(SectionPos.of(x, y, z), empty);
        if (level.getSectionsCount() > 64) {
            // высокий мир: сам куб + соседи только за изменившимися гранями (закрывшиеся — мягко), слияние за кадр (SectionDirty)
            ContentRange.set(level, x, y, z, sec);
            SectionDirty.mark(x, y, z, SectionDirty.blockFaces(old, sec));
        } else {
            level.setSectionDirtyWithNeighbors(x, y, z);
        }
    }

    public static String stats() {
        return "кубов принято " + received + " (из кэша " + fromCache + "), выгружено " + unloaded + (dropped > 0 ? ", отброшено " + dropped : "") + ", " + CubeQueue.stats();
    }
}
