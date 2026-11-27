package ink.hoka.odyssey.server.tall;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Загрузка столбцов по ходу быстрого движения (высокий мир, космос). Minestom возвращает игрока назад, если тот
 * вошёл в незагруженный столбец («летел — откинуло, потом пропустило»): при полёте на корабле/в невесомости игрок
 * обгоняет загрузку обзора. Раз в 2 тика по скорости игрока заранее грузим столбцы на 0.5…4 с вперёд (полоса 3 в
 * ширину). Медленно (меньше ~8 блоков/с) — не нужно: обзор успевает сам.
 */
public final class AheadLoad {

    private AheadLoad() {}

    private static final Map<UUID, Pos> LAST = new ConcurrentHashMap<>();
    private static final int[] AHEAD_TICKS = {10, 20, 40, 80};

    public static void register() {
        MinecraftServer.getSchedulerManager().submitTask(() -> {
            for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
                try { tick(p); } catch (Throwable ignored) { }
            }
            return TaskSchedule.tick(2);
        });
        MinecraftServer.getGlobalEventHandler().addListener(net.minestom.server.event.player.PlayerDisconnectEvent.class,
                e -> LAST.remove(e.getPlayer().getUuid()));
    }

    private static void tick(Player p) {
        Instance inst = p.getInstance();
        Pos now = p.getPosition();
        Pos was = LAST.put(p.getUuid(), now);
        if (inst == null || was == null || !TallWorld.isCubes(inst)) return;
        double vx = (now.x() - was.x()) / 2, vz = (now.z() - was.z()) / 2;     // блоков за тик
        double sp = Math.sqrt(vx * vx + vz * vz);
        if (sp < 0.4 || sp > 200) return;                                     // медленно / телепорт
        int pcx = now.blockX() >> 4, pcz = now.blockZ() >> 4;
        for (int t : AHEAD_TICKS) {
            int cx = (int) Math.floor((now.x() + vx * t) / 16), cz = (int) Math.floor((now.z() + vz * t) / 16);
            if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) > 28) break;   // не дальше обзора — их выгрузит обычный порядок
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int x = cx + dx, z = cz + dz;
                if (x == pcx && z == pcz) continue;
                if (inst.getChunk(x, z) == null) inst.loadChunk(x, z);
            }
        }
    }
}
