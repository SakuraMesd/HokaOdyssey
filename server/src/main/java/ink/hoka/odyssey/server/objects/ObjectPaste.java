package ink.hoka.odyssey.server.objects;

import ink.hoka.odyssey.gen.GenCube;
import ink.hoka.odyssey.gen.GenObject;
import ink.hoka.odyssey.gen.GenPlacer;
import ink.hoka.odyssey.server.tall.CubeStore;
import ink.hoka.odyssey.server.tall.Cubes;
import ink.hoka.odyssey.server.tall.gen.MinestomGenIds;
import net.kyori.adventure.nbt.TagStringIO;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Вставка объекта в мир для проверки (/hoobj paste) тем же кодом, что у генератора (GenPlacer: поворот, отражение,
 * replace): кубы рамки грузятся, потом блоки ставятся пачками до 4096 за тик. Незагруженные кубы пропускаются.
 * Последняя вставка игрока запоминается для /hoobj undo (старые блоки).
 */
public final class ObjectPaste {

    private ObjectPaste() {}

    static final int PER_TICK = 4096;

    record Change(int x, int y, int z, Block block) {}

    /** Запись отмены: мир и старые блоки. */
    record Undo(Instance inst, List<Change> old) {}

    private static final Map<UUID, Undo> UNDO = new ConcurrentHashMap<>();

    /** Итог вставки: поставлено блоков, пропущено кубов (не загрузились). */
    public record Result(int blocks, int skippedCubes) {}

    /** Рамка варианта объекта (мировые блоки, включительно): x0,y0,z0,x1,y1,z1. */
    public static int[] bounds(GenObject o, int ax, int ay, int az, int rot, boolean mir) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i < o.count(); i++) {
            int x = GenObject.rx(o.dx(i), o.dz(i), rot, mir), z = GenObject.rz(o.dx(i), o.dz(i), rot, mir);
            x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z);
        }
        return new int[]{ax + x0, ay + o.minDy(), az + z0, ax + x1, ay + o.maxDy(), az + z1};
    }

    /** Поставить объект якорем в (ax, ay, az). force — затирать всё. */
    public static CompletableFuture<Result> paste(Player p, Instance inst, GenObject o, int ax, int ay, int az, int rot, boolean mir, boolean force) {
        GenObject obj = force ? o.withMeta(withReplaceAll(o.meta().raw())) : o;
        int[] b = bounds(obj, ax, ay, az, rot, mir);
        CompletableFuture<Result> out = new CompletableFuture<>();
        Cubes.loadCubes(inst, new Vec(b[0], b[1], b[2]), new Vec(b[3], b[4], b[5])).whenComplete((v, e) -> {
            if (e != null) { out.completeExceptionally(e); return; }
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
                try { apply(p, inst, obj, ax, ay, az, rot, mir, b, out); }
                catch (Throwable t) { out.completeExceptionally(t); }
            });
        });
        return out;
    }

    private static Map<String, Object> withReplaceAll(Map<String, Object> m) {
        Map<String, Object> r = new LinkedHashMap<>(m);
        r.put("replace", "all");
        return r;
    }

    private static void apply(Player p, Instance inst, GenObject o, int ax, int ay, int az, int rot, boolean mir, int[] b,
                              CompletableFuture<Result> out) {
        GenPlacer placer = GenPlacer.of(MinestomGenIds.get(), List.of(o));
        List<int[]> sets = new ArrayList<>();                                 // x, y, z, state
        Map<Long, String> data = new HashMap<>();
        int skipped = 0;
        for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++)
            for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++)
                for (int cy = b[1] >> 4; cy <= b[4] >> 4; cy++) {
                    if (!CubeStore.isReady(inst, cx << 4, cy << 4, cz << 4)) { skipped++; continue; }
                    placer.paste(new WorldCube(inst, cx, cy, cz, sets, data), o, ax, ay, az, rot, mir);
                }
        List<Change> old = new ArrayList<>(sets.size());
        int total = sets.size();
        int sk = skipped;
        int[] at = {0};
        AtomicReference<Task> task = new AtomicReference<>();
        Runnable step = () -> {
            try {
                int end = Math.min(total, at[0] + PER_TICK);
                for (int i = at[0]; i < end; i++) {
                    int[] s = sets.get(i);
                    Block blk = Block.fromStateId(s[3]);
                    if (blk == null) continue;
                    String snbt = data.get(key(s[0], s[1], s[2]));
                    if (snbt != null) try { blk = blk.withNbt(TagStringIO.tagStringIO().asCompound(snbt)); } catch (java.io.IOException ignored) {}
                    old.add(new Change(s[0], s[1], s[2], inst.getBlock(s[0], s[1], s[2])));
                    inst.setBlock(s[0], s[1], s[2], blk);
                }
                at[0] = end;
                if (end >= total) {
                    Task t = task.get();
                    if (t != null) t.cancel();
                    if (p != null) UNDO.put(p.getUuid(), new Undo(inst, old));
                    out.complete(new Result(total, sk));
                }
            } catch (Throwable t) {
                Task tk = task.get();
                if (tk != null) tk.cancel();
                if (p != null && !old.isEmpty()) UNDO.put(p.getUuid(), new Undo(inst, old));
                out.completeExceptionally(t);
            }
        };
        task.set(MinecraftServer.getSchedulerManager().buildTask(step).repeat(TaskSchedule.tick(1)).schedule());
    }

    /** Вернуть последнюю вставку игрока. Ответ — сколько блоков (−1 — нечего). */
    public static int undo(Player p) {
        Undo u = UNDO.remove(p.getUuid());
        if (u == null) return -1;
        List<Change> l = u.old();
        int[] at = {l.size() - 1};
        AtomicReference<Task> task = new AtomicReference<>();
        task.set(MinecraftServer.getSchedulerManager().buildTask(() -> {
            int end = Math.max(-1, at[0] - PER_TICK);
            for (int i = at[0]; i > end; i--) {                               // в обратном порядке
                Change c = l.get(i);
                try { u.inst().setBlock(c.x(), c.y(), c.z(), c.block()); } catch (RuntimeException ignored) {}
            }
            at[0] = end;
            if (end < 0) { Task t = task.get(); if (t != null) t.cancel(); }
        }).repeat(TaskSchedule.tick(1)).schedule());
        return l.size();
    }

    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    /** Куб мира как GenCube: чтение — из инстанса, запись — в список изменений. */
    static final class WorldCube implements GenCube {
        private final Instance inst;
        private final int cx, cy, cz;
        private final List<int[]> sets;
        private final Map<Long, String> data;
        private final Map<Integer, Integer> local = new HashMap<>();

        WorldCube(Instance inst, int cx, int cy, int cz, List<int[]> sets, Map<Long, String> data) {
            this.inst = inst; this.cx = cx; this.cy = cy; this.cz = cz; this.sets = sets; this.data = data;
        }

        @Override public int cx() { return cx; }
        @Override public int cy() { return cy; }
        @Override public int cz() { return cz; }
        @Override public long seed() { return 0; }
        @Override public boolean secrets() { return true; }

        @Override
        public void set(int lx, int ly, int lz, int state) {
            local.put(lx | ly << 4 | lz << 8, state);
            sets.add(new int[]{x0() + lx, y0() + ly, z0() + lz, state});
        }

        @Override public void setAbs(int x, int y, int z, int state) {
            if (x >> 4 == cx && y >> 4 == cy && z >> 4 == cz) set(x & 15, y & 15, z & 15, state);
        }

        @Override
        public int get(int lx, int ly, int lz) {
            Integer v = local.get(lx | ly << 4 | lz << 8);
            return v != null ? v : inst.getBlock(x0() + lx, y0() + ly, z0() + lz).stateId();
        }

        @Override public void fill(int state) { throw new UnsupportedOperationException(); }
        @Override public void fillY(int ly0, int ly1, int state) { throw new UnsupportedOperationException(); }
        @Override public void biome(int bx, int by, int bz, int biome) {}
        @Override public void fillBiome(int biome) {}
        @Override public void secret(int lx, int ly, int lz, int state) { set(lx, ly, lz, state); }
        @Override public void data(int lx, int ly, int lz, String snbt) { data.put(key(x0() + lx, y0() + ly, z0() + lz), snbt); }
    }
}
