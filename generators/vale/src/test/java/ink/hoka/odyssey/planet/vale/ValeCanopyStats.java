package ink.hoka.odyssey.planet.vale;

import ink.hoka.odyssey.gen.HeightKind;
import ink.hoka.odyssey.gen.PlanetGenerator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

/** Замер: доля колонок под кронами (верх объектов выше земли+1) и средняя высота кроны по зонам — для дальней прорисовки. */
class ValeCanopyStats {
    @Test
    void measure() throws Exception {
        PlanetGenerator g = ValeTestKit.gen();
        ValeGen v = (ValeGen) g;
        Map<String, long[]> st = new TreeMap<>();               // [колонок, под кроной, сумма высот кроны]
        for (int bx = -6; bx < 6; bx++) for (int bz = -6; bz < 6; bz++) {
            int x0 = bx * 900 + 37, z0 = bz * 900 - 51;
            for (int dz = 0; dz < 64; dz += 2) for (int dx = 0; dx < 64; dx += 2) {
                int x = x0 + dx, z = z0 + dz;
                int h = v.surface(x, z);
                String zn = v.zoneName(x, z);
                int top = g.highest(x, z, h, h + 120, HeightKind.MOTION);
                long[] a = st.computeIfAbsent(zn, k -> new long[3]);
                a[0]++;
                if (top > h + 2) { a[1]++; a[2] += top - h; }
            }
        }
        StringBuilder sb = new StringBuilder("\nзона: колонок, покрытие, ср. высота кроны\n");
        st.forEach((k, a) -> sb.append(String.format("%-8s %6d %.3f %.1f%n", k, a[0], a[1] / (double) a[0], a[1] == 0 ? 0 : a[2] / (double) a[1])));
        System.out.println(sb);
    }
}
