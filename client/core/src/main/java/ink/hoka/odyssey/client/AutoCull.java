package ink.hoka.odyssey.client;

/**
 * 0.90 Выбор обхода секций Sodium в высоком мире: граф видимости (отсекает то, что за стенами, но проходит КАЖДУЮ
 * секцию в пирамиде взгляда, и пустые — в космосе это десятки тысяч секций воздуха за кадр, 70% времени кадра) или
 * дерево непустых секций (только секции с геометрией, без отсечения стенами).
 * <p>Раз в {@link #PROBE_NS} (и при сдвиге камеры на {@link #MOVE_SECTIONS}+ секций) — проба: один обход графом, следом
 * один деревом; считаем, сколько секций каждый отдал на отрисовку и сколько длился. Дерево — если рисует почти
 * столько же (стены почти ничего не скрывают: космос, открытое небо) и быстрее; иначе граф (внутри станции, в
 * пещере). Между пробами — выбранный способ.</p>
 */
public final class AutoCull {

    private AutoCull() {}

    static final long PROBE_NS = 1_500_000_000L;
    static final int MOVE_SECTIONS = 2;
    /** Дерево рисует не больше чем граф × 1,3 + 64 секции — разница в отрисовке мала. */
    static final double DRAW_RATIO = 1.3;
    static final int DRAW_SLACK = 64;

    public static volatile boolean enabled = true;

    private static boolean useTree;
    /** 0 — обычный кадр, 1 — проба графом, 2 — проба деревом. */
    private static int probe;
    private static long probeAt;
    private static int px, py, pz;
    private static int graphDrawn, treeDrawn;
    private static long graphNs, treeNs;
    /** Для F3 / лога. */
    public static String last = "";

    /** Перед обходом: брать ли дерево. Камера — секция. */
    public static boolean wantTree(int cx, int cy, int cz, long now) {
        if (!enabled) { probe = 0; return false; }
        if (probe == 0 && (now - probeAt >= PROBE_NS
                || Math.abs(cx - px) >= MOVE_SECTIONS || Math.abs(cy - py) >= MOVE_SECTIONS || Math.abs(cz - pz) >= MOVE_SECTIONS)) {
            probe = 1;
            probeAt = now;
            px = cx; py = cy; pz = cz;
        }
        if (probe == 1) return false;
        if (probe == 2) return true;
        return useTree;
    }

    /** После обхода: сколько секций в списках отрисовки и сколько длился. tree — каким способом шёл. */
    public static void result(boolean tree, int drawn, long ns) {
        if (probe == 1 && !tree) { graphDrawn = drawn; graphNs = ns; probe = 2; return; }
        if (probe == 2 && tree) {
            treeDrawn = drawn; treeNs = ns; probe = 0;
            useTree = decide(graphDrawn, graphNs, treeDrawn, treeNs);
            last = (useTree ? "дерево" : "граф") + " (граф " + graphDrawn + " секц. " + graphNs / 1000 + " мкс, дерево "
                    + treeDrawn + " секц. " + treeNs / 1000 + " мкс)";
            return;
        }
        if (probe != 0) probe = 0;                                            // пробу прервал другой путь (камера в небе) — заново позже
    }

    static boolean decide(int gDrawn, long gNs, int tDrawn, long tNs) {
        return tDrawn <= gDrawn * DRAW_RATIO + DRAW_SLACK && tNs < gNs * 0.8;
    }

    /** Тесты. */
    static void reset() { useTree = false; probe = 0; probeAt = 0; px = py = pz = 0; last = ""; }
    static boolean usingTree() { return useTree; }
}
