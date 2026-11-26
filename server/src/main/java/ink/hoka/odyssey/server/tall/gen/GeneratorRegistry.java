package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.GeneratorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Реестр генераторов планет: id → провайдер и владелец (модуль или «core» для flat/void). Плюс «кто сейчас
 * пользуется генератором» (загруженные карты, pregen) — для запрета выгрузки модуля ({@link #guard}).
 */
public final class GeneratorRegistry {

    private GeneratorRegistry() {}

    private static final Logger LOG = LoggerFactory.getLogger("worldgen");
    public static final String CORE = "core";
    private static final Pattern ID = Pattern.compile("[a-z0-9_\\-]{1,32}");

    public record Entry(GeneratorProvider provider, String owner) {
        public String tag() { return provider.id() + "@" + provider.version(); }
    }

    private static final Map<String, Entry> MAP = new ConcurrentHashMap<>();
    /** Пользователь («карта mars», «pregen mars») → id генератора. */
    private static final Map<String, String> USERS = new ConcurrentHashMap<>();

    static {
        // встроенные — переносимые (gen-api): нетронутое клиент 0.59+ генерирует сам
        MAP.put("flat", new Entry(PortableProvider.builtin(ink.hoka.odyssey.gen.BuiltinGens.FLAT), CORE));
        MAP.put("void", new Entry(PortableProvider.builtin(ink.hoka.odyssey.gen.BuiltinGens.VOID), CORE));
    }

    /** Зарегистрировать (модуль owner). Исключение — id неверный или занят. */
    public static synchronized void register(String owner, GeneratorProvider p) {
        String id = p.id();
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("id генератора: a-z 0-9 _ - (до 32), а не «" + id + "»");
        Entry old = MAP.get(id);
        if (old != null && old.provider instanceof PortableProvider && !(p instanceof PortableProvider) && PortableGens.OWNER.equals(old.owner)) {
            // старый модуль-генератор (например noisegen) при переносимом jar с тем же id из generators/ — не нужен
            LOG.warn("генератор {} модуля {} не зарегистрирован: есть переносимый {} из generators/ (старый модуль можно убрать)", id, owner, old.tag());
            return;
        }
        if (old != null) throw new IllegalStateException("генератор «" + id + "» уже есть (модуль " + old.owner + ")");
        MAP.put(id, new Entry(p, owner));
        LOG.info("генератор {} v{} (модуль {}{}{})", id, p.version(), owner, p.deterministic() ? "" : ", НЕдетерминированный",
                p instanceof PortableProvider pp ? ", переносимый " + pp.filePath : "");
        if (p instanceof PortableProvider pp && pp.jar != null) PortableGens.filesChanged();
    }

    /** Снять все генераторы модуля (выключение). */
    public static synchronized void unregisterOwner(String owner) {
        if (CORE.equals(owner)) return;
        for (var u : USERS.entrySet()) {                                    // guard не пустил бы; сюда — только гонка/disableAll
            Entry e = MAP.get(u.getValue());
            if (e != null && e.owner.equals(owner))
                LOG.warn("генератор {} снят с модулем {}, а им ещё пользуется: {}", e.provider.id(), owner, u.getKey());
        }
        boolean files = MAP.values().stream().anyMatch(e -> e.owner.equals(owner) && e.provider instanceof PortableProvider pp && pp.jar != null);
        MAP.values().removeIf(e -> e.owner.equals(owner));
        if (files) PortableGens.filesChanged();
    }

    /** Все переносимые генераторы с jar (раздача клиентам). */
    public static java.util.List<PortableProvider> portableJars() {
        java.util.List<PortableProvider> r = new java.util.ArrayList<>();
        for (Entry e : MAP.values()) if (e.provider instanceof PortableProvider pp && pp.jar != null) r.add(pp);
        return r;
    }

    public static Entry get(String id) { return id == null ? null : MAP.get(id); }

    public static SortedSet<String> ids() { return new TreeSet<>(MAP.keySet()); }

    // ───────────── кто пользуется ─────────────

    public static void use(String who, String genId) { USERS.put(who, genId); }

    /**
     * Занять генератор провайдера p (карта/pregen), если он всё ещё зарегистрирован этим же объектом: между
     * resolve и use модуль могли выгрузить/перезагрузить — тогда false (карта не грузится со старым классом).
     */
    public static synchronized boolean acquire(String who, GeneratorProvider p) {
        Entry e = MAP.get(p.id());
        if (e == null || e.provider != p) return false;
        USERS.put(who, p.id());
        return true;
    }

    public static void release(String who) { USERS.remove(who); }

    /** Кто пользуется генераторами модулей modules: текст отказа или null — можно выгружать. */
    public static String guard(Collection<String> modules) {
        Set<String> mods = new HashSet<>(modules);
        StringBuilder r = new StringBuilder();
        for (var u : new TreeMap<>(USERS).entrySet()) {
            Entry e = MAP.get(u.getValue());
            if (e == null || !mods.contains(e.owner)) continue;
            if (!r.isEmpty()) r.append("; ");
            r.append("генератор ").append(e.provider.id()).append(" (модуль ").append(e.owner).append(") — ").append(u.getKey());
        }
        if (r.isEmpty()) return null;
        return r + ". Сначала /homap unload <карта> и /hocubes pregen pause <карта>, потом перезагрузка модуля.";
    }
}
