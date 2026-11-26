package ink.hoka.odyssey.gen;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Проверка байткода jar генератора (сервер при загрузке; клиент — тем же кодом): запрещены нестрогая математика
 * (Math.sin/cos/… — 1 ulp разницы на разных платформах = другой рельеф), случайность без сида, время, параллельные
 * потоки, ссылки на Minestom/MC. Разбирается только пул констант (ссылки на методы и строки дескрипторов).
 */
public final class GenBytecodeCheck {

    private GenBytecodeCheck() {}

    static final Set<String> MATH = Set.of("sin", "cos", "tan", "asin", "acos", "atan", "atan2", "pow", "exp", "log",
            "log10", "cbrt", "sinh", "cosh", "tanh", "hypot", "expm1", "log1p", "random");
    static final String[] FOREIGN = {"net/minestom/", "net/minecraft/", "com/mojang/", "net/fabricmc/", "net/kyori/"};

    /** Нарушения по всем классам (пусто — ок). */
    public static List<String> check(Map<String, byte[]> classes) {
        List<String> bad = new ArrayList<>();
        for (var e : new java.util.TreeMap<>(classes).entrySet()) {
            try { checkClass(e.getKey(), e.getValue(), bad); }
            catch (IOException | RuntimeException ex) { bad.add(e.getKey() + ": битый класс (" + ex + ")"); }
        }
        return bad;
    }

    private static void checkClass(String name, byte[] b, List<String> bad) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("не класс");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int n = in.readUnsignedShort();
        String[] utf = new String[n];
        int[] a = new int[n], c = new int[n];
        byte[] tag = new byte[n];
        for (int i = 1; i < n; i++) {
            int t = in.readUnsignedByte();
            tag[i] = (byte) t;
            switch (t) {
                case 1 -> utf[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> { in.readLong(); i++; }
                case 7, 8, 16, 19, 20 -> a[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> { a[i] = in.readUnsignedShort(); c[i] = in.readUnsignedShort(); }
                case 15 -> { in.readUnsignedByte(); a[i] = in.readUnsignedShort(); }
                default -> throw new IOException("тег пула " + t);
            }
        }
        for (int i = 1; i < n; i++) {
            if (tag[i] == 1 && utf[i] != null)
                for (String f : FOREIGN) if (utf[i].contains(f)) { bad.add(name + ": ссылка на " + f + " (" + utf[i] + ")"); break; }
            if (tag[i] == 7 && "java/util/concurrent/ThreadLocalRandom".equals(utf[a[i]])) bad.add(name + ": ThreadLocalRandom");
            if (tag[i] != 10 && tag[i] != 11) continue;
            String owner = utf[a[a[i]]];
            int nt = c[i];
            String m = utf[a[nt]], d = utf[c[nt]];
            if ("java/lang/Math".equals(owner) && MATH.contains(m)) bad.add(name + ": Math." + m + " (только StrictMath)");
            else if ("java/lang/System".equals(owner) && ("nanoTime".equals(m) || "currentTimeMillis".equals(m))) bad.add(name + ": System." + m);
            else if ("java/util/Random".equals(owner) && "<init>".equals(m) && "()V".equals(d)) bad.add(name + ": new Random() без сида");
            else if ("parallelStream".equals(m) || ("parallel".equals(m) && owner != null && owner.startsWith("java/util/stream/")))
                bad.add(name + ": параллельный поток (" + m + ")");
        }
    }
}
