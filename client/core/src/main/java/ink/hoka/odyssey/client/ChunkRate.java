package ink.hoka.odyssey.client;

/**
 * 0.63: сколько столбцов за тик просить у сервера (ответ на ChunkBatchFinished → Minestom targetChunksPerTick).
 * Ваниль меряет «время пачки / столбцов» по часам; у нас Finished ждёт в CubeQueue за всеми кубами окна (десятки
 * тысяч меток) — время пачки огромное, игра просила ~0.5–2 столбца за тик, и окно 65×65 шло минуту и дольше.
 * Здесь — регулятор по очереди: хвоста (кубы + ванильные пакеты + генерация) меньше 0.75 с работы — просить больше
 * (×1.4 + 1), больше 3 с — меньше (×0.6); пропускная способность — сколько очередь реально применяла за секунду.
 * Только на кубовых картах Hoka (TallWorld.net), иначе ванильное значение.
 */
public final class ChunkRate {

    private ChunkRate() {}

    static final float MIN = 1f, MAX = 48f, START = 8f;
    private static float rate = START;
    private static long lastAt, lastDone;
    private static int lastBacklog;
    private static double perSec = 1500;

    /** Значение для ServerboundChunkBatchReceived (главный поток). */
    public static float desired(float vanilla) {
        if (!TallWorld.net) return vanilla;
        long now = System.nanoTime();
        long done = CubeQueue.applied + CubeQueue.replayed + GenPool.inserted;
        int backlog = CubeQueue.size() + GenPool.pendingCount();
        if (lastAt != 0) {
            double dt = (now - lastAt) / 1e9;
            // мерить пропускную способность — только когда было что применять (иначе меряем поток пакетов)
            if (dt > 0.1 && lastBacklog > 256) perSec = Math.max(300, perSec * 0.6 + (done - lastDone) / dt * 0.4);
        }
        lastAt = now;
        lastDone = done;
        lastBacklog = backlog;
        double secs = backlog / perSec;
        if (secs < 0.75) rate = Math.min(MAX, rate * 1.4f + 1f);
        else if (secs > 3) rate = Math.max(MIN, rate * 0.6f);
        return rate;
    }

    /** Новый мир / выход — с начала. */
    public static void reset() { rate = START; lastAt = 0; lastBacklog = 0; }

    public static String stats() {
        return String.format(java.util.Locale.ROOT, "столбцов за тик %.1f (очередь %.0f/с)", rate, perSec);
    }
}
