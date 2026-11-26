package ink.hoka.odyssey.client;

import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Разреженные секции высокого мира (CUBIC-CHUNKS.md §3.1, этап 2). Столбец 1876 секций, почти все — воздух: вместо
 * объекта на каждую пустую секцию — ОБЩАЯ неизменяемая секция «весь воздух, один биом» ({@link Shared}). Своя у
 * каждого мира (биомы — Holder'ы реестров соединения, фабрика палитр — своя у Level). Как только в общую секцию
 * ставят блок — столбец получает свою копию (LevelChunkMixin, «копия при записи»).
 */
public final class SparseSections {

    private SparseSections() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");
    /** Отладка: сколько общих секций выдано и сколько настоящих создано из пакетов (F3 / лог). */
    public static final AtomicLong SHARED_USED = new AtomicLong(), REAL = new AtomicLong(), COPIED = new AtomicLong(), MISUSE = new AtomicLong();
    /** 0.57: скелетов, разобранных в сетевом потоке (ChunkPacketPreparseMixin), и взятых готовыми. */
    public static final AtomicLong PREPARSED = new AtomicLong(), PRE_USED = new AtomicLong();
    /** 0.57: пакет столбца, который сейчас применяет главный поток (PacketDeferMixin.updateLevelChunk). */
    public static HokaPreparsed pending;

    /** Общая секция «весь воздух»: менять нельзя (запись — ошибка мода, логируем и игнорируем). */
    public static final class Shared extends LevelChunkSection {
        Shared(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) { super(states, biomes); }

        @Override public BlockState setBlockState(int x, int y, int z, BlockState state, boolean lock) {
            misuse("setBlockState");
            return Blocks.AIR.defaultBlockState();
        }
        @Override public void read(FriendlyByteBuf buf) { misuse("read"); throw new IllegalStateException("общая секция Hoka: read"); }
        @Override public void readBiomes(FriendlyByteBuf buf) { misuse("readBiomes"); throw new IllegalStateException("общая секция Hoka: readBiomes"); }
        @Override public void fillBiomesFromNoise(net.minecraft.world.level.biome.BiomeResolver r, net.minecraft.world.level.biome.Climate.Sampler s, int x, int y, int z) { misuse("fillBiomesFromNoise"); }
    }

    private static void misuse(String what) {
        if (MISUSE.getAndIncrement() == 0) LOG.warn("кто-то пишет в общую пустую секцию Hoka ({}) — запись пропущена", what, new Throwable());
    }

    private static final class Cache {
        final PalettedContainerFactory f;
        final Shared fallback;                                         // заглушка до прихода данных столбца
        final Map<Holder<Biome>, Shared> byBiome = new HashMap<>();
        /** id биома в пакете → общая секция (0.49: массив вместо ConcurrentHashMap с упаковкой Integer; копия при записи). */
        volatile Shared[] byId = new Shared[0];
        final PalettedContainer<BlockState> scratch;                   // сюда «вчитываем» блоки пустой секции (выбрасываем)
        Cache(PalettedContainerFactory f) {
            this.f = f;
            fallback = new Shared(f.createForBlockStates(), f.createForBiomes());
            scratch = f.createForBlockStates();
        }
    }

    private static final Map<PalettedContainerFactory, Cache> CACHES = new WeakHashMap<>();

    /** 0.49: последний кэш — без synchronized/WeakHashMap на каждую из 1876 секций столбца. */
    private static volatile Cache last;

    private static Cache cache(PalettedContainerFactory f) {
        Cache c = last;
        if (c != null && c.f == f) return c;
        synchronized (SparseSections.class) {
            c = CACHES.computeIfAbsent(f, Cache::new);
        }
        last = c;
        return c;
    }

    /** Заглушка для новых столбцов (до чтения пакета). */
    public static LevelChunkSection fallback(PalettedContainerFactory f) {
        return cache(f).fallback;
    }

    /** Прочитать секцию из пакета столбца: пустая с одним биомом → общая, иначе — настоящая. */
    public static LevelChunkSection read(PalettedContainerFactory f, FriendlyByteBuf buf) {
        if (buf.getShort(buf.readerIndex()) != 0) {
            LevelChunkSection s = new LevelChunkSection(f);
            s.read(buf);
            REAL.incrementAndGet();
            return s;
        }
        Cache c = cache(f);
        // быстрый путь скелета: 0 блоков, палитра блоков «один = воздух», палитра биомов «один = id» — общая секция
        // этого биома без разбора контейнеров (скелетов при 32 чанках — тысячи столбцов по 1876 секций)
        int ri = buf.readerIndex(), bid = -1;
        if (buf.readableBytes() >= 6 && buf.getByte(ri + 2) == 0 && buf.getByte(ri + 3) == 0 && buf.getByte(ri + 4) == 0) {
            buf.readerIndex(ri + 5);
            bid = buf.readVarInt();
            Shared[] ids = c.byId;
            Shared s = bid >= 0 && bid < ids.length ? ids[bid] : null;
            if (s != null) { SHARED_USED.incrementAndGet(); return s; }
            buf.readerIndex(ri);
        }
        synchronized (c) {
            buf.readShort();
            c.scratch.read(buf);
            PalettedContainer<Holder<Biome>> bio = f.createForBiomes();
            bio.read(buf);
            if (c.scratch.maybeHas(st -> !st.isAir())) {                // счётчик 0, а блоки есть — не доверяем, настоящая
                REAL.incrementAndGet();
                return new LevelChunkSection(c.scratch.copy(), bio);
            }
            Holder<Biome> one = single(bio);
            if (one == null) {
                REAL.incrementAndGet();
                return new LevelChunkSection(f.createForBlockStates(), bio);
            }
            SHARED_USED.incrementAndGet();
            Shared sh = c.byBiome.computeIfAbsent(one, h -> new Shared(f.createForBlockStates(), bio));
            if (bid >= 0 && bid < 65536) {                              // дальше этот биом — без разбора
                Shared[] ids = c.byId;
                if (bid >= ids.length) ids = java.util.Arrays.copyOf(ids, Math.max(bid + 1, ids.length * 2));
                else ids = ids.clone();
                ids[bid] = sh;
                c.byId = ids;
            }
            return sh;
        }
    }

    /** Биомы пустой общей секции из пакета биомов: та же логика. */
    public static LevelChunkSection readBiomes(PalettedContainerFactory f, FriendlyByteBuf buf) {
        PalettedContainer<Holder<Biome>> bio = f.createForBiomes();
        bio.read(buf);
        Holder<Biome> one = single(bio);
        if (one == null) return new LevelChunkSection(f.createForBlockStates(), bio);
        Cache c = cache(f);
        synchronized (c) { return c.byBiome.computeIfAbsent(one, h -> new Shared(f.createForBlockStates(), bio)); }
    }

    /** Своя копия общей секции перед записью блока. */
    public static LevelChunkSection copyForWrite(PalettedContainerFactory f, Shared s) {
        COPIED.incrementAndGet();
        return new LevelChunkSection(f.createForBlockStates(), s.getBiomes().copy());
    }

    private static Holder<Biome> single(PalettedContainer<Holder<Biome>> bio) {
        Object[] seen = {null, Boolean.FALSE};
        bio.count((h, n) -> {
            if (seen[0] == null) seen[0] = h;
            else if (seen[0] != h) seen[1] = Boolean.TRUE;
        });
        @SuppressWarnings("unchecked") Holder<Biome> h = (Holder<Biome>) seen[0];
        return seen[1] == Boolean.TRUE ? null : h;
    }

    public static String stats() {
        return "скелетов разобрано в сети " + PREPARSED.get() + " (взято " + PRE_USED.get() + "), общих " + SHARED_USED.get() + ", настоящих " + REAL.get() + ", копий " + COPIED.get() + (MISUSE.get() > 0 ? ", ошибок записи " + MISUSE.get() : "");
    }
}
