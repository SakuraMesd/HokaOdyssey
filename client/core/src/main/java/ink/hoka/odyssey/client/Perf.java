package ink.hoka.odyssey.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.90 Профилировщик главного потока (включается файлом {@code hoka-perf.on} в папке игры — без команд и перезапуска).
 * Поток {@link FrameWatch} раз в {@link #SAMPLE_MS} мс снимает стек главного потока: где он сейчас — в целом и отдельно
 * в медленных кадрах (дольше {@link #SLOW_MS} мс). Раз в 10 с в лог «[проф]»: топ мест (верхний кадр не из JDK и
 * верхний кадр нашего кода), сборки мусора (сколько и сколько мс) и сколько памяти выделил главный поток. По этому
 * видно, что держит FPS и что даёт рывки, не гадая. Стоимость: снимок стека ≈ десятки мкс, 250 раз в секунду.
 */
public final class Perf {

    private Perf() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");
    static final int SAMPLE_MS = 4;
    static final long SLOW_MS = 8;
    private static final long WINDOW_NS = 10_000_000_000L;

    private static volatile boolean on;
    private static long checkedAt, windowStart;
    private static final Map<String, int[]> ALL = new HashMap<>(), SLOW = new HashMap<>(), OURS = new HashMap<>();
    private static int samples, slowSamples;
    private static long gcCount0, gcMs0, alloc0;

    /** Включён ли (файл hoka-perf.on; проверка раз в 2 с). */
    static boolean on() {
        long now = System.nanoTime();
        if (now - checkedAt > 2_000_000_000L) {
            checkedAt = now;
            boolean was = on;
            on = Files.exists(Path.of("hoka-perf.on"));
            if (on && !was) { LOG.info("[проф] включён (файл hoka-perf.on)"); reset(now); }
        }
        return on;
    }

    /** Снимок стека главного потока (поток сторожа). frameMs — сколько уже длится текущий кадр. */
    static void sample(Thread render, long frameMs) {
        StackTraceElement[] st = render.getStackTrace();
        if (st.length == 0) return;
        String top = null, ours = null;
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if (top == null && !c.startsWith("java.") && !c.startsWith("jdk.") && !c.startsWith("sun.")) top = name(e);
            if (ours == null && c.startsWith("ink.hoka.")) ours = name(e);
            if (top != null && ours != null) break;
        }
        if (top == null) top = name(st[0]);
        synchronized (Perf.class) {
            samples++;
            ALL.computeIfAbsent(top, k -> new int[1])[0]++;
            if (ours != null) OURS.computeIfAbsent(ours, k -> new int[1])[0]++;
            if (frameMs >= SLOW_MS) {
                slowSamples++;
                String key = ours != null && !ours.equals(top) ? top + " ← " + ours : top;
                SLOW.computeIfAbsent(key, k -> new int[1])[0]++;
            }
        }
    }

    private static String name(StackTraceElement e) {
        String c = e.getClassName();
        int dot = c.lastIndexOf('.');
        return (dot >= 0 ? c.substring(dot + 1) : c) + "." + e.getMethodName();
    }

    /** Раз в 10 с (из потока сторожа): итог окна в лог. */
    static void report(Thread render) {
        long now = System.nanoTime();
        if (windowStart == 0) { reset(now); return; }
        if (now - windowStart < WINDOW_NS) return;
        long gcN = 0, gcMs = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcN += Math.max(0, g.getCollectionCount());
            gcMs += Math.max(0, g.getCollectionTime());
        }
        long alloc = allocated(render);
        StringBuilder b = new StringBuilder();
        synchronized (Perf.class) {
            double secs = (now - windowStart) / 1e9;
            b.append(String.format(java.util.Locale.ROOT, "[проф] %.0f с: снимков %d (в медленных кадрах %d); сборки мусора %d за %d мс; главный поток выделил %d МБ (%.0f МБ/с)",
                    secs, samples, slowSamples, gcN - gcCount0, gcMs - gcMs0, (alloc - alloc0) >> 20, ((alloc - alloc0) >> 20) / secs));
            top(b, "\n  всё время:", ALL, samples, 14);
            top(b, "\n  наш код (сколько всего времени):", OURS, samples, 10);
            top(b, "\n  медленные кадры (>" + SLOW_MS + " мс):", SLOW, slowSamples, 12);
            ALL.clear(); SLOW.clear(); OURS.clear();
            samples = slowSamples = 0;
        }
        gcCount0 = gcN; gcMs0 = gcMs; alloc0 = alloc;
        windowStart = now;
        LOG.info(b.toString());
    }

    private static void top(StringBuilder b, String title, Map<String, int[]> m, int total, int n) {
        if (total == 0 || m.isEmpty()) return;
        b.append(title);
        List<Map.Entry<String, int[]>> l = new ArrayList<>(m.entrySet());
        l.sort((x, y) -> y.getValue()[0] - x.getValue()[0]);
        for (int i = 0; i < Math.min(n, l.size()); i++)
            b.append(String.format(java.util.Locale.ROOT, "\n    %5.1f%%  %s", 100.0 * l.get(i).getValue()[0] / total, l.get(i).getKey()));
    }

    private static void reset(long now) {
        synchronized (Perf.class) { ALL.clear(); SLOW.clear(); OURS.clear(); samples = slowSamples = 0; }
        windowStart = now;
        long gcN = 0, gcMs = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) { gcN += Math.max(0, g.getCollectionCount()); gcMs += Math.max(0, g.getCollectionTime()); }
        gcCount0 = gcN; gcMs0 = gcMs;
        Thread r = renderThread;
        alloc0 = r == null ? 0 : allocated(r);
    }

    static volatile Thread renderThread;

    private static long allocated(Thread t) {
        try {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean tm)
                return Math.max(0, tm.getThreadAllocatedBytes(t.threadId()));
        } catch (Throwable ignored) { }
        return 0;
    }
}
