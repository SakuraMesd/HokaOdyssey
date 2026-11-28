package ink.hoka.odyssey.blocks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Коллизия по частям (поле {@code collision} в config.yml): у каждой части многоклеточного блока свой набор коробок.
 * Файл — {@code {"cells": {"dx,dy,dz": [[x1,y1,z1,x2,y2,z2], …]}}}: ключ — клетка части до поворота (как {@link Multi}),
 * коробки — px этой клетки (0…16 по X/Z, 0…32 по Y). Части без записи — без коллизии (проходимые).
 * Поворот коробок — вокруг середины клетки (8, 8), как {@code BlockForm.rotateBox} у мода и модель блока.
 */
final class Collision {

    private Collision() {}

    /** Коробки каждой части (по номеру части {@link Multi#part}); пустой список — часть проходимая. */
    static double[][][] parse(String json, int[] size) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject cells = root.has("cells") ? root.getAsJsonObject("cells") : null;
        if (cells == null) throw new IllegalArgumentException("нет раздела cells");
        double[][][] out = new double[Multi.parts(size)][][];
        for (int i = 0; i < out.length; i++) out[i] = new double[0][];
        for (var e : cells.entrySet()) {
            String[] k = e.getKey().split(",");
            if (k.length != 3) throw new IllegalArgumentException("ключ части «" + e.getKey() + "» — нужно dx,dy,dz");
            int dx, dy, dz;
            try { dx = Integer.parseInt(k[0].trim()); dy = Integer.parseInt(k[1].trim()); dz = Integer.parseInt(k[2].trim()); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("ключ части «" + e.getKey() + "» — не числа"); }
            if (dx < 0 || dy < 0 || dz < 0 || dx >= size[0] || dy >= size[1] || dz >= size[2])
                throw new IllegalArgumentException("часть «" + e.getKey() + "» вне size");
            JsonArray arr = e.getValue().getAsJsonArray();
            double[][] boxes = new double[arr.size()][];
            for (int i = 0; i < arr.size(); i++) {
                JsonArray b = arr.get(i).getAsJsonArray();
                if (b.size() != 6) throw new IllegalArgumentException("часть «" + e.getKey() + "»: коробка " + i + " — нужно 6 чисел");
                double[] v = new double[6];
                for (int j = 0; j < 6; j++) v[j] = b.get(j).getAsDouble();
                if (!(v[0] >= 0 && v[2] >= 0 && v[1] >= 0 && v[3] <= 16 && v[5] <= 16 && v[4] <= 32
                        && v[0] < v[3] && v[1] < v[4] && v[2] < v[5]))
                    throw new IllegalArgumentException("часть «" + e.getKey() + "»: коробка " + b + " — нужно x1<x2, 0…16 по X/Z, 0…32 по Y");
                boxes[i] = v;
            }
            out[Multi.part(size, dx, dy, dz)] = boxes;
        }
        return out;
    }

    /** Коробка клетки (px), повёрнутая на rot вокруг середины клетки — как у мода (BlockForm.rotateBox). */
    static double[] rotateBox(double[] s, int rot) {
        double[] a = rotate(s[0] - 8, s[2] - 8, rot), b = rotate(s[3] - 8, s[5] - 8, rot);
        return new double[]{Math.min(a[0], b[0]) + 8, s[1], Math.min(a[1], b[1]) + 8,
                Math.max(a[0], b[0]) + 8, s[4], Math.max(a[1], b[1]) + 8};
    }

    /** Точка (x, z) от середины, повёрнутая на rot: 90 → (−z, x), 180 → (−x, −z), 270 → (z, −x). */
    static double[] rotate(double x, double z, int rot) {
        return switch (Math.floorMod(rot, 360)) {
            case 90 -> new double[]{-z, x};
            case 180 -> new double[]{-x, -z};
            case 270 -> new double[]{z, -x};
            default -> new double[]{x, z};
        };
    }

    /** Все коробки блока в мире (px от угла главной клетки) при повороте rot. */
    static List<double[]> world(double[][][] col, int[] size, int rot) {
        List<double[]> out = new ArrayList<>();
        for (int p = 0; p < col.length; p++) {
            int[] o = Multi.offset(size, p, rot);
            for (double[] b : col[p]) {
                double[] r = rotateBox(b, rot);
                out.add(new double[]{r[0] + o[0] * 16, r[1] + o[1] * 16, r[2] + o[2] * 16, r[3] + o[0] * 16, r[4] + o[1] * 16, r[5] + o[2] * 16});
            }
        }
        return out;
    }

    /**
     * Коробка {@code inside} (px в системе модели: центр (0, 0) — середина габарита size, Y — от низа главной клетки)
     * в мире: px от угла главной клетки при повороте rot.
     */
    static double[] insideBox(double[] in, int[] size, int rot) {
        double ox = size[0] * 8, oz = size[2] * 8;
        return rotateBox(new double[]{in[0] + ox, in[1], in[2] + oz, in[3] + ox, in[4], in[5] + oz}, rot);
    }

    /** Точка (px от угла главной клетки) внутри коробки. */
    static boolean contains(double[] b, double x, double y, double z) {
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    /** Коробки части — для blocks.json. */
    static JsonArray json(double[][] boxes) {
        JsonArray a = new JsonArray();
        for (double[] b : boxes) {
            JsonArray x = new JsonArray();
            for (double v : b) x.add(v);
            a.add(x);
        }
        return a;
    }
}
