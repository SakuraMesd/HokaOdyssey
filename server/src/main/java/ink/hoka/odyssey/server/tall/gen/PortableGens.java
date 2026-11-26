package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.gen.GenClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Переносимые генераторы из jar (gen-api): папка {@code generators/} у ядра (scan при запуске, до карт) и jar,
 * которые поставляют модули ({@code registerGenerator(Path)}). Байты jar грузятся в GenClassLoader (только java.* и
 * gen-api), проверяются (GenBytecodeCheck, манифест ↔ провайдер) и уходят клиентам файлами clientmods
 * ({@link #files}: путь → байты; список подписан, клиент кэширует по sha). Нет clientmods — генерация на клиенте выключена.
 */
public final class PortableGens {

    private PortableGens() {}

    private static final Logger LOG = LoggerFactory.getLogger("worldgen");
    /** Владелец генераторов из папки generators/. */
    public static final String OWNER = "generators";

    /** Мост к clientmods (сервис GenFilesSink) — ставит ядро; null/пусто — раздачи нет. */
    private static volatile Supplier<java.util.Optional<ink.hoka.odyssey.api.GenFilesSink>> sink = java.util.Optional::empty;

    public static void setSink(Supplier<java.util.Optional<ink.hoka.odyssey.api.GenFilesSink>> s) { sink = s; }

    /** Раздача jar клиентам есть (модуль clientmods включён). */
    public static boolean canPublish() {
        try { return sink.get().isPresent(); } catch (RuntimeException e) { return false; }
    }

    /** Состав jar сменился — clientmods пересобирает и рассылает подписанный список файлов. */
    public static void filesChanged() {
        try { sink.get().ifPresent(ink.hoka.odyssey.api.GenFilesSink::generatorFilesChanged); }
        catch (RuntimeException e) { LOG.warn("раздача jar генераторов: {}", e.toString()); }
    }

    /** Файлы для clientmods: "gen/&lt;id&gt;@&lt;v&gt;-&lt;sha8&gt;.jar" → байты. */
    public static Map<String, byte[]> files() {
        Map<String, byte[]> r = new TreeMap<>();
        for (PortableProvider p : GeneratorRegistry.portableJars()) r.put(p.filePath, p.jar);
        r.putAll(ink.hoka.odyssey.server.objects.ObjectPacks.files());       // пакеты объектов загруженных карт
        return r;
    }

    /** Папка generators/: каждый *.jar — генератор (ошибка одного — в лог, остальные грузятся). Ответ — сколько. */
    public static synchronized int scan(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        try (var s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted()::iterator) {
                try { register(OWNER, Files.readAllBytes(f)); n++; }
                catch (Exception e) { LOG.error("генератор {} не загружен: {}", f.getFileName(), e.getMessage()); }
            }
        } catch (java.io.IOException e) {
            LOG.error("папка {} не прочитана: {}", dir, e.toString());
        }
        return n;
    }

    /** Загрузить jar и зарегистрировать генератор (владелец owner). Исключение — jar плохой или id занят. */
    public static PortableProvider register(String owner, byte[] jar) throws Exception {
        GenClassLoader.Loaded l = load(jar);
        PortableProvider p = new PortableProvider(l.provider(), l.sha256(), jar, l.manifest());
        GeneratorRegistry.register(owner, p);
        return p;
    }

    /** Загрузить jar (без регистрации). Родитель — загрузчик ядра (gen-api в нём), фильтр — GenClassLoader. */
    public static GenClassLoader.Loaded load(byte[] jar) throws Exception {
        return GenClassLoader.load(jar, PortableGens.class.getClassLoader());
    }
}
