package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.server.tall.region.RegionFiles;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Стресс шагов 9–11: один ленивый столбец, параллельно — запись (блоки и сундуки), выгрузка кубов, сохранение,
 * запросы загрузки. Ни одна успешная запись не должна потеряться: в памяти — сразу, после рестарта — с диска.
 */
class CubeStressTest {

    static Instance base;
    static RegistryKey<DimensionType> key;

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tmp;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_stress_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        base = MinecraftServer.getInstanceManager().createInstanceContainer(key);
    }

    private static final Block[] VALUES = { Block.STONE, Block.GOLD_BLOCK, Block.DIAMOND_BLOCK, Block.EMERALD_BLOCK, Block.AIR, Block.OAK_PLANKS };
    /** Кубы: земля генератора (y 0…47), небо, высоко, глубоко, у самой границы куба. */
    private static final int[] YS = { 5, 20, 39, 40, 41, 300, 3000, 12000, -10000, 15, 16 };

    private static Block chest(int n) {
        return Block.CHEST.withProperty("facing", "north")
                .withNbt(CompoundBinaryTag.builder().putString("CustomName", "\"" + n + "\"").build());
    }

    private static int idx(int y) { return (y >> 4) - (TallWorld.MIN_Y >> 4); }

    @Test
    void writeUnloadSaveLoadConcurrently() throws Exception {
        Path map = tmp.resolve("stress");
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        l.setLazy(true);
        l.setUnload(true);
        Instance li = MinecraftServer.getInstanceManager().createInstanceContainer(key, l);
        final int cx = 10, cz = 11;
        CubeColumn c = CubeColumn.generated(li, cx, cz);
        assertTrue(c.isLazy());

        final int writers = 6;
        final Map<Long, Block> expected = new ConcurrentHashMap<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Throwable> fail = new AtomicReference<>();
        final AtomicLong writes = new AtomicLong(), rejected = new AtomicLong(), unloaded = new AtomicLong(), saves = new AtomicLong(), checks = new AtomicLong();
        List<Thread> ts = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            final int wid = w;
            ts.add(Thread.ofPlatform().name("w" + w).start(() -> {
                var rnd = ThreadLocalRandom.current();
                try {
                    while (!stop.get()) {
                        // у каждого писателя свои колонки x (wid*2, wid*2+1) — ожидаемое значение однозначно
                        int lx = wid * 2 + rnd.nextInt(2), lz = rnd.nextInt(16);
                        int y = YS[rnd.nextInt(YS.length)] + rnd.nextInt(3) - 1;
                        int x = cx * 16 + lx, z = cz * 16 + lz;
                        long k = pk(lx, y, lz);
                        int i = idx(y);
                        c.whenReady(i, 0).get(30, TimeUnit.SECONDS);
                        Block b = rnd.nextInt(8) == 0 ? chest(rnd.nextInt(1000)) : VALUES[rnd.nextInt(VALUES.length)];
                        c.lockWriteLock();
                        try {
                            // проверка: куб в памяти — там ровно то, что мы писали последним
                            if (c.inMemory(i)) {
                                Block want = expected.get(k);
                                if (want != null) {
                                    Block got = c.getBlock(x, y, z, Block.Getter.Condition.NONE);
                                    if (!same(want, got)) throw new AssertionError("в памяти " + got + " вместо " + want + " @" + x + " " + y + " " + z);
                                    checks.incrementAndGet();
                                }
                            }
                            c.setBlock(x, y, z, b);
                            expected.put(k, b);
                            writes.incrementAndGet();
                        } catch (IllegalStateException e) {
                            rejected.incrementAndGet();                    // куб ушёл в выгрузку между ожиданием и записью — норма
                        } finally { c.unlockWriteLock(); }
                    }
                } catch (Throwable t) { fail.compareAndSet(null, t); stop.set(true); }
            }));
        }
        ts.add(Thread.ofPlatform().name("unloader").start(() -> {
            try {
                while (!stop.get()) {
                    int[] is = c.pickUnload(new BitSet(), System.currentTimeMillis() + CubeStore.IDLE_MS + 1_000, CubeStore.IDLE_MS);
                    if (is.length > 0) unloaded.addAndGet(c.unloadCubes(is));
                    Thread.onSpinWait();
                }
            } catch (Throwable t) { fail.compareAndSet(null, t); stop.set(true); }
        }));
        ts.add(Thread.ofPlatform().name("saver").start(() -> {
            try {
                while (!stop.get()) {
                    try { l.save(c); saves.incrementAndGet(); }
                    catch (java.io.IOException e) { throw new AssertionError(e); }
                    Thread.sleep(2);
                }
            } catch (Throwable t) { fail.compareAndSet(null, t); stop.set(true); }
        }));
        ts.add(Thread.ofPlatform().name("loader").start(() -> {
            var rnd = ThreadLocalRandom.current();
            try {
                while (!stop.get()) {
                    c.request(idx(YS[rnd.nextInt(YS.length)]), rnd.nextInt(5));
                    c.lockReadLock();
                    try { c.getBlock(cx * 16 + rnd.nextInt(16), YS[rnd.nextInt(YS.length)], cz * 16 + rnd.nextInt(16)); }
                    finally { c.unlockReadLock(); }
                    c.light();                                              // свет читает палитры без замка — не должен падать
                }
            } catch (Throwable t) { fail.compareAndSet(null, t); stop.set(true); }
        }));

        long end = System.currentTimeMillis() + 6_000;
        while (System.currentTimeMillis() < end && !stop.get()) Thread.sleep(50);
        stop.set(true);
        for (Thread t : ts) t.join(60_000);
        if (fail.get() != null) throw new AssertionError("сбой в потоке", fail.get());
        System.out.println("стресс: записей " + writes + ", отклонено (выгружался) " + rejected + ", кубов выгружено " + unloaded
                + ", сохранений " + saves + ", проверок в памяти " + checks + ", позиций " + expected.size());
        assertTrue(unloaded.get() > 50, "выгрузка должна была крутиться");
        assertTrue(writes.get() > 1000);

        // всё дописать: дождаться очереди загрузки (она не пишет), сохранить
        Thread.sleep(200);
        l.save(c);
        assertFalse(c.isModified(), "после сохранения ничего не висит");
        l.close();
        MinecraftServer.getInstanceManager().unregisterInstance(li);

        // «рестарт»: обычная (не ленивая) загрузка — каждая успешная запись на месте
        var r = (CubeColumn) new CubeRegionLoader(map, null).loadChunk(base, cx, cz);
        int bad = 0;
        r.lockReadLock();
        try {
            for (var e : expected.entrySet()) {
                long k = e.getKey();
                int lx = (int) (k >> 40) & 0xFF, lz = (int) (k >> 32) & 0xFF, y = (int) k;
                Block got = r.getBlock(cx * 16 + lx, y, cz * 16 + lz, Block.Getter.Condition.NONE);
                if (!same(e.getValue(), got)) {
                    if (bad++ < 10) System.out.println("ПОТЕРЯ: " + lx + " " + y + " " + lz + " ждали " + e.getValue() + " есть " + got);
                }
            }
        } finally { r.unlockReadLock(); }
        RegionFiles.closeUnder(map);
        assertEquals(0, bad, "потерянных записей");
        assertFalse(TallSections.poisoned(), "общая пустая секция испорчена");
    }

    /** Мигрированный из .htw столбец снова как у генератора: заголовок остаётся (пустой), старый .htw не возвращается. */
    @Test
    void htwNotResurrected() throws Exception {
        Path map = tmp.resolve("htw"), htwDir = map.resolve("tall");
        java.nio.file.Files.createDirectories(htwDir);
        int cx = 3, cz = 4, x = cx * 16 + 2, z = cz * 16 + 2;
        CubeColumn a = CubeColumn.generated(base, cx, cz);
        a.lockWriteLock();
        try { a.setBlock(x, 9000, z, Block.GOLD_BLOCK); } finally { a.unlockWriteLock(); }
        new TallLoader(htwDir, CubeColumn::new).saveChunk(a);

        CubeRegionLoader l = new CubeRegionLoader(map, htwDir);
        var b = (CubeColumn) l.loadChunk(base, cx, cz);
        b.lockWriteLock();
        try {
            assertEquals(Block.GOLD_BLOCK, b.getBlock(x, 9000, z));
            b.setBlock(x, 9000, z, Block.AIR);                              // снова как у генератора
        } finally { b.unlockWriteLock(); }
        l.save(b);
        RegionFiles.closeUnder(map);
        var d = (CubeColumn) new CubeRegionLoader(map, htwDir).loadChunk(base, cx, cz);
        d.lockReadLock();
        try { assertTrue(d.getBlock(x, 9000, z).isAir(), "из .htw вернулся старый блок"); }
        finally { d.unlockReadLock(); }
        RegionFiles.closeUnder(map);
    }

    private static long pk(int lx, int y, int lz) { return ((long) lx << 40) | ((long) lz << 32) | (y & 0xFFFFFFFFL); }

    private static boolean same(Block a, Block b) {
        if (a.isAir() && b.isAir()) return true;
        return a.stateId() == b.stateId() && java.util.Objects.equals(a.nbt(), b.nbt());
    }
}
