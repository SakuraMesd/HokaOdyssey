package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.ModuleConfig;
import ink.hoka.odyssey.api.worldgen.Bounds;
import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.server.tall.CubeGen;
import ink.hoka.odyssey.server.tall.CubeRegionLoader;
import ink.hoka.odyssey.server.tall.FlatTallGen;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

/**
 * Генератор карты: CubeGen по миру (поле CubeRegionLoader, ставит Maps) и разбор map.yml.
 * <pre>
 * generator: &lt;id&gt;            — flat, void или генератор модуля
 * seed: &lt;long&gt;               — новым картам случайный, у старых 0
 * gen_min_y / gen_max_y / gen_size — переопределяют Bounds провайдера
 * generator_options: {…}       — генератору как есть
 * generator_seen: &lt;id&gt;@&lt;ver&gt;  — пишет ядро; нет — старая карта: всегда flat (как было)
 * objects: [trees, bushes]     — категории objects/ для генератора ("*" — все); нет ключа — все, если генератор просит
 *                               (hoka-gen.json objects: true), иначе ни одной. Пакет — снимок при загрузке карты
 * objects_seen: &lt;hash16&gt;      — пишет ядро (смена объектов — в лог)
 * secret_seed: &lt;long&gt;        — сид секретов переносимого генератора (руды, тайники): клиентам НЕ уходит. Нет — ядро
 *                               создаёт случайный и сохраняет (единственная запись resolve)
 * </pre>
 */
public final class CubeGens {

    private CubeGens() {}

    private static final Logger LOG = LoggerFactory.getLogger("worldgen");

    /** Генератор кубов мира: высокая карта на регионах — свой (map.yml), иначе тестовая земля FlatTallGen. */
    public static CubeGen of(Instance inst) {
        if (inst instanceof InstanceContainer ic && ic.getChunkLoader() instanceof CubeRegionLoader l) return l.gen();
        return FlatTallGen.INSTANCE;
    }

    /** Генератор модуля мира или null (тестовая земля / не высокая карта). */
    public static ModuleCubeGen module(Instance inst) { return of(inst) instanceof ModuleCubeGen g ? g : null; }

    /** Итог разбора map.yml: генератор или ошибка; legacy — карта до генераторов-модулей (всегда flat). */
    public record Resolved(ModuleCubeGen gen, String error, boolean legacy, String requested) {}

    /** Собрать генератор карты по map.yml (ничего не пишет). Генератора нет в реестре — ошибка (НЕ подставлять flat). */
    public static Resolved resolve(String map, Path dir, ModuleConfig meta) {
        String want = meta.getString("generator", "flat");
        // старая карта: generator_seen нет, а данные уже есть (до генераторов-модулей всё было flat);
        // новая (пустая папка, map.yml руками) — сразу с тем генератором, что просили
        boolean legacy = !meta.contains("generator_seen") && hasData(dir);
        String id = legacy ? "flat" : want;
        GeneratorRegistry.Entry e = GeneratorRegistry.get(id);
        if (e == null)
            return new Resolved(null, "генератор «" + id + "» не зарегистрирован (модуль не включён?) — есть: "
                    + String.join(", ", GeneratorRegistry.ids()), legacy, want);
        Bounds def = e.provider().defaultBounds();
        Bounds b = new Bounds(meta.getInt("gen_min_y", def.minY()), meta.getInt("gen_max_y", def.maxY()),
                meta.getInt("gen_size", def.size()));
        Object opts = meta.get("generator_options");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = opts instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        GenSettings s = new GenSettings(map, meta.getLong("seed", 0L), b, options);
        ModuleCubeGen g;
        Long secret = null;
        ink.hoka.odyssey.gen.GenObjects objects = ink.hoka.odyssey.gen.GenObjects.EMPTY;
        if (e.provider() instanceof PortableProvider pp) {
            try { objects = ink.hoka.odyssey.server.objects.ObjectPacks.forMap(meta.get("objects"), pp.wantsObjects); }
            catch (RuntimeException ex) { return new Resolved(null, "объекты карты (objects:): " + ex.getMessage(), legacy, want); }
            if (!meta.contains("secret_seed")) {                              // секреты — от своего сида, не от seed (seed знает клиент)
                meta.set("secret_seed", new java.security.SecureRandom().nextLong());
                try { meta.save(); } catch (RuntimeException ex) { LOG.warn("карта «{}»: secret_seed не сохранён: {}", map, ex.toString()); }
            }
            secret = meta.getLong("secret_seed", 0L);
        }
        try { g = new ModuleCubeGen(e.provider(), s, secret, objects); }
        catch (Throwable t) { return new Resolved(null, "генератор «" + id + "» не создался: " + t, legacy, want); }
        return new Resolved(g.withIndex(dir.resolve("genidx")), null, legacy, want);
    }

    /** В папке карты уже есть мир (регионы кубов или .htw). */
    static boolean hasData(Path dir) {
        for (String d : new String[]{"cols", "cubes", "tall"}) {
            Path p = dir.resolve(d);
            if (!java.nio.file.Files.isDirectory(p)) continue;
            try (var s = java.nio.file.Files.list(p)) { if (s.findAny().isPresent()) return true; }
            catch (java.io.IOException e) { return true; }                    // не прочитать — считать старой (безопаснее)
        }
        return false;
    }

    /**
     * Карта загружена с генератором g: старая карта — предупредить, если просили не flat, и закрепить flat;
     * версия сменилась — в лог (правки остаются, нетронутое — новой версией). Пишет generator_seen.
     */
    public static void markSeen(String map, ModuleConfig meta, Resolved r) {
        String seen = meta.getString("generator_seen", null);
        String tag = r.gen().tag();
        if (r.legacy()) {
            if (!"flat".equals(r.requested()))
                LOG.warn("карта «{}»: старая высокая карта (нет generator_seen) — генератор flat, как было; generator: {} из map.yml "
                        + "не применён (иначе правки легли бы поверх чужого рельефа). Сменить: поставить generator_seen вручную.", map, r.requested());
            meta.set("generator_legacy", r.requested());
            meta.set("generator", "flat");
        } else if (seen != null && !seen.equals(tag)) {
            LOG.warn("карта «{}»: генератор {} → {}: правки игроков на диске остаются, нетронутое сгенерируется новой версией, "
                    + "индекс pregen старой версии не используется (нужен /hocubes pregen)", map, seen, tag);
        }
        String objSeen = meta.getString("objects_seen", null);
        long oh = r.gen().objects.hash();
        String obj = oh == 0 ? null : String.format("%016x", oh);
        if (objSeen != null && !objSeen.equals(String.valueOf(obj)) && !r.legacy())
            LOG.warn("карта «{}»: объекты генератора сменились ({} → {}): правки игроков остаются, нетронутое сгенерируется с новыми "
                    + "объектами, индекс pregen старых не используется (нужен /hocubes pregen)", map, objSeen, obj == null ? "нет" : obj);
        boolean dirty = false;
        if (!tag.equals(seen)) { meta.set("generator_seen", tag); dirty = true; }
        if (obj != null ? !obj.equals(objSeen) : objSeen != null) { meta.set("objects_seen", obj); dirty = true; }
        if (dirty) meta.save();
    }
}
