package ink.hoka.odyssey.gen;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Проверка байткода и загрузчик-фильтр: Math.sin, new Random(), чужие классы — отказ; StrictMath — можно. */
class GenBytecodeCheckTest {

    /** Плохой: нестрогая математика и случайность без сида. */
    public static final class BadMath {
        public static double f(double x) { return Math.sin(x) + new java.util.Random().nextDouble(); }
    }

    /** Хороший: StrictMath, sqrt, floor. */
    public static final class Good {
        public static double f(double x) { return StrictMath.sin(x) + Math.sqrt(x) + Math.floor(x); }
    }

    /** Время. */
    public static final class Clock {
        public static long f() { return System.nanoTime(); }
    }

    /** Генератор для загрузки из jar. */
    public static final class TestProvider implements GenProvider {
        @Override public String id() { return "t-load"; }
        @Override public int version() { return 4; }
        @Override public PlanetGenerator create(GenContext ctx) { return out -> out.fill(1); }
    }

    static byte[] bytes(Class<?> c) throws Exception {
        try (InputStream in = c.getResourceAsStream("/" + c.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }

    @Test
    void rejectsMathAndRandom() throws Exception {
        List<String> bad = GenBytecodeCheck.check(Map.of("BadMath", bytes(BadMath.class)));
        assertTrue(bad.stream().anyMatch(s -> s.contains("Math.sin")), bad.toString());
        assertTrue(bad.stream().anyMatch(s -> s.contains("Random")), bad.toString());
        assertTrue(GenBytecodeCheck.check(Map.of("Good", bytes(Good.class))).isEmpty());
        assertTrue(GenBytecodeCheck.check(Map.of("Clock", bytes(Clock.class))).stream().anyMatch(s -> s.contains("nanoTime")));
    }

    @Test
    void rejectsForeignReference() {
        // константа-строка с дескриптором Minestom в пуле — как у класса, который ссылается на Block
        byte[] fake = classWithUtf8("Lnet/minestom/server/instance/block/Block;");
        assertFalse(GenBytecodeCheck.check(Map.of("Fake", fake)).isEmpty());
    }

    /** Минимальный класс-файл: только пул констант с одной строкой (для разбора пула этого хватает). */
    private static byte[] classWithUtf8(String s) {
        var o = new java.io.ByteArrayOutputStream();
        try (var d = new java.io.DataOutputStream(o)) {
            d.writeInt(0xCAFEBABE);
            d.writeShort(0);
            d.writeShort(65);
            d.writeShort(2);
            d.writeByte(1);
            d.writeUTF(s);
        } catch (Exception e) { throw new RuntimeException(e); }
        return o.toByteArray();
    }

    static byte[] jar(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (JarOutputStream j = new JarOutputStream(o)) {
            for (var e : entries.entrySet()) {
                j.putNextEntry(new JarEntry(e.getKey()));
                j.write(e.getValue());
                j.closeEntry();
            }
        }
        return o.toByteArray();
    }

    @Test
    void loaderFiltersAndLoads() throws Exception {
        String prov = TestProvider.class.getName();
        // класс провайдера лежит в пакете API (тестовом) — загрузчик такой jar отвергает: свои классы — только вне ink.hoka.odyssey.gen
        byte[] apiPkg = jar(Map.of(prov.replace('.', '/') + ".class", bytes(TestProvider.class),
                GenManifest.FILE, ("{\"id\":\"t-load\",\"version\":4,\"provider\":\"" + prov + "\"}").getBytes()));
        assertThrows(java.io.IOException.class, () -> GenClassLoader.load(apiPkg, getClass().getClassLoader()));
        assertFalse(GenClassLoader.parentAllowed("net.minestom.server.instance.block.Block"));
        assertFalse(GenClassLoader.parentAllowed("ink.hoka.odyssey.gen.sub.X"));
        assertTrue(GenClassLoader.parentAllowed("ink.hoka.odyssey.gen.GenCube"));
        assertTrue(GenClassLoader.parentAllowed("java.util.List"));
        // без манифеста — отказ
        byte[] noMf = jar(Map.of("a/B.class", bytes(Good.class)));
        assertThrows(java.io.IOException.class, () -> GenClassLoader.load(noMf, getClass().getClassLoader()));
    }
}
