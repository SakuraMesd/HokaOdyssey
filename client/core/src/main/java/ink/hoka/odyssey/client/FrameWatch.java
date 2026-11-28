package ink.hoka.odyssey.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сторож кадров (отладка высокого мира): отдельный поток следит за главным; кадр дольше LIMIT_MS — снимаем, чем
 * занята игра в этот момент (стек главного потока), и пишем в лог «[фриз] …». Только на нашем сервере с высоким
 * миром (TallWorld.net). Дёшево: поток просыпается раз в 5 мс и смотрит одно число.
 */
public final class FrameWatch {

    private FrameWatch() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");
    private static final long LIMIT_MS = 40;
    private static volatile long frameStart;
    private static volatile boolean reported;
    private static volatile Thread render;
    private static long lastReport;

    /** 0.49: средняя длительность кадра (EMA, нс) — от неё бюджеты света и кубов на кадр. */
    private static long avgNs = 4_000_000L;
    /** Длительности кадров за окно статистики (мкс). */
    private static int[] durs = new int[4096];
    private static int nDur;
    private static long statStart;
    private static final long STAT_NS = 10_000_000_000L;

    /** Средний кадр, нс (главный поток). */
    public static long avgFrameNs() { return avgNs; }

    /** Бюджет работы на кадр: доля среднего кадра в пределах [min, max], нс. */
    public static long budgetNs(double share, long min, long max) {
        return Math.max(min, Math.min(max, (long) (avgNs * share)));
    }

    /** Главный поток: начало кадра. */
    public static void frameStart() {
        long now = System.nanoTime();
        long was = frameStart;
        if (was != 0 && reported) {
            LOG.info("[фриз] кадр длился {} мс", (now - was) / 1_000_000);
        }
        if (was != 0) {
            long d = now - was;
            avgNs += (Math.min(d, 100_000_000L) - avgNs) >> 4;             // EMA ~16 кадров; фриз не раздувает бюджет надолго
            if (TallWorld.net) stat(now, d);
        }
        frameStart = now;
        reported = false;
        if (render == null) { render = Thread.currentThread(); start(); }
    }

    /**
     * 0.49 (K): статистика ровности кадров за 10 с — в лог: число кадров, p50/p99/макс и сколько дольше 10 мс.
     * «700 FPS ощущаются как 40» видно по p99 и счётчику >10 мс, а не по среднему.
     */
    private static void stat(long now, long d) {
        if (statStart == 0) { statStart = now; nDur = 0; return; }
        if (nDur == durs.length) durs = java.util.Arrays.copyOf(durs, durs.length * 2);
        durs[nDur++] = (int) Math.min(d / 1000, Integer.MAX_VALUE);
        if (now - statStart < STAT_NS) return;
        int n = nDur;
        int[] a = java.util.Arrays.copyOf(durs, n);
        java.util.Arrays.sort(a);
        int slow = 0;
        for (int i = n - 1; i >= 0 && a[i] > 10_000; i--) slow++;
        double secs = (now - statStart) / 1e9;
        LOG.info("[кадры] {} с: {} кадров ({} FPS), p50 {} мс, p99 {} мс, p99.9 {} мс, макс {} мс, дольше 10 мс: {}",
                String.format("%.1f", secs), n, Math.round(n / secs), ms(a[n / 2]), ms(a[Math.min(n - 1, (int) (n * 0.99))]),
                ms(a[Math.min(n - 1, (int) (n * 0.999))]), ms(a[n - 1]), slow);
        statStart = now;
        nDur = 0;
        if (durs.length > 65536) durs = new int[4096];
    }

    private static String ms(int us) { return String.format("%.2f", us / 1000.0); }

    private static void start() {
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(Perf.SAMPLE_MS); } catch (InterruptedException e) { return; }
                long s = frameStart;
                if (s != 0 && Perf.on()) {                                      // 0.90: профилировщик (файл hoka-perf.on)
                    Perf.renderThread = render;
                    Perf.sample(render, (System.nanoTime() - s) / 1_000_000);
                    Perf.report(render);
                }
                if (!TallWorld.net || reported) continue;
                if (s == 0) continue;
                long ms = (System.nanoTime() - s) / 1_000_000;
                if (ms < LIMIT_MS) continue;
                reported = true;
                long now = System.currentTimeMillis();
                if (now - lastReport < 250) continue;                          // не засыпать лог
                lastReport = now;
                StackTraceElement[] st = render.getStackTrace();
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < Math.min(st.length, 28); i++) b.append("\n    at ").append(st[i]);
                LOG.info("[фриз] кадр уже {} мс, главный поток сейчас:{}", ms, b);
            }
        }, "hoka-frame-watch");
        t.setDaemon(true);
        t.start();
    }
}
