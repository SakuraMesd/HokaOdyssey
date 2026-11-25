package ink.hoka.odyssey.server.tall.gen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Прогресс pregen — maps/&lt;карта&gt;/pregen.yml (пишется атомарно): параметры, state, watermark (все единицы
 * спирали ниже готовы) и done_above (готовые выше watermark — работа параллельная). Возобновление после
 * перезапуска: единицы ниже watermark и из done_above пропускаются.
 */
public final class PregenProgress {

    /** Область в столбцах (включительно), Y-ограничение (null — границы генератора), потоки, центр спирали. */
    public int x1, z1, x2, z2, threads, centerX, centerZ;
    public Integer ymin, ymax;
    public String gen = "";
    public long seed;
    /** Отпечаток генератора (16 кубов) — код поменяли без смены version: продолжать нельзя. null — старый файл. */
    public Integer fp;
    public String state = "running";
    private int watermark;
    private final TreeSet<Integer> doneAbove = new TreeSet<>();

    public synchronized boolean isDone(int i) { return i < watermark || doneAbove.contains(i); }

    public synchronized void markDone(int i) {
        if (i < watermark) return;
        doneAbove.add(i);
        while (doneAbove.remove(watermark)) watermark++;
    }

    public synchronized int watermark() { return watermark; }

    public synchronized int doneCount() { return watermark + doneAbove.size(); }

    public synchronized String encode() {
        StringBuilder b = new StringBuilder("# pregen (ядро Hoka Odyssey) — не править во время работы\n");
        b.append("gen: ").append(gen).append('\n');
        b.append("seed: ").append(seed).append('\n');
        if (fp != null) b.append("fp: ").append(fp).append('\n');
        b.append("area: ").append(x1).append(' ').append(z1).append(' ').append(x2).append(' ').append(z2).append('\n');
        b.append("center: ").append(centerX).append(' ').append(centerZ).append('\n');
        if (ymin != null && ymax != null) b.append("y: ").append(ymin).append(' ').append(ymax).append('\n');
        b.append("threads: ").append(threads).append('\n');
        b.append("state: ").append(state).append('\n');
        b.append("watermark: ").append(watermark).append('\n');
        b.append("done_above: [");
        int k = 0;
        for (int i : doneAbove) { if (k++ > 0) b.append(", "); b.append(i); }
        b.append("]\n");
        return b.toString();
    }

    public static PregenProgress decode(String s) {
        PregenProgress p = new PregenProgress();
        for (String line : s.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c).trim(), v = line.substring(c + 1).trim();
            String[] a = v.split("\\s+");
            switch (k) {
                case "gen" -> p.gen = v;
                case "seed" -> p.seed = Long.parseLong(v);
                case "fp" -> p.fp = Integer.parseInt(v);
                case "area" -> { p.x1 = Integer.parseInt(a[0]); p.z1 = Integer.parseInt(a[1]); p.x2 = Integer.parseInt(a[2]); p.z2 = Integer.parseInt(a[3]); }
                case "center" -> { p.centerX = Integer.parseInt(a[0]); p.centerZ = Integer.parseInt(a[1]); }
                case "y" -> { p.ymin = Integer.parseInt(a[0]); p.ymax = Integer.parseInt(a[1]); }
                case "threads" -> p.threads = Integer.parseInt(v);
                case "state" -> p.state = v;
                case "watermark" -> p.watermark = Integer.parseInt(v);
                case "done_above" -> {
                    String in = v.replace("[", "").replace("]", "").trim();
                    if (!in.isEmpty()) for (String x : in.split(",")) p.doneAbove.add(Integer.parseInt(x.trim()));
                }
                default -> { }
            }
        }
        while (p.doneAbove.remove(p.watermark)) p.watermark++;
        return p;
    }

    public void save(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, encode(), StandardCharsets.UTF_8);
        try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    public static PregenProgress load(Path file) throws IOException { return decode(Files.readString(file, StandardCharsets.UTF_8)); }
}
