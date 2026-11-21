package ink.hoka.odyssey.ships;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minestom.server.instance.block.Block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Чертёж корабля: блоки в прямоугольнике sx×sy×sz (как построил админ, поворот 0), место пилота, сундуки,
 * зоны, где игрокам можно строить. Файл: module/ships/templates/&lt;имя&gt;.json.
 */
final class ShipTemplate {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    String name;
    String createdBy;
    int sx, sy, sz;
    /** Палитра состояний блоков; индекс 0 — воздух. */
    List<String> palette = new ArrayList<>();
    /** Индексы палитры по порядку x → z → y, сжатие: пары [индекс, сколько подряд]. */
    List<int[]> runs = new ArrayList<>();
    int[] seat;
    List<int[]> chests = new ArrayList<>();
    /** Зоны строительства: {x1,y1,z1,x2,y2,z2} включительно. */
    List<int[]> zones = new ArrayList<>();

    private transient Block[] cache;

    int index(int x, int y, int z) { return (y * sz + z) * sx + x; }

    /** Блок в локальных координатах (вне чертежа — воздух). */
    Block block(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return Block.AIR;
        return blocks()[index(x, y, z)];
    }

    Block[] blocks() {
        if (cache != null) return cache;
        Block[] pal = new Block[palette.size()];
        for (int i = 0; i < pal.length; i++) {
            Block b = Block.fromState(palette.get(i));
            pal[i] = b == null ? Block.AIR : b;
        }
        Block[] out = new Block[sx * sy * sz];
        int k = 0;
        for (int[] run : runs) for (int j = 0; j < run[1] && k < out.length; j++) out[k++] = pal[run[0]];
        for (; k < out.length; k++) out[k] = Block.AIR;
        return cache = out;
    }

    /** Сколько не-воздушных блоков. */
    int solidCount() {
        int n = 0;
        for (Block b : blocks()) if (!b.isAir()) n++;
        return n;
    }

    boolean inZone(int x, int y, int z) {
        for (int[] q : zones)
            if (x >= q[0] && x <= q[3] && y >= q[1] && y <= q[4] && z >= q[2] && z <= q[5]) return true;
        return false;
    }

    boolean isChest(int x, int y, int z) {
        for (int[] c : chests) if (c[0] == x && c[1] == y && c[2] == z) return true;
        return false;
    }

    /** Собрать из массива блоков (порядок index()). */
    static ShipTemplate of(String name, String by, int sx, int sy, int sz, Block[] blocks) {
        ShipTemplate t = new ShipTemplate();
        t.name = name; t.createdBy = by; t.sx = sx; t.sy = sy; t.sz = sz;
        t.palette.add(Block.AIR.state());
        java.util.Map<String, Integer> idx = new java.util.HashMap<>();
        idx.put(Block.AIR.state(), 0);
        int prev = -1, count = 0;
        for (Block b : blocks) {
            String s = b.state();
            Integer i = idx.get(s);
            if (i == null) { i = t.palette.size(); t.palette.add(s); idx.put(s, i); }
            if (i == prev) count++;
            else { if (prev >= 0) t.runs.add(new int[]{prev, count}); prev = i; count = 1; }
        }
        if (prev >= 0) t.runs.add(new int[]{prev, count});
        t.cache = blocks.clone();
        return t;
    }

    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    static ShipTemplate load(Path file) throws IOException {
        ShipTemplate t = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ShipTemplate.class);
        if (t == null || t.seat == null || t.sx <= 0) throw new IOException("битый чертёж " + file.getFileName());
        if (t.chests == null) t.chests = new ArrayList<>();
        if (t.zones == null) t.zones = new ArrayList<>();
        return t;
    }
}
