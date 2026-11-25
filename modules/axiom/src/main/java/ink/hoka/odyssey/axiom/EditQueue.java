package ink.hoka.odyssey.axiom;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockEntityDataPacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import net.minestom.server.utils.block.BlockUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Очередь правок из set_buffer: за тик применяется не больше sections-per-tick секций (на всех).
 * Запись — chunk.setBlock под замком столбца (на высокой карте TallChunk сам заводит/отдаёт секцию и помечает свет;
 * свет уходит игрокам на следующем тике). Блоки игрокам — одним MultiBlockChangePacket на секцию (на кубовой карте —
 * только тем, у кого секция в окне по высоте: остальным куб придёт целиком, когда окно до него дойдёт).
 */
final class EditQueue {

    record Job(UUID player, WeakReference<Instance> instance, BlockBuffer.Section section, int tries) {
        Job(UUID player, WeakReference<Instance> instance, BlockBuffer.Section section) { this(player, instance, section, 0); }
    }

    private final ConcurrentLinkedDeque<Job> queue = new ConcurrentLinkedDeque<>();   // add — из тика, drop — при выходе игрока
    private final Settings cfg;
    /** Блок по id состояния (кэш Block.fromStateId). */
    private static final Block[] BLOCKS = new Block[Block.statesCount() + 1];

    /** deferred — правок отложено до загрузки куба. */
    long applied, skippedUnloaded, deferred;

    private final ink.hoka.odyssey.api.OdysseyServerApi api;

    EditQueue(Settings cfg, ink.hoka.odyssey.api.OdysseyServerApi api) { this.cfg = cfg; this.api = api; }

    void add(Job j) { queue.add(j); }

    int size() { return queue.size(); }

    void clear() { queue.clear(); }

    void drop(UUID player) { queue.removeIf(j -> j.player().equals(player)); }

    /** Сколько секций применять за тик и не дольше apply-ms-per-tick (большая правка не должна ронять TPS). */
    void tick(org.slf4j.Logger log) {
        long deadline = System.nanoTime() + cfg.applyMsPerTick() * 1_000_000L;
        for (int n = 0; n < cfg.sectionsPerTick(); n++) {
            if (n > 0 && System.nanoTime() > deadline) return;
            Job j = queue.poll();
            if (j == null) return;
            try {
                apply(j);
            } catch (Exception e) {                                     // одна битая секция не должна стопорить очередь
                log.warn("Axiom: секция {},{},{} не применена: {}", j.section().sx(), j.section().sy(), j.section().sz(), e.toString());
            }
        }
    }

    private static Block block(int id) {
        if (id < 0 || id >= BLOCKS.length) return null;
        Block b = BLOCKS[id];
        if (b == null) BLOCKS[id] = b = Block.fromStateId(id);
        return b;
    }

    private void apply(Job j) {
        Instance inst = j.instance().get();
        BlockBuffer.Section s = j.section();
        if (inst == null) return;
        if (!api.isCubeLoaded(inst, s.sx() << 4, s.sy() << 4, s.sz() << 4)) {   // куб не загружен — не писать (§4.2)
            // столбец в памяти, а куба нет (кубы по запросу, ядро 0.4): загрузить и повторить правку в конце очереди
            if (inst.isChunkLoaded(s.sx(), s.sz()) && j.tries() < 3) {
                var at = new Vec(s.sx() << 4, s.sy() << 4, s.sz() << 4);
                api.loadCubes(inst, at, at).thenRun(() -> queue.add(new Job(j.player(), j.instance(), j.section(), j.tries() + 1)));
                deferred++;
            } else skippedUnloaded++;
            return;
        }
        Chunk c = inst.getChunk(s.sx(), s.sz());
        if (c == null || !c.isLoaded()) { skippedUnloaded++; return; }
        if (s.sy() < c.getMinSection() || s.sy() >= c.getMaxSection()) return;
        int bx = s.sx() << 4, by = s.sy() << 4, bz = s.sz() << 4;
        if (cfg.deniedX(bx) && cfg.deniedX(bx + 15)) return;
        if (s.single() && s.nbt().isEmpty()) {                          // заливка одним блоком, а там уже он (небо воздухом)
            int one = s.pal()[0];
            if (one < 0 || c.getSection(s.sy()).blockPalette().singleValue() == one) return;
        }
        long[] changes = new long[4096];
        int nc = 0;
        List<Vec> bePos = new ArrayList<>();
        List<Block> beBlock = new ArrayList<>();
        // synchronized — от сохранения .htw (TallLoader), замок записи столбца — от сохранения регионами кубов
        // (CubeRegionLoader снимает кубы под замком чтения): без него правка могла попасть посреди снимка
        synchronized (c) {
            c.lockWriteLock();
            try {
            for (int i = 0; i < 4096; i++) {
                int id = s.id(i);
                if (id < 0) continue;
                int lx = i & 15, lz = (i >> 4) & 15, ly = i >> 8;
                int x = bx + lx, y = by + ly, z = bz + lz;
                if (cfg.deniedX(x)) continue;
                CompoundBinaryTag tag = s.nbt().get(i);
                if (tag == null && c.getBlock(x, y, z, Block.Getter.Condition.TYPE).stateId() == id) continue;   // уже такой
                Block b = block(id);
                if (b == null) continue;
                if (tag != null && b.registry().blockEntityType() != null) {
                    b = b.withNbt(tag);
                    bePos.add(new Vec(x, y, z));
                    beBlock.add(b);
                }
                c.setBlock(x, y, z, b);
                changes[nc++] = (long) id << 12 | (long) (lx << 8 | lz << 4 | ly);
            }
            } finally { c.unlockWriteLock(); }
        }
        if (nc == 0) return;
        applied++;
        var pkt = new MultiBlockChangePacket(Packing.sectionPos(s.sx(), s.sy(), s.sz()), Arrays.copyOf(changes, nc));
        boolean cubes = Packing.isCubes(inst);
        for (Player p : c.getViewers()) {
            if (cubes && !Packing.inWindow(p, by)) continue;
            p.sendPacket(pkt);
            for (int k = 0; k < bePos.size(); k++) {
                Block b = beBlock.get(k);
                var nbt = BlockUtils.extractClientNbt(b);
                if (nbt != null) p.sendPacket(new BlockEntityDataPacket(bePos.get(k), b.registry().blockEntityType(), nbt));
            }
        }
    }
}
