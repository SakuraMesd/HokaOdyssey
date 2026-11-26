package ink.hoka.odyssey.server.objects;

import ink.hoka.odyssey.gen.GenObjectCodec;
import ink.hoka.odyssey.server.tall.CubeStore;
import ink.hoka.odyssey.server.tall.Cubes;
import ink.hoka.odyssey.server.tall.gen.MinestomGenIds;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Чтение области мира в {@link GenObjectCodec.Builder}: сначала загрузка кубов рамки, потом в тиках пачками (до
 * 262144 блоков за тик). Воздух и structure_void не пишутся (keep-air — воздух пишется), блок-сущности — SNBT без x/y/z/id.
 */
public final class ObjectCapture {

    private ObjectCapture() {}

    public static final int MAX_AXIS = 128, MAX_BLOCKS = 65536;
    public static final long MAX_VOLUME = 2_097_152;
    static final int PER_TICK = 262_144;

    /** Итог: сборщик и число непустых блоков с данными. */
    public record Result(GenObjectCodec.Builder builder, int dataBlocks) {}

    /** Проверка размеров выделения: null — можно, иначе текст. */
    public static String checkSize(Selections.Sel s) {
        int dx = s.maxX() - s.minX() + 1, dy = s.maxY() - s.minY() + 1, dz = s.maxZ() - s.minZ() + 1;
        if (dx > MAX_AXIS || dy > MAX_AXIS || dz > MAX_AXIS) return "выделение больше " + MAX_AXIS + " по оси: " + dx + "×" + dy + "×" + dz;
        if (s.volume() > MAX_VOLUME) return "объём больше " + MAX_VOLUME;
        return null;
    }

    /** Прочитать выделение (keepAir — писать воздух). Будущее завершается в тике. */
    public static CompletableFuture<Result> capture(Instance inst, Selections.Sel s, boolean keepAir) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        Vec min = new Vec(s.minX(), s.minY(), s.minZ()), max = new Vec(s.maxX(), s.maxY(), s.maxZ());
        Cubes.loadCubes(inst, min, max).whenComplete((v, e) -> {
            if (e != null) { out.completeExceptionally(e); return; }
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> run(inst, s, keepAir, out));
        });
        return out;
    }

    private static void run(Instance inst, Selections.Sel s, boolean keepAir, CompletableFuture<Result> out) {
        for (int cx = s.minX() >> 4; cx <= s.maxX() >> 4; cx++)
            for (int cz = s.minZ() >> 4; cz <= s.maxZ() >> 4; cz++)
                for (int cy = s.minY() >> 4; cy <= s.maxY() >> 4; cy++)
                    if (!CubeStore.isReady(inst, cx << 4, cy << 4, cz << 4)) {
                        out.completeExceptionally(new IllegalStateException("кубы выделения не загрузились — повторите"));
                        return;
                    }
        GenObjectCodec.Builder b = new GenObjectCodec.Builder(keepAir);
        int sx = s.maxX() - s.minX() + 1, sz = s.maxZ() - s.minZ() + 1;
        long total = s.volume();
        long[] at = {0};
        int[] data = {0};
        AtomicReference<Task> task = new AtomicReference<>();
        Runnable step = () -> {
            if (out.isDone()) { Task t = task.get(); if (t != null) t.cancel(); return; }
            try {
                long end = Math.min(total, at[0] + PER_TICK);
                for (long i = at[0]; i < end; i++) {
                    int x = s.minX() + (int) (i % sx), z = s.minZ() + (int) ((i / sx) % sz), y = s.minY() + (int) (i / ((long) sx * sz));
                    Block blk = inst.getBlock(x, y, z);
                    if (blk.isAir() && !keepAir) continue;
                    if (b.add(x, y, z, MinestomGenIds.canonical(blk)) && blk.hasNbt() && !blk.isAir()) {
                        String snbt = snbt(blk.nbt());
                        if (snbt != null) { b.data(x, y, z, snbt); data[0]++; }
                    }
                    if (b.size() > MAX_BLOCKS) throw new IllegalStateException("непустых блоков больше " + MAX_BLOCKS);
                }
                at[0] = end;
                if (end >= total) {
                    Task t = task.get();
                    if (t != null) t.cancel();
                    out.complete(new Result(b, data[0]));
                }
            } catch (Throwable t) {
                Task tk = task.get();
                if (tk != null) tk.cancel();
                out.completeExceptionally(t);
            }
        };
        task.set(MinecraftServer.getSchedulerManager().buildTask(step).repeat(TaskSchedule.tick(1)).schedule());
    }

    /** SNBT блок-сущности без x/y/z/id (null — пусто). */
    static String snbt(CompoundBinaryTag nbt) {
        if (nbt == null) return null;
        CompoundBinaryTag c = nbt.remove("x").remove("y").remove("z").remove("id");
        if (c.size() == 0) return null;
        try { return TagStringIO.tagStringIO().asString(c); }
        catch (java.io.IOException e) { return null; }
    }
}
