package ink.hoka.odyssey.stations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Пакеты клиентскому модулю stations (канал clientmods «stations»). Сервер → клиент, DataOutputStream:
 * <pre>
 * 1 REGIONS    UTF map; byte admin; int cNormal, cVacuum, cMap, cSel (RGB);
 *              int n; n × (UTF id, UTF title, byte grav (0 normal, 1 vacuum, 2 map), int nb, nb × 6 int (min xyz, max xyz))
 * 2 SELECTION  byte has (бит 0 — точка 1, бит 1 — точка 2); [3 int]; [3 int]   (точки на текущей карте игрока)
 * 3 TRANSITION byte kind (1 — вошёл в регион, 0 — вышел наружу); UTF title; UTF sub; byte grav (0..2; 3 — снаружи)
 * 4 SHOW       int seconds (0 — выключить); UTF id ("" — все регионы)
 * </pre>
 * Клиент → сервер пока ничего не шлёт.
 */
final class Net {

    static final int REGIONS = 1, SELECTION = 2, TRANSITION = 3, SHOW = 4;

    private Net() {}

    interface Body { void write(DataOutputStream out) throws IOException; }

    static byte[] pack(int op, Body body) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(op);
            body.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toByteArray();
    }

    static byte[] regions(String map, boolean admin, int[] colors, List<Region> regions) {
        return pack(REGIONS, out -> {
            out.writeUTF(map);
            out.writeByte(admin ? 1 : 0);
            for (int i = 0; i < 4; i++) out.writeInt(colors[i]);
            out.writeInt(regions.size());
            for (Region r : regions) {
                out.writeUTF(r.id());
                out.writeUTF(r.title());
                out.writeByte(r.gravity().code());
                out.writeInt(r.boxes().size());
                for (Box x : r.boxes()) {
                    out.writeInt(x.minX()); out.writeInt(x.minY()); out.writeInt(x.minZ());
                    out.writeInt(x.maxX()); out.writeInt(x.maxY()); out.writeInt(x.maxZ());
                }
            }
        });
    }

    static byte[] selection(int[] p1, int[] p2) {
        return pack(SELECTION, out -> {
            out.writeByte((p1 != null ? 1 : 0) | (p2 != null ? 2 : 0));
            if (p1 != null) for (int v : p1) out.writeInt(v);
            if (p2 != null) for (int v : p2) out.writeInt(v);
        });
    }

    static byte[] transition(boolean enter, String title, String sub, int grav) {
        return pack(TRANSITION, out -> {
            out.writeByte(enter ? 1 : 0);
            out.writeUTF(title);
            out.writeUTF(sub);
            out.writeByte(grav);
        });
    }

    static byte[] show(int seconds, String id) {
        return pack(SHOW, out -> {
            out.writeInt(seconds);
            out.writeUTF(id == null ? "" : id);
        });
    }
}
