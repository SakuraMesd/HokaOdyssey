package ink.hoka.odyssey.server.tall.gen;

import ink.hoka.odyssey.api.worldgen.Bounds;
import ink.hoka.odyssey.api.worldgen.GenSettings;
import ink.hoka.odyssey.api.worldgen.GeneratorProvider;
import ink.hoka.odyssey.gen.*;

/**
 * Переносимый генератор (gen-api) в реестре ядра — под старым интерфейсом GeneratorProvider, чтобы карты, pregen и
 * GenTest работали без изменений. jar == null — встроенный (flat, void: код в gen-api, клиенту jar не нужен).
 * Сервер генерирует из тех же байт, что уходят клиенту (filePath/sha256) — детерминизм по построению.
 */
public final class PortableProvider implements GeneratorProvider {

    public final GenProvider gen;
    /** Путь файла для клиента: "gen/&lt;id&gt;@&lt;v&gt;-&lt;sha8&gt;.jar" (файлы clientmods) или "builtin:&lt;id&gt;". */
    public final String filePath;
    /** sha256 jar ("" у встроенного). */
    public final String sha256;
    /** Байты jar (null у встроенного). */
    public final byte[] jar;
    /** hoka-gen.json: уровень API (1 — до объектов) и «нужны объекты» (нет ключа objects: в map.yml — все категории). */
    public final int apiLevel;
    public final boolean wantsObjects;

    public PortableProvider(GenProvider gen, String sha256, byte[] jar) { this(gen, sha256, jar, null); }

    public PortableProvider(GenProvider gen, String sha256, byte[] jar, GenManifest manifest) {
        this.gen = gen;
        this.apiLevel = manifest == null ? 1 : manifest.api();
        this.wantsObjects = manifest != null && manifest.objects();
        this.jar = jar;
        this.sha256 = jar == null ? "" : sha256;
        this.filePath = jar == null ? BuiltinGens.PREFIX + gen.id() : "gen/" + gen.id() + "@" + gen.version() + "-" + sha256.substring(0, 8) + ".jar";
    }

    public static PortableProvider builtin(GenProvider g) { return new PortableProvider(g, "", null); }

    @Override public String id() { return gen.id(); }
    @Override public int version() { return gen.version(); }
    @Override public boolean deterministic() { return true; }

    @Override
    public Bounds defaultBounds() {
        GenBounds b = gen.defaultBounds();
        return new Bounds(b.minY(), b.maxY(), b.size());
    }

    @Override public PortableWorldGen create(GenSettings s) { return create(s, null); }

    /** Генератор карты на сервере: секреты пишутся от secretSeed (null — секретов нет). */
    public PortableWorldGen create(GenSettings s, Long secretSeed) { return create(s, secretSeed, GenObjects.EMPTY); }

    /** С библиотекой объектов карты (пакет-снимок; тот же уходит клиенту). */
    public PortableWorldGen create(GenSettings s, Long secretSeed, GenObjects objects) {
        GenContext ctx = context(s, MinestomGenIds.get(), true, secretSeed, objects);
        return new PortableWorldGen(gen.create(ctx), ctx);
    }

    /**
     * Контекст как у клиента: options — через JSON туда-обратно (клиент получает их строкой op 20, типы чисел должны
     * совпасть), границы — настройки карты.
     */
    public static GenContext context(GenSettings s, GenIds ids, boolean server, Long secretSeed) {
        return context(s, ids, server, secretSeed, GenObjects.EMPTY);
    }

    public static GenContext context(GenSettings s, GenIds ids, boolean server, Long secretSeed, GenObjects objects) {
        var o = GenJson.parseObject(GenJson.write(s.options()));
        Bounds b = s.bounds();
        return GenContext.of(s.map(), s.seed(), new GenBounds(b.minY(), b.maxY(), b.size()), o, ids, server, secretSeed, objects);
    }
}
