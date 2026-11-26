package ink.hoka.odyssey.gen;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Загрузчик jar генератора из байт (сервер и клиент грузят одни и те же байты). Родителю отдаёт только {@code java.*}
 * и классы пакета {@code ink.hoka.odyssey.gen} (этот API) — ссылка на Minestom или MC падает при загрузке одинаково на
 * обеих сторонах. Классы jar не могут лежать в {@code java.*} и в пакете API.
 */
public final class GenClassLoader extends ClassLoader {

    static { registerAsParallelCapable(); }

    public static final String API_PACKAGE = "ink.hoka.odyssey.gen";

    private final Map<String, byte[]> classes = new HashMap<>(), resources = new HashMap<>();

    public GenClassLoader(byte[] jar, ClassLoader parent) throws IOException {
        super("hoka-gen", parent);
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            for (JarEntry e; (e = in.getNextJarEntry()) != null; ) {
                if (e.isDirectory()) continue;
                byte[] b = in.readAllBytes();
                String n = e.getName();
                if (n.endsWith(".class")) {
                    if (n.startsWith("META-INF/")) continue;                    // module-info/версии — не нужны
                    String cn = n.substring(0, n.length() - 6).replace('/', '.');
                    if (cn.startsWith("java.") || API_PACKAGE.equals(pkg(cn)))
                        throw new IOException("jar генератора: класс " + cn + " в запрещённом пакете");
                    classes.put(cn, b);
                } else resources.put(n, b);
            }
        }
    }

    private static String pkg(String cn) { int i = cn.lastIndexOf('.'); return i < 0 ? "" : cn.substring(0, i); }

    /** Родителю можно: java.* и сам API (без подпакетов). */
    static boolean parentAllowed(String name) { return name.startsWith("java.") || API_PACKAGE.equals(pkg(name)); }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (parentAllowed(name)) c = getParent() == null ? findSystemClass(name) : getParent().loadClass(name);
                else c = findClass(name);
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = classes.get(name);
        if (b == null) throw new ClassNotFoundException(name + " — генератору доступны только java.* и " + API_PACKAGE);
        return defineClass(name, b, 0, b.length);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] b = resources.get(name.startsWith("/") ? name.substring(1) : name);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    public Map<String, byte[]> classBytes() { return java.util.Collections.unmodifiableMap(classes); }

    /** Загруженный генератор: манифест, провайдер, sha256 jar. */
    public record Loaded(GenManifest manifest, GenProvider provider, String sha256, byte[] jar, GenClassLoader loader) {}

    /**
     * Загрузить jar: манифест, проверка байткода (GenBytecodeCheck), провайдер; id и version провайдера обязаны
     * совпасть с манифестом. Любая ошибка — исключение (генератор не регистрируется).
     */
    public static Loaded load(byte[] jar, ClassLoader parent) throws Exception {
        GenClassLoader l = new GenClassLoader(jar, parent);
        byte[] mf = l.resources.get(GenManifest.FILE);
        if (mf == null) throw new IOException("в jar нет " + GenManifest.FILE);
        GenManifest m = GenManifest.parse(mf);
        if (m.api() > GenApi.LEVEL)
            throw new IOException("генератор " + m.tag() + " требует API " + m.api() + ", а здесь " + GenApi.LEVEL + " — нужно ядро новее");
        List<String> bad = GenBytecodeCheck.check(l.classes);
        if (!bad.isEmpty()) throw new IOException("генератор " + m.tag() + " недетерминирован или ссылается на чужие классы: "
                + String.join("; ", bad.subList(0, Math.min(8, bad.size()))) + (bad.size() > 8 ? " … (" + bad.size() + ")" : ""));
        Class<?> c = l.loadClass(m.provider());
        if (!GenProvider.class.isAssignableFrom(c)) throw new IOException(m.provider() + " не GenProvider");
        GenProvider p = (GenProvider) c.getDeclaredConstructor().newInstance();
        if (!m.id().equals(p.id()) || m.version() != p.version())
            throw new IOException("hoka-gen.json " + m.tag() + " ≠ провайдер " + p.id() + "@" + p.version());
        return new Loaded(m, p, sha256(jar), jar, l);
    }

    public static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Прочитать поток целиком (для jar из ресурсов). */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        in.transferTo(o);
        return o.toByteArray();
    }
}
