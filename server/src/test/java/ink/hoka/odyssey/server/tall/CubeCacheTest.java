package ink.hoka.odyssey.server.tall;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.play.data.LightData;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Кэш кубов клиента (ядро 0.51): версия куба (CubeHash), её сброс в CubeColumn, модель на сервере, ops 13/14. */
class CubeCacheTest {

    static Instance inst;

    @BeforeAll
    static void init() throws Exception {
        MinecraftServer.init();
        RegistryKey<DimensionType> key = MinecraftServer.getDimensionTypeRegistry().register(Key.key("hoka:tall_cache_test"),
                TallWorld.tall(DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0));
        inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
    }

    /**
     * Эталонные векторы: те же числа обязан давать CubeHash клиента (ink.hoka.odyssey.client.CubeHash). Изменились —
     * значит, поменяли формулу: поднять FORMAT в ОБОИХ файлах.
     */
    static final long REF_ZERO = 0xd7b11457959f700cL, REF_PATTERN = 0xf12b4d5e0f896b11L, REF_LIGHT = 0x8abcaff9818b4ab1L;

    static int[] pattern(int n, int mod) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i * 7 % mod;
        return a;
    }

    @Test
    void referenceVectors() {
        long z = CubeHash.ids(new int[4096], new int[64]);
        long p = CubeHash.ids(pattern(4096, 23), pattern(64, 5));
        byte[] sd = new byte[256];
        for (int i = 0; i < 256; i++) sd[i] = (byte) (i % 17);
        long l = CubeHash.light(0, sd, 1, null);
        System.out.printf("CubeHash эталоны: zero %016x, pattern %016x, light %016x%n", z, p, l);
        if (REF_ZERO != 0) {
            assertEquals(REF_ZERO, z);
            assertEquals(REF_PATTERN, p);
            assertEquals(REF_LIGHT, l);
        }
        assertNotEquals(0, z);
        assertNotEquals(z, p);
    }

    private static Section randomSection(Random r, int kinds) {
        Section s = new Section();
        Block[] pal = {Block.STONE, Block.DIRT, Block.GRASS_BLOCK, Block.OAK_LOG, Block.GLASS, Block.WATER, Block.SAND, Block.GOLD_BLOCK};
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            if (r.nextInt(3) > 0) s.blockPalette().set(x, y, z, pal[r.nextInt(Math.min(kinds, pal.length))].stateId());
        int n = MinecraftServer.getBiomeRegistry().size();
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++)
            s.biomePalette().set(x, y, z, r.nextInt(Math.min(n, 3)));
        return s;
    }

    /** Сетевая форма секции, как в op 2: short блоков, палитра блоков, палитра биомов. */
    private static byte[] wireOf(Section s) {
        var biomeSer = Palette.biomeSerializer(MinecraftServer.getBiomeRegistry().size());
        return NetworkBuffer.makeArray(buf -> {
            buf.write(NetworkBuffer.SHORT, (short) s.blockPalette().count());
            buf.write(Palette.BLOCK_SERIALIZER, s.blockPalette());
            buf.write(biomeSer, s.biomePalette());
        });
    }

    @Test
    void wireMatchesPalette() {
        Random r = new Random(11);
        for (int k : new int[]{1, 2, 5, 8}) {
            Section s = randomSection(r, k);
            byte[] w = wireOf(s);
            int[] off = {0};
            assertEquals(CubeHash.of(s), CubeHash.wire(w, off), "разбор сетевой формы (как у клиента), видов " + k);
            assertEquals(w.length, off[0], "секция разобрана целиком");
        }
        Section one = new Section();
        one.blockPalette().fill(Block.STONE.stateId());
        int[] off = {0};
        assertEquals(CubeHash.of(one), CubeHash.wire(wireOf(one), off), "одно значение");
    }

    @Test
    void independentOfPaletteHistory() {
        Random r = new Random(3);
        Section a = randomSection(r, 6);
        Section b = new Section();
        // b: сначала мусор (палитра растёт в другом порядке), потом то же содержимое, что у a
        for (int y = 15; y >= 0; y--) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) b.blockPalette().set(x, y, z, Block.DIAMOND_BLOCK.stateId());
        for (int y = 15; y >= 0; y--) for (int z = 15; z >= 0; z--) for (int x = 15; x >= 0; x--) b.blockPalette().set(x, y, z, a.blockPalette().get(x, y, z));
        for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) b.biomePalette().set(x, y, z, a.biomePalette().get(x, y, z));
        assertEquals(CubeHash.of(a), CubeHash.of(b));
        // одно значение заливкой и поштучно (палитра — массив) — одна версия
        Section f = new Section(), g = new Section();
        f.blockPalette().fill(Block.STONE.stateId());
        g.blockPalette().set(0, 0, 0, Block.DIRT.stateId());
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) g.blockPalette().set(x, y, z, Block.STONE.stateId());
        assertEquals(CubeHash.of(f), CubeHash.of(g));
        // один блок другой — другая версия; биом другой — другая
        b.blockPalette().set(3, 4, 5, Block.BEDROCK.stateId());
        assertNotEquals(CubeHash.of(a), CubeHash.of(b));
        Section c = a.clone();
        c.biomePalette().set(1, 1, 1, (c.biomePalette().get(1, 1, 1) + 1) % 3);
        assertNotEquals(CubeHash.of(a), CubeHash.of(c));
    }

    private static void set(TallColumn c, int x, int y, int z, Block b) {
        c.lockWriteLock();
        try { c.setBlock(x, y, z, b); } finally { c.unlockWriteLock(); }
    }

    private static long hash(TallColumn c, int i) {
        c.lockReadLock();
        try { return c.cubeHash(i); } finally { c.unlockReadLock(); }
    }

    private static int idx(int y) { return (y >> 4) - (TallWorld.MIN_Y >> 4); }

    @Test
    void columnHashResetOnEdits() {
        CubeColumn c = CubeColumn.generated(inst, 40, 41);
        int x = 40 * 16 + 2, z = 41 * 16 + 9, y = 5000, i = idx(y);
        set(c, x, y, z, Block.STONE);
        set(c, x + 1, y, z, Block.DIRT);
        long h0 = hash(c, i);
        assertEquals(h0, hash(c, i), "посчитанная версия — та же");
        set(c, x, y + 1, z, Block.GLASS);
        long h1 = hash(c, i);
        assertNotEquals(h0, h1, "правка блока — новая версия");
        set(c, x, y + 1, z, Block.AIR);
        assertEquals(h0, hash(c, i), "вернули как было — версия как была (хэш содержимого, не счётчик)");
        c.lockWriteLock();
        try { c.setBiome(x, y, z, Biome.DESERT); } finally { c.unlockWriteLock(); }
        assertNotEquals(h0, hash(c, i), "смена биома — новая версия");
        c.lockReadLock();
        try {
            assertEquals(CubeHash.of(c.getSections().get(i)), c.cubeHash(i), "кэш = заново по содержимому");
            assertEquals(c.cubeHash(i), c.cubeHashFresh(i));
        } finally { c.unlockReadLock(); }
        // копия инстанса — та же версия
        CubeColumn d;
        c.lockReadLock();
        try { d = (CubeColumn) c.copy(inst, 40, 41); } finally { c.unlockReadLock(); }
        assertEquals(hash(c, i), hash(d, i));
    }

    @Test
    void hashSurvivesSaveLoad(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path map = tmp.resolve("cache");
        CubeColumn c = CubeColumn.generated(inst, 7, -3);
        int x = 7 * 16 + 4, z = -3 * 16 + 4, y = 1234, i = idx(y);
        Random r = new Random(5);
        for (int k = 0; k < 300; k++) set(c, x + r.nextInt(12), y + r.nextInt(16) - (y & 15), z + r.nextInt(12), r.nextBoolean() ? Block.STONE : Block.OAK_PLANKS);
        long h = hash(c, i);
        CubeRegionLoader l = new CubeRegionLoader(map, null);
        l.save(c);
        l.close();
        var d = (CubeColumn) new CubeRegionLoader(map, null).loadChunk(inst, 7, -3);
        assertEquals(h, hash(d, i), "после записи на диск и загрузки (перезапуск) — та же версия");
        ink.hoka.odyssey.server.tall.region.RegionFiles.closeUnder(map);
        // ключ карты: файл cubecache.id — тот же при повторном чтении, другой у новой папки
        long a = CacheKeys.fileId(map), b = CacheKeys.fileId(map), n = CacheKeys.fileId(tmp.resolve("other"));
        assertEquals(a, b);
        assertNotEquals(a, n);
    }

    @Test
    void modelDecisions() {
        CubeCacheModel.P m = new CubeCacheModel.P();
        CubeCacheModel.budget(m, 64);
        long key = 77;
        assertFalse(CubeCacheModel.refBlocks(m, key, 1, 2, 900, 5), "нет записи — целиком");
        CubeCacheModel.cachedBlocks(m, key, 1, 2, 900, 5);
        assertTrue(CubeCacheModel.refBlocks(m, key, 1, 2, 900, 5), "та же версия — ссылкой");
        assertFalse(CubeCacheModel.refBlocks(m, key, 1, 2, 900, 6), "куб правили, пока игрока не было — целиком");
        assertFalse(CubeCacheModel.refBlocks(m, 78, 1, 2, 900, 5), "другая карта");
        CubeCacheModel.sentBlocks(m, key, 1, 2, 900);
        assertFalse(CubeCacheModel.refBlocks(m, key, 1, 2, 900, 5), "отправлен — у клиента его в кэше больше нет");
        // засев HAVE: версия неизвестна — ссылкой с любой текущей; известную не затирает
        CubeCacheModel.cachedBlocks(m, key, 3, 3, 10, 42);
        byte[] bm = {(byte) 0b101}, lm = {(byte) 0b001};
        CubeCacheModel.seed(m, key, 3, 3, 10, bm, lm, 3);
        assertTrue(CubeCacheModel.refBlocks(m, key, 3, 3, 10, 42));
        assertFalse(CubeCacheModel.refBlocks(m, key, 3, 3, 10, 43), "засев не затёр известную версию");
        assertTrue(CubeCacheModel.refBlocks(m, key, 3, 3, 12, 999));
        assertFalse(CubeCacheModel.refBlocks(m, key, 3, 3, 11, 999));
        assertTrue(CubeCacheModel.refLight(m, key, 3, 3, 10, 1234));
        // свет: последняя L у клиента
        CubeCacheModel.sentLight(m, key, 3, 3, 10, 555);
        assertTrue(CubeCacheModel.refLight(m, key, 3, 3, 10, 555));
        assertFalse(CubeCacheModel.refLight(m, key, 3, 3, 10, 556));
        // EVICT
        CubeCacheModel.evict(m, key, 3, 3);
        assertFalse(CubeCacheModel.refBlocks(m, key, 3, 3, 10, 42));
        assertFalse(CubeCacheModel.refLight(m, key, 3, 3, 10, 555));
        // предел записей: старые столбцы вытесняются
        CubeCacheModel.budget(m, 1);                                       // 4096 записей
        for (int k = 0; k < 10_000; k++) CubeCacheModel.cachedBlocks(m, key, k, 0, 1, 7);
        assertFalse(CubeCacheModel.refBlocks(m, key, 0, 0, 1, 7), "старейший вытеснен");
        assertTrue(CubeCacheModel.refBlocks(m, key, 9_999, 0, 1, 7));
        // бюджет 0 — модель прочь
        CubeCacheModel.budget(m, 0);
        assertFalse(CubeCacheModel.refBlocks(m, key, 9_999, 0, 1, 7));
    }

    @Test
    void lightRecordHashMatchesClientParse() {
        Random r = new Random(9);
        byte[][] s = new byte[4][2048];
        java.util.Arrays.fill(s[0], (byte) 0xFF);
        for (int q = 0; q < 4096; q++) if ((q >> 8) >= (q & 255) % 17) CubeLight.setNib(s[1], q, 15);
        for (int q = 0; q < 4096; q++) CubeLight.setNib(s[2], q, (q & 255) % 16);
        r.nextBytes(s[3]);
        List<byte[]> sky = new ArrayList<>(List.of(s)), blk = new ArrayList<>();
        BitSet sm = new BitSet(), bm = new BitSet();
        for (int i = 0; i < 4; i++) sm.set(i + 1);
        byte[] torch = new byte[2048];
        r.nextBytes(torch);
        bm.set(4); blk.add(torch);
        LightData l = new LightData(sm, bm, new BitSet(), new BitSet(), sky, blk);
        List<LightOp.Rec> recs = LightOp.records(1, 2, -50, l, List.of(0, 1, 2, 3, 4), true);
        assertEquals(5, recs.size());
        for (LightOp.Rec rec : recs) {
            NetworkBuffer b = NetworkBuffer.wrap(rec.bytes(), 0, rec.bytes().length);
            assertEquals(rec.pos(), b.read(NetworkBuffer.LONG));
            assertEquals(rec.light(), clientLight(b), "L записи " + rec.i() + " = L разбора клиента");
            assertEquals(0, b.readableBytes());
        }
        assertTrue(recs.get(0).bytes().length <= LightOp.REF_MIN, "«всё 15» — короткая запись, ссылкой не шлётся");
        assertTrue(recs.get(3).bytes().length > LightOp.REF_MIN);
        // те же записи двумя путями: encode == pack(records)
        List<byte[]> a = LightOp.encode(1, 2, -50, l, List.of(0, 1, 2, 3, 4), true), p = LightOp.pack(recs);
        assertEquals(a.size(), p.size());
        for (int k = 0; k < a.size(); k++) assertArrayEquals(a.get(k), p.get(k));
        // op 14
        byte[] ref = LightOp.packRefs(recs.subList(3, 5)).get(0);
        NetworkBuffer b = NetworkBuffer.wrap(ref, 0, ref.length);
        assertEquals((byte) 14, b.read(NetworkBuffer.BYTE));
        assertEquals(2, b.read(NetworkBuffer.VAR_INT));
        assertEquals(recs.get(3).pos(), b.read(NetworkBuffer.LONG));
        assertEquals(recs.get(3).light(), b.read(NetworkBuffer.LONG));
    }

    /** Разбор записи, как ServerLight.parse клиента (после pos), и хэш его вида Cube. */
    static long clientLight(NetworkBuffer b) {
        int f = b.read(NetworkBuffer.BYTE) & 0xFF;
        int sm = f >> 2 & 3, bmode = f >> 4 & 3;
        int sky;
        byte[] sd = null;
        switch (sm) {
            case 0 -> {
                sky = 0;
                int d = b.read(NetworkBuffer.BYTE) & 0xFF;
                sd = new byte[256];
                if (d == 0xFF) sd = b.read(NetworkBuffer.FixedRawBytes(256)); else java.util.Arrays.fill(sd, (byte) Math.min(d, 16));
            }
            case 1 -> sky = 1;
            case 2 -> sky = 2;
            default -> {
                if ((f & 0x40) != 0) { sky = 4; sd = b.read(NetworkBuffer.FixedRawBytes(128)); }
                else { sky = 3; sd = b.read(NetworkBuffer.FixedRawBytes(2048)); }
            }
        }
        byte[] bd = bmode == 3 ? b.read(NetworkBuffer.FixedRawBytes(2048)) : null;
        return CubeHash.light(sky, sd, bmode, bd);
    }

    @Test
    void cubesRefCodec() {
        CubeColumn c = CubeColumn.generated(inst, 12, 13);
        int x = 12 * 16 + 1, z = 13 * 16 + 1, y = 700, i = idx(y);
        set(c, x, y, z, Block.STONE);
        set(c, x, y + 1, z, Block.CHEST);
        List<byte[]> out = new ArrayList<>();
        c.lockReadLock();
        try { CubeStreamer.encodeRefs(c, List.of(i), List.of(c.cubeHash(i)), out); } finally { c.unlockReadLock(); }
        assertEquals(1, out.size());
        NetworkBuffer b = NetworkBuffer.wrap(out.get(0), 0, out.get(0).length);
        assertEquals((byte) 13, b.read(NetworkBuffer.BYTE));
        assertEquals(1, b.read(NetworkBuffer.VAR_INT));
        long pos = b.read(NetworkBuffer.LONG);
        assertEquals(12, CubeStreamer.posX(pos));
        assertEquals(y >> 4, CubeStreamer.posY(pos));
        assertEquals(13, CubeStreamer.posZ(pos));
        assertEquals(hash(c, i), b.read(NetworkBuffer.LONG));
        assertEquals(1, b.read(NetworkBuffer.VAR_INT), "блок-энтити сундука — в ссылке");
        // отрицательные координаты — pos разбирается обратно
        long q = CubeStreamer.pos(-5, -900, -7);
        assertEquals(-5, CubeStreamer.posX(q));
        assertEquals(-900, CubeStreamer.posY(q));
        assertEquals(-7, CubeStreamer.posZ(q));
    }
}
