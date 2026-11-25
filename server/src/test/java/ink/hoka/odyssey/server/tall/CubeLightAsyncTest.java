package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.data.LightData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Этап 2: свет считает свой поток, мир меняется параллельно (правки блоков, загрузка столбцов и кубов). После простоя
 * свет должен совпасть байт в байт с синхронным пересчётом той же истории — промежуточные состояния, прочитанные
 * потоком, исправляют следующие события.
 */
class CubeLightAsyncTest {

    static final int MIN = CubeLight.MIN, CUBES = CubeLight.CUBES;
    static final int FLOOR = 40, R = 2;
    /** Кубы y 64…79 у новых столбцов «не в памяти» — грузятся позже (LOAD). */
    static final int LATE = (64 >> 4) - MIN;
    static final Block[] BLOCKS = { Block.STONE, Block.GLASS, Block.TORCH, Block.GLOWSTONE, Block.AIR, Block.AIR, Block.OAK_SLAB, Block.WATER };

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    /** Операция истории: 0 — загрузить столбец, 1 — блок, 2 — загрузить отложенный куб. */
    record Op(int kind, int x, int y, int z, Block b) {}

    @Test
    void parallelMatchesSync() throws Exception {
        for (long seed = 1; seed <= 3; seed++) {
            CubeLightTest.FakeWorld a = new CubeLightTest.FakeWorld(true, FLOOR, true);
            List<Op> log = new ArrayList<>();
            Random r = new Random(seed);
            try {
                for (int n = 0; n < 700; n++) {
                    Op op = apply(a, r, null);
                    if (op != null) log.add(op);
                    if (r.nextInt(8) == 0) LockSupport.parkNanos(r.nextInt(300_000));   // дать потоку света вклиниться
                }
                assertTrue(log.size() > 300, "история слишком короткая: " + log.size());
                awaitIdle(a.eng);
                assertFalse(a.eng.takeOut().isEmpty(), "готовый свет должен лечь в outbox для рассылки");
            } finally {
                a.eng.stop();
                Thread t = a.eng.worker;
                if (t != null) t.join(10_000);
            }
            a.eng.process();                                                // промахи рамки — как сделал бы поток

            CubeLightTest.FakeWorld b = new CubeLightTest.FakeWorld(true, FLOOR);   // та же история синхронно
            for (Op op : log) { apply(b, null, op); b.eng.process(); }

            int x0 = -R * 16, s = (2 * R + 1) * 16, y0 = 16, sy = 96;
            for (boolean sky : new boolean[]{true, false}) {
                byte[] la = a.eng.box(x0, y0, x0, s, sy, s, sky), lb = b.eng.box(x0, y0, x0, s, sy, s, sky);
                int diff = 0, first = -1;
                for (int q = 0; q < la.length; q++) if (la[q] != lb[q]) { diff++; if (first < 0) first = q; }
                if (diff > 0) {
                    int x = first % s + x0, z = first / s % s + x0, y = first / s / s + y0;
                    fail("сид " + seed + ", " + (sky ? "небо" : "блоки") + ": расхождений " + diff + ", первое (" + x + ", " + y + ", " + z
                            + ") параллельно " + la[first] + ", синхронно " + lb[first]);
                }
            }
        }
    }

    /** Применить случайную операцию (r != null; ответ — она или null, если невозможна) или записанную (op). */
    private static Op apply(CubeLightTest.FakeWorld w, Random r, Op op) {
        if (op == null) {
            int k = r.nextInt(10);
            int cx = r.nextInt(2 * R + 1) - R, cz = r.nextInt(2 * R + 1) - R;
            CubeLightTest.FakeCol c = w.cols.get(CubeLight.colKey(cx, cz));
            if (k == 0 || c == null) op = c == null ? new Op(0, cx, 0, cz, null) : null;
            else if (k == 1) op = c.notReady.get(LATE) ? new Op(2, cx, 0, cz, null) : null;
            else {
                int x = cx * 16 + r.nextInt(16), z = cz * 16 + r.nextInt(16), y = 34 + r.nextInt(50);
                if (!c.ready((y >> 4) - MIN)) return null;                 // куб не в памяти — запись нельзя (§4.2)
                op = new Op(1, x, y, z, BLOCKS[r.nextInt(BLOCKS.length)]);
            }
            if (op == null) return null;
        }
        final Op o = op;
        switch (o.kind) {
            case 0 -> {
                w.cols.computeIfAbsent(CubeLight.colKey(o.x, o.z), q -> {       // столбец виден миру уже собранным
                    CubeLightTest.FakeCol c = new CubeLightTest.FakeCol(o.x, o.z);
                    for (int y = 0; y <= FLOOR; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                        c.set(o.x * 16 + x, y, o.z * 16 + z, y == 0 ? Block.BEDROCK : Block.STONE);
                    c.notReady.set(LATE);
                    return c;
                });
                w.eng.columnLoadedAt(o.x, o.z);
            }
            case 1 -> w.set(op.x, op.y, op.z, op.b);
            case 2 -> {
                w.cols.get(CubeLight.colKey(op.x, op.z)).notReady.clear(LATE);
                w.eng.cubeLoadedAt(op.x, LATE + MIN, op.z);
            }
        }
        return op;
    }

    private static void awaitIdle(CubeLight e) {
        long t0 = System.nanoTime();
        int calm = 0;
        while (calm < 5) {                                                  // несколько замеров подряд: поток мог взять партию между ними
            assertTrue(System.nanoTime() - t0 < 30_000_000_000L, "поток света не разобрал очередь за 30 с");
            calm = e.idle() ? calm + 1 : 0;
            LockSupport.parkNanos(2_000_000);
        }
    }

    /** Настоящие CubeColumn и поток света: факел через границу столбцов, выдача игроку — только опубликованное. */
    @Test
    void realColumnsWithLightThread() {
        var key = MinecraftServer.getDimensionTypeRegistry().register(net.kyori.adventure.key.Key.key("hoka:tall_light_async_test"),
                CubeLightTest.rethrow(() -> TallWorld.tall(net.minestom.server.world.DimensionType.builder().build(), TallWorld.MIN_Y, TallWorld.HEIGHT, true, 0)));
        var inst = MinecraftServer.getInstanceManager().createInstanceContainer(key);
        inst.setChunkLoader(net.minestom.server.instance.ChunkLoader.noop());
        inst.setChunkSupplier(CubeColumn::generated);
        TallWorld.markCubes(inst);
        CubeLight eng = CubeLight.asyncFor(inst);
        try {
            assertNotNull(eng.worker, "поток света запущен");
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) inst.loadChunk(cx, cz).join();
            awaitIdle(eng);
            CubeColumn c = (CubeColumn) inst.getChunk(0, 0);
            inst.setBlock(15, 41, 8, Block.TORCH);
            awaitIdle(eng);
            LightData d = CubeLight.data(c, List.of(2 - MIN), true);       // без пересчёта: поток уже опубликовал
            assertTrue(d.blockMask().get(2 - MIN + 1), "свет факела опубликован потоком");
            LightData e = CubeLight.data((CubeColumn) inst.getChunk(1, 0), List.of(2 - MIN), true);
            assertTrue(e.blockMask().get(2 - MIN + 1), "и в соседнем столбце");
            assertEquals(13, eng.level(16, 41, 8, false), "соседний столбец");
            inst.setBlock(15, 41, 8, Block.AIR);
            awaitIdle(eng);
            LightData g = CubeLight.data((CubeColumn) inst.getChunk(1, 0), List.of(2 - MIN), true);
            assertTrue(g.emptyBlockMask().get(2 - MIN + 1), "факел убрали — бит «пусто»");
        } finally {
            eng.stop();
            MinecraftServer.getInstanceManager().unregisterInstance(inst);
        }
    }
}
