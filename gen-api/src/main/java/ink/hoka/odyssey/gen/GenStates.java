package ink.hoka.odyssey.gen;

import java.util.Map;
import java.util.TreeMap;

/**
 * Поворот/отражение канонических состояний строками (без реестров): сначала отражение по X (восток ↔ запад), затем
 * поворот по часовой (вид сверху) quarterTurns раз: север → восток → юг → запад. Покрыты частые свойства (facing,
 * orientation, axis, rotation, north/east/south/west, рельсы, ступеньки, двери, сундуки); прочее — как есть.
 */
public final class GenStates {

    private GenStates() {}

    private static final String[] H = {"north", "east", "south", "west"};

    private static int dir(String s) {
        for (int i = 0; i < 4; i++) if (H[i].equals(s)) return i;
        return -1;
    }

    /** Горизонтальное направление после отражения и поворота; не горизонтальное — как есть. */
    public static String turn(String d, int q, boolean mirror) {
        int i = dir(d);
        if (i < 0) return d;
        if (mirror && (i == 1 || i == 3)) i ^= 2;                            // восток ↔ запад
        return H[(i + q) & 3];
    }

    /** Состояние после отражения по X (mirrorX) и поворота на quarterTurns×90° по часовой. */
    public static String rotate(String canonical, int quarterTurns, boolean mirrorX) {
        int q = quarterTurns & 3;
        if (q == 0 && !mirrorX) return canonical;
        int b = canonical.indexOf('[');
        if (b < 0) return canonical;
        String id = canonical.substring(0, b);
        Map<String, String> p = new TreeMap<>();
        String body = canonical.substring(b + 1, canonical.length() - 1);
        if (!body.isEmpty()) for (String kv : body.split(",")) {
            int e = kv.indexOf('=');
            if (e > 0) p.put(kv.substring(0, e), kv.substring(e + 1));
        }
        Map<String, String> r = new TreeMap<>(p);
        // стороны north/east/south/west (заборы, стены, лианы, панели, редстоун)
        if (p.containsKey("north") && p.containsKey("east") && p.containsKey("south") && p.containsKey("west")) {
            for (String d : H) r.put(turn(d, q, mirrorX), p.get(d));
        }
        for (var e : p.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            switch (k) {
                case "facing", "horizontal_facing" -> r.put(k, turn(v, q, mirrorX));
                case "orientation" -> {                                      // jigsaw/crafter: «перед_верх»
                    int u = v.indexOf('_');
                    if (u > 0) r.put(k, turn(v.substring(0, u), q, mirrorX) + "_" + turn(v.substring(u + 1), q, mirrorX));
                }
                case "axis" -> { if ((q & 1) == 1) r.put(k, "x".equals(v) ? "z" : "z".equals(v) ? "x" : v); }
                case "rotation" -> {
                    try {
                        int rv = Integer.parseInt(v);
                        if (mirrorX) rv = (16 - rv) & 15;
                        r.put(k, Integer.toString((rv + 4 * q) & 15));
                    } catch (NumberFormatException ignored) {}
                }
                case "shape" -> r.put(k, shape(v, q, mirrorX));
                case "hinge", "type" -> { if (mirrorX) r.put(k, "left".equals(v) ? "right" : "right".equals(v) ? "left" : v); }
                default -> {}
            }
        }
        return GenIds.canonical(id, r);
    }

    /** shape рельсов (по сторонам света) и ступенек (left/right при отражении). */
    static String shape(String v, int q, boolean mirror) {
        switch (v) {
            case "inner_left": return mirror ? "inner_right" : v;
            case "inner_right": return mirror ? "inner_left" : v;
            case "outer_left": return mirror ? "outer_right" : v;
            case "outer_right": return mirror ? "outer_left" : v;
            default: break;
        }
        if (v.startsWith("ascending_")) return "ascending_" + turn(v.substring(10), q, mirror);
        int u = v.indexOf('_');
        if (u < 0) return v;
        int a = dir(v.substring(0, u)), c = dir(v.substring(u + 1));
        if (a < 0 || c < 0) return v;
        String x = turn(H[a], q, mirror), y = turn(H[c], q, mirror);
        // допустимые имена рельсов: north_south, east_west, south_east, south_west, north_west, north_east
        int ix = dir(x), iy = dir(y);
        if ((ix & 1) == (iy & 1)) return (ix & 1) == 0 ? "north_south" : "east_west";
        String ns = (ix & 1) == 0 ? x : y, ew = (ix & 1) == 0 ? y : x;
        return ns + "_" + ew;
    }
}
