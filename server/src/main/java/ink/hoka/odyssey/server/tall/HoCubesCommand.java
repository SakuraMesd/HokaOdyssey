package ink.hoka.odyssey.server.tall;

import ink.hoka.odyssey.server.perms.Ops;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;

/**
 * /hocubes — отладка высокого мира и будущих кубических чанков (CUBIC-CHUNKS.md §6).
 * <ul>
 *   <li>{@code stats} — по каждому миру: столбцов в памяти, секций всего / непустых, свет в очереди; кэш кубов клиентов;</li>
 *   <li>{@code cachecheck} — сверить хэши кубов у себя (клиент 0.51+) с сервером (op 15 → op 6);</li>
 *   <li>{@code player <ник>} — что сообщило ядро при входе (версия, Sodium), где стоит, сколько столбцов видит;</li>
 *   <li>{@code dump [x y z]} — секция под ногами (или по координатам): блоков, палитра, высоты столбца; skyTop/motion/surface
 *       колонки (HeightIndex); куб — состояние, интерес, «изменён», у кого есть, кто закрепил, сущности.</li>
 *   <li>{@code convert <карта>} — выгруженную высокую карту .htw → регионы кубов (шаг 7): перевод, сверка, tall/ → tall.bak/,
 *       map.yml {@code store: cubes};</li>
 *   <li>{@code verify <карта>} — сверить блоки .htw (tall/ или tall.bak/) с регионами по всем столбцам.</li>
 * </ul>
 */
public final class HoCubesCommand extends Command {

    public HoCubesCommand(ink.hoka.odyssey.server.maps.Maps maps, Ops ops) {
        super("hocubes");
        setCondition(ops.admin());
        setDefaultExecutor((s, c) -> s.sendMessage(Component.text("/hocubes stats | cachecheck | player <ник> | dump [x y z] | go [y] | convert <карта> | verify <карта> | compact <карта> | pregen … | size <карта> [N] | gentest <карта> [N]", NamedTextColor.GRAY)));
        addSyntax((s, c) -> stats(s), ArgumentType.Literal("stats"));
        addSyntax((s, c) -> {                                              // кэш кубов 0.51: сверить хэши клиента и сервера
            if (!(s instanceof Player p)) return;
            int n = CubeStreamer.cacheCheck(p);
            if (n < 0) s.sendMessage(Component.text("сверка: ядро клиента < 0.51 или не кубовая карта", NamedTextColor.RED));
            else s.sendMessage(Component.text("сверка: отправлено " + n + " версий кубов, ждём ответ клиента", NamedTextColor.GRAY));
        }, ArgumentType.Literal("cachecheck"));
        var nick = ArgumentType.Word("ник");
        addSyntax((s, c) -> player(s, c.get(nick)), ArgumentType.Literal("player"), nick);
        addSyntax((s, c) -> { if (s instanceof Player p) player(s, p.getUsername()); }, ArgumentType.Literal("player"));
        addSyntax((s, c) -> { if (s instanceof Player p) dump(s, p.getInstance(), p.getPosition().blockX(), p.getPosition().blockY(), p.getPosition().blockZ()); },
                ArgumentType.Literal("dump"));
        var dx = ArgumentType.Integer("x");
        var dy = ArgumentType.Integer("y");
        var dz = ArgumentType.Integer("z");
        addSyntax((s, c) -> { if (s instanceof Player p) dump(s, p.getInstance(), c.get(dx), c.get(dy), c.get(dz)); },
                ArgumentType.Literal("dump"), dx, dy, dz);
        var y = ArgumentType.Integer("y").between(TallWorld.MIN_Y, TallWorld.MIN_Y + TallWorld.HEIGHT - 3);
        addSyntax((s, c) -> go(s, maps, 41), ArgumentType.Literal("go"));
        addSyntax((s, c) -> go(s, maps, c.get(y) + 1), ArgumentType.Literal("go"), y);
        var map = ArgumentType.Word("карта").setSuggestionCallback((sender, ctx, sug) -> {
            for (String n : maps.names()) if (TallWorld.key(n) != null) sug.addEntry(new net.minestom.server.command.builder.suggestion.SuggestionEntry(n));
        });
        addSyntax((s, c) -> convert(s, maps, c.get(map)), ArgumentType.Literal("convert"), map);
        addSyntax((s, c) -> verify(s, maps, c.get(map)), ArgumentType.Literal("verify"), map);
        addSyntax((s, c) -> compact(s, maps, c.get(map)), ArgumentType.Literal("compact"), map);
        // генераторы планет: pregen, size, gentest (CUBIC-CHUNKS.md «Генераторы и pregen»)
        var rest = ArgumentType.StringArray("аргументы");
        addSyntax((s, c) -> pregen(s, maps, c.get(rest)), ArgumentType.Literal("pregen"), rest);
        addSyntax((s, c) -> s.sendMessage(Component.text(PREGEN_HELP, NamedTextColor.GRAY)), ArgumentType.Literal("pregen"));
        var n = ArgumentType.Long("N");
        addSyntax((s, c) -> size(s, maps, c.get(map), 100_000), ArgumentType.Literal("size"), map);
        addSyntax((s, c) -> size(s, maps, c.get(map), c.get(n)), ArgumentType.Literal("size"), map, n);
        var cnt = ArgumentType.Integer("кубов").between(8, 100_000);
        addSyntax((s, c) -> gentest(s, maps, c.get(map), 256), ArgumentType.Literal("gentest"), map);
        addSyntax((s, c) -> gentest(s, maps, c.get(map), c.get(cnt)), ArgumentType.Literal("gentest"), map, cnt);
    }

    static final String PREGEN_HELP = "/hocubes pregen <карта> <радиус_чанков> [ymin ymax] [потоков] | pregen <карта> box <x1> <z1> <x2> <z2> [ymin ymax] [потоков]"
            + " (блоки) | pregen pause|resume|stop|status [карта]";

    /** /hocubes pregen … (разбор руками: формы с разным числом чисел). */
    private static void pregen(CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, String[] a) {
        var env = maps.pregenEnv();
        if (a.length == 0) { info(s, PREGEN_HELP); return; }
        String act = a[0];
        if (act.equals("status")) {
            var all = ink.hoka.odyssey.server.tall.gen.Pregen.active();
            if (all.isEmpty()) info(s, "pregen не идёт");
            for (var p : all) if (a.length < 2 || p.map.equals(a[1])) info(s, "pregen " + p.status());
            return;
        }
        if (act.equals("pause") || act.equals("stop") || act.equals("resume")) {
            if (a.length < 2) { err(s, "нужна карта: /hocubes pregen " + act + " <карта>"); return; }
            String m = a[1];
            switch (act) {
                case "pause" -> info(s, ink.hoka.odyssey.server.tall.gen.Pregen.pause(m));
                case "stop" -> info(s, ink.hoka.odyssey.server.tall.gen.Pregen.stop(m, env));
                default -> {
                    String e = ink.hoka.odyssey.server.tall.gen.Pregen.resume(m, env, t -> info(s, t));
                    if (e != null) err(s, e);
                }
            }
            return;
        }
        String m = act;
        if (!maps.exists(m)) { err(s, "карты «" + m + "» нет"); return; }
        var p = new ink.hoka.odyssey.server.tall.gen.PregenProgress();
        int k;
        try {
            var meta = maps.meta(m);
            int sx = (int) Math.floor(meta.getDouble("spawn.x", 0)) >> 4, sz = (int) Math.floor(meta.getDouble("spawn.z", 0)) >> 4;
            if (a.length >= 6 && a[1].equals("box")) {
                int x1 = Integer.parseInt(a[2]) >> 4, z1 = Integer.parseInt(a[3]) >> 4, x2 = Integer.parseInt(a[4]) >> 4, z2 = Integer.parseInt(a[5]) >> 4;
                p.x1 = Math.min(x1, x2); p.x2 = Math.max(x1, x2); p.z1 = Math.min(z1, z2); p.z2 = Math.max(z1, z2);
                p.centerX = (p.x1 + p.x2) / 2; p.centerZ = (p.z1 + p.z2) / 2;
                k = 6;
            } else if (a.length >= 2) {
                int r = Integer.parseInt(a[1]);
                if (r < 0 || r > 1_000_000) { err(s, "радиус 0…1000000 чанков"); return; }
                p.x1 = sx - r; p.x2 = sx + r; p.z1 = sz - r; p.z2 = sz + r;
                p.centerX = sx; p.centerZ = sz;
                k = 2;
            } else { err(s, PREGEN_HELP); return; }
            int left = a.length - k;
            if (left >= 2) { p.ymin = Integer.parseInt(a[k]); p.ymax = Integer.parseInt(a[k + 1]); k += 2; left -= 2; }
            if (left == 1) { p.threads = Integer.parseInt(a[k]); left--; }
            if (left != 0) { err(s, PREGEN_HELP); return; }
            if (p.threads < 0 || p.threads > 256) { err(s, "потоков 1…256"); return; }
        } catch (NumberFormatException e) { err(s, "не число: " + e.getMessage() + " — " + PREGEN_HELP); return; }
        java.nio.file.Path f = maps.dir(m).resolve("pregen.yml");
        if (java.nio.file.Files.exists(f) && ink.hoka.odyssey.server.tall.gen.Pregen.get(m) == null) {
            err(s, "у «" + m + "» есть незаконченный pregen.yml — /hocubes pregen resume " + m + " или /hocubes pregen stop " + m);
            return;
        }
        String e = ink.hoka.odyssey.server.tall.gen.Pregen.start(m, env, p, t -> info(s, t));
        if (e != null) err(s, e);
    }

    /** /hocubes size <карта> [N] — факт на диске и прикидка для N×N. */
    private static void size(CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, String name, long n) {
        if (!maps.exists(name)) { err(s, "карты «" + name + "» нет"); return; }
        Thread.ofVirtual().name("hocubes-size-" + name).start(() -> {
            try {
                for (String l : ink.hoka.odyssey.server.tall.gen.DiskEstimate.fact(maps.dir(name))) info(s, l);
                var g = genFor(s, maps, name);
                if (g == null) return;
                for (String l : ink.hoka.odyssey.server.tall.gen.DiskEstimate.estimate(g, n)) info(s, l);
            } catch (Exception e) {
                err(s, "size «" + name + "»: " + e);
                org.slf4j.LoggerFactory.getLogger("tall").error("hocubes size " + name, e);
            }
        });
    }

    /** /hocubes gentest <карта> [N] — детерминизм и честность генератора. */
    private static void gentest(CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, String name, int n) {
        if (!maps.exists(name)) { err(s, "карты «" + name + "» нет"); return; }
        Thread.ofVirtual().name("hocubes-gentest-" + name).start(() -> {
            try {
                var g = genFor(s, maps, name);
                if (g == null) return;
                info(s, "gentest «" + name + "» (" + g.tag() + "): " + n + " кубов…");
                var r = ink.hoka.odyssey.server.tall.gen.GenTest.run(g, n);
                String line = String.format(java.util.Locale.ROOT, "кубов %d, %.3f мс/куб; недетерминированных %d, hasBlocks врёт %d, uniform врёт %d, highest врёт %d",
                        r.cubes(), r.msPerCube(), r.mismatches(), r.badHasBlocks(), r.badUniform(), r.badHighest());
                if (r.ok()) ok(s, "gentest: всё сходится — " + line);
                else { err(s, "gentest: " + line); for (String f : r.first()) err(s, "  " + f); }
            } catch (Exception e) {
                err(s, "gentest «" + name + "»: " + e);
                org.slf4j.LoggerFactory.getLogger("tall").error("hocubes gentest " + name, e);
            }
        });
    }

    /** Генератор карты: загруженной — её, иначе собрать по map.yml. */
    private static ink.hoka.odyssey.server.tall.gen.ModuleCubeGen genFor(CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, String name) {
        var live = maps.liveGen(name);
        if (live != null) return live;
        var meta = maps.meta(name);
        if (!meta.getBoolean("tall", false) || !"cubes".equalsIgnoreCase(meta.getString("store", "htw"))) {
            err(s, "«" + name + "» — не высокая карта на регионах кубов (tall: true, store: cubes)");
            return null;
        }
        var r = ink.hoka.odyssey.server.tall.gen.CubeGens.resolve(name, maps.dir(name), meta);
        if (r.error() != null) { err(s, r.error()); return null; }
        return r.gen();
    }

    /** Проверки перед convert/verify: высокая выгруженная карта; занять её (load вернёт null). Ответ — ключ измерения или null. */
    private static net.minestom.server.registry.RegistryKey<net.minestom.server.world.DimensionType> prepare(
            CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, String name) {
        if (!maps.exists(name)) { err(s, "карты «" + name + "» нет"); return null; }
        var key = TallWorld.key(name);
        if (key == null) { err(s, "«" + name + "» — не высокая карта (map.yml tall: true, нужен рестарт после правки)"); return null; }
        if (!maps.lockForConvert(name)) { err(s, "карта «" + name + "» загружена или уже обрабатывается — сначала /homap unload " + name); return null; }
        return key;
    }

    /** /hocubes convert — .htw → регионы, сверка, tall → tall.bak, store: cubes. В виртуальном потоке. */
    private static void convert(CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, String name) {
        var key = prepare(s, maps, name);
        if (key == null) return;
        java.nio.file.Path dir = maps.dir(name), tall = dir.resolve("tall");
        Thread.ofVirtual().name("hocubes-convert-" + name).start(() -> {
            try {
                var meta = maps.meta(name);
                if ("cubes".equalsIgnoreCase(meta.getString("store", "htw"))) { err(s, "«" + name + "» уже на регионах (store: cubes)"); return; }
                if (!java.nio.file.Files.isDirectory(tall)) { err(s, "нет папки " + tall + " — переводить нечего"); return; }
                info(s, "перевод «" + name + "»: .htw → регионы…");
                long t0 = System.currentTimeMillis();
                var r = CubeConvert.convert(dir, tall, key, m -> info(s, m));
                info(s, "записано: столбцов " + r.columns() + ", кубов " + r.cubes() + ", битых .htw " + r.broken()
                        + " (" + (System.currentTimeMillis() - t0) / 1000 + " с)");
                if (r.mismatches() > 0) { err(s, "ошибок записи " + r.mismatches() + ": " + r.first() + " — карта НЕ переключена"); return; }
                var v = CubeConvert.verify(dir, tall, key, m -> info(s, m));
                if (v.mismatches() > 0) { err(s, "сверка: расхождений " + v.mismatches() + ": " + v.first() + " — карта НЕ переключена, tall/ на месте"); return; }
                java.nio.file.Path bak = dir.resolve("tall.bak");
                if (java.nio.file.Files.exists(bak)) bak = dir.resolve("tall.bak." + System.currentTimeMillis());
                java.nio.file.Files.move(tall, bak);
                meta.set("store", "cubes");
                meta.save();
                ok(s, "«" + name + "» на регионах кубов: сверено " + v.columns() + " столбцов без расхождений; старое — в " + bak.getFileName()
                        + " (не стёрто). Загрузится при следующем входе на карту.");
            } catch (Exception e) {
                err(s, "перевод «" + name + "» не удался: " + e);
                org.slf4j.LoggerFactory.getLogger("tall").error("hocubes convert " + name, e);
            } finally {
                maps.unlockConvert(name);
            }
        });
    }

    /** /hocubes compact — сжать регионы выгруженной карты (заголовки без кубов — прочь, файлы — без дыр). */
    private static void compact(CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, String name) {
        var key = prepare(s, maps, name);
        if (key == null) return;
        java.nio.file.Path dir = maps.dir(name);
        Thread.ofVirtual().name("hocubes-compact-" + name).start(() -> {
            try {
                long[] r = CubeConvert.compact(dir, m -> info(s, m));
                ok(s, "«" + name + "» сжата: " + r[0] / 1024 + " КБ → " + r[1] / 1024 + " КБ, убрано заголовков без кубов: " + r[2]);
            } catch (Exception e) {
                err(s, "сжатие «" + name + "» не удалось: " + e);
                org.slf4j.LoggerFactory.getLogger("tall").error("hocubes compact " + name, e);
            } finally {
                maps.unlockConvert(name);
            }
        });
    }

    /** /hocubes verify — сверка .htw (tall/, иначе самый свежий tall.bak*) с регионами. */
    private static void verify(CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, String name) {
        var key = prepare(s, maps, name);
        if (key == null) return;
        java.nio.file.Path dir = maps.dir(name);
        Thread.ofVirtual().name("hocubes-verify-" + name).start(() -> {
            try {
                java.nio.file.Path src = dir.resolve("tall");
                if (!java.nio.file.Files.isDirectory(src)) {
                    src = null;
                    try (var st = java.nio.file.Files.list(dir)) {
                        for (var p : st.filter(p -> p.getFileName().toString().startsWith("tall.bak") && java.nio.file.Files.isDirectory(p)).toList())
                            if (src == null || java.nio.file.Files.getLastModifiedTime(p).compareTo(java.nio.file.Files.getLastModifiedTime(src)) > 0) src = p;
                    }
                }
                if (src == null) { err(s, "нет ни tall/, ни tall.bak/ — сверять не с чем"); return; }
                info(s, "сверка «" + name + "»: " + src.getFileName() + " против регионов…");
                var v = CubeConvert.verify(dir, src, key, m -> info(s, m));
                if (v.mismatches() == 0) ok(s, "сверено столбцов " + v.columns() + ": расхождений нет" + (v.broken() > 0 ? " (битых .htw " + v.broken() + ")" : ""));
                else err(s, "столбцов " + v.columns() + ", расхождений " + v.mismatches() + ": " + v.first());
            } catch (Exception e) {
                err(s, "сверка «" + name + "» не удалась: " + e);
            } finally {
                maps.unlockConvert(name);
            }
        });
    }

    private static void ok(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.GREEN)); }
    private static void info(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.AQUA)); }
    private static void err(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.RED)); }

    /** /hocubes go [y] — на кубовую карту «cubes» (только ядро 0.31+ с Sodium). */
    private static void go(CommandSender s, ink.hoka.odyssey.server.maps.Maps maps, int y) {
        if (!(s instanceof Player p)) return;
        var hi = Handshake.of(p);
        if (!hi.known() || !hi.sodium() || hi.core().compareTo("0.31") < 0) {
            s.sendMessage(Component.text("Кубовая карта: нужно ядро Hoka Odyssey 0.31+ и Sodium (у тебя: "
                    + (hi.known() ? hi.core() : "старое ядро") + (hi.sodium() ? "" : ", без Sodium") + ")", NamedTextColor.RED));
            return;
        }
        var m = maps.load("cubes");
        if (m == null || !TallWorld.isCubes(m.instance)) { s.sendMessage(Component.text("Нет карты «cubes» (map.yml: tall: true, cubes: true)", NamedTextColor.RED)); return; }
        maps.touch(m);
        var to = new net.minestom.server.coordinate.Pos(0.5, y, 0.5, p.getPosition().yaw(), p.getPosition().pitch());
        if (p.getInstance() == m.instance) p.teleport(to); else p.setInstance(m.instance, to);
        p.setFlying(true);
        s.sendMessage(Component.text("Кубовая карта: y = " + (y - 1), NamedTextColor.GREEN));
    }

    private static void stats(CommandSender s) {
        for (Instance inst : MinecraftServer.getInstanceManager().getInstances()) {
            long cols = 0, secs = 0, full = 0, objs = 0, cubeCols = 0;
            long[] lz = new long[5];
            long held = 0, dirty = 0, dirtyCols = 0;
            for (Chunk ch : inst.getChunks()) {
                cols++;
                if (ch instanceof CubeColumn cc) { cubeCols++; if (cc.isLazy()) { int[] n = cc.stateCounts(); for (int k = 0; k < 5; k++) lz[k] += n[k]; held += cc.heldCount(); } }
                if (ch instanceof TallColumn tc) { int d = tc.st().dirtyCount(); dirty += d; if (tc.isModified()) dirtyCols++; }
                objs += ch instanceof TallColumn tc ? tc.realSections() : ch.getSections().size();
                for (Section sec : ch.getSections()) { secs++; if (sec.blockPalette().count() > 0) full++; }
            }
            if (cols == 0) continue;
            String kind = inst.getCachedDimensionType().height() > 4064 ? "высокий" : "обычный";
            s.sendMessage(Component.text(String.format("%s (%s): столбцов %d, секций %d, непустых %d (%.1f%%)",
                    inst.getDimensionName(), kind, cols, secs, full, secs == 0 ? 0.0 : 100.0 * full / secs)
                    + ", объектов секций " + objs + (cubeCols > 0 ? ", из них CubeColumn " + cubeCols : ""), NamedTextColor.AQUA));
            if (CubeStore.lazy(inst))
                s.sendMessage(Component.text("  кубы по запросу: READY непустых " + lz[0] + " (загружено " + held + "), не в памяти " + lz[1]
                        + ", в очереди " + lz[2] + ", LOADING " + lz[3] + ", UNLOADING " + lz[4]
                        + "; выгрузка " + (CubeStore.unloading(inst) ? "вкл" : "выкл"), NamedTextColor.AQUA));
            if (dirtyCols > 0 || dirty > 0)
                s.sendMessage(Component.text("  не сохранено: кубов (dirty) " + dirty + ", столбцов " + dirtyCols, NamedTextColor.AQUA));
            if (inst instanceof net.minestom.server.instance.InstanceContainer ic && ic.getChunkLoader() instanceof CubeRegionLoader l) {
                String dim = inst.getDimensionName();
                Thread.ofVirtual().name("hocubes-du").start(() -> {           // обход папок — не в тике
                    long[] du = l.diskUsage();
                    s.sendMessage(Component.text(String.format("  регионы %s: заголовки %.1f МБ, кубы %.1f МБ, файлов %d",
                            dim, du[0] / 1048576.0, du[1] / 1048576.0, du[2]), NamedTextColor.GRAY));
                });
            }
        }
        s.sendMessage(Component.text("выгрузка кубов: выгружено " + CubeStore.unloads.get() + ", оставлено в памяти (попросили/не сохранилось) "
                + CubeStore.unloadKept.get() + ", сбоев сохранения " + CubeStore.unloadErrors.get(), NamedTextColor.AQUA));
        s.sendMessage(Component.text(CubeStreamer.stats(), NamedTextColor.AQUA));
        s.sendMessage(Component.text(CubeLight.stats(), NamedTextColor.AQUA));
        s.sendMessage(Component.text("столбцов выгружено (никто не видел минуту): " + ink.hoka.odyssey.server.maps.Maps.columnsReaped
                + ", оставлено из-за сущностей: " + ink.hoka.odyssey.server.maps.Maps.columnsKeptForEntities, NamedTextColor.AQUA));
        s.sendMessage(Component.text(CubeEntities.stats() + "; закреплено рамок " + Cubes.frames() + " (владельцев " + Cubes.owners() + ")", NamedTextColor.AQUA));
        if (CubeStore.violations.get() > 0)
            s.sendMessage(Component.text("⚠ нарушений правила записи (в незагруженный/выгружаемый куб): " + CubeStore.violations.get() + " (первый стек — в логе)", NamedTextColor.RED));
        else s.sendMessage(Component.text("нарушений правила записи: 0", NamedTextColor.GRAY));
        if (TallSections.poisoned()) s.sendMessage(Component.text("⚠ общая пустая секция испорчена: кто-то пишет мимо materialize!", NamedTextColor.RED));
        s.sendMessage(Component.text("свет в очереди: " + TallWorld.dirtyCount() + " столбцов; игроков онлайн: "
                + MinecraftServer.getConnectionManager().getOnlinePlayers().size(), NamedTextColor.GRAY));
    }

    private static void player(CommandSender s, String name) {
        Player p = MinecraftServer.getConnectionManager().findOnlinePlayer(name);
        if (p == null) { s.sendMessage(Component.text("Нет такого игрока онлайн", NamedTextColor.RED)); return; }
        var hi = Handshake.of(p);
        s.sendMessage(Component.text(p.getUsername() + ": ядро " + (hi.known() ? hi.core() + " (ответ " + hi.ms() + " мс)" : "не ответило (старше 0.29?)")
                + ", Sodium " + (hi.sodium() ? hi.sodiumVersion() : "нет"), NamedTextColor.AQUA));
        var pos = p.getPosition();
        int view = p.effectiveViewDistance();
        s.sendMessage(Component.text(String.format("мир %s, %d %d %d (секция Y %d), дальность %d → столбцов в окне %d",
                p.getInstance() == null ? "-" : p.getInstance().getDimensionName(), pos.blockX(), pos.blockY(), pos.blockZ(),
                pos.blockY() >> 4, view, (2 * view + 1) * (2 * view + 1)), NamedTextColor.GRAY));
        s.sendMessage(Component.text("по высоте ±" + CubeStreamer.vertical(p) + " кубов (предел сервера " + CubeStreamer.VERTICAL_MAX + ")", NamedTextColor.GRAY));
        s.sendMessage(Component.text(CubeCacheModel.describe(p.getUuid()), NamedTextColor.GRAY));
    }

    private static void dump(CommandSender s, Instance inst, int x, int y, int z) {
        if (inst == null) return;
        Chunk ch = inst.getChunk(x >> 4, z >> 4);
        if (ch == null) { s.sendMessage(Component.text("столбец " + (x >> 4) + " " + (z >> 4) + " не загружен", NamedTextColor.RED)); return; }
        Section sec = ch.getSectionAt(y);
        s.sendMessage(Component.text(String.format("столбец %d %d (%s), секция Y %d: блоков %d, палитра блоков %d бит; высота motion %d, surface %d",
                ch.getChunkX(), ch.getChunkZ(), ch.getClass().getSimpleName(), y >> 4, sec.blockPalette().count(),
                sec.blockPalette().bitsPerEntry(),
                ch.motionBlockingHeightmap().getHeight(x & 15, z & 15),
                ch.worldSurfaceHeightmap().getHeight(x & 15, z & 15)), NamedTextColor.AQUA));
        if (ch instanceof TallColumn tc) {                               // HeightIndex (шаг 4): Y верхнего блока, «—» — нет
            s.sendMessage(Component.text("колонка " + x + " " + z + ": skyTop " + h(tc.height(HeightIndex.Kind.SKY, x, z))
                    + ", motion " + h(tc.height(HeightIndex.Kind.MOTION, x, z)) + ", surface " + h(tc.height(HeightIndex.Kind.SURFACE, x, z))
                    + "; генератор: " + h(ink.hoka.odyssey.server.tall.gen.CubeGens.of(inst).highest(x, z, TallWorld.MIN_Y, TallWorld.MIN_Y + TallWorld.HEIGHT - 1, HeightIndex.Kind.SURFACE)),
                    NamedTextColor.AQUA));
            int i = (y >> 4) - (TallWorld.MIN_Y >> 4);
            if (i < 0 || i >= tc.getSections().size()) return;
            StringBuilder b = new StringBuilder("куб ").append(x >> 4).append(' ').append(y >> 4).append(' ').append(z >> 4).append(": ");
            if (tc instanceof CubeColumn cc) {
                b.append(cc.stateName(i));
                long idle = cc.idleMs(i, System.currentTimeMillis());
                if (idle >= 0) b.append(", без интереса ").append(idle / 1000).append(" с");
            } else b.append("в памяти");
            b.append(tc.st().isDirty(i) ? ", изменён (dirty)" : ", сохранён");
            var who = CubeStreamer.cubeViewers(tc, i);
            b.append("; у игроков: ").append(who.isEmpty() ? "ни у кого" : String.join(", ", who));
            var pins = Cubes.pinOwners(inst, x >> 4, y >> 4, z >> 4);
            b.append("; закреплён: ").append(pins.isEmpty() ? "нет" : String.join(", ", pins));
            int y0 = (y >> 4) << 4;
            long ents = inst.getChunkEntities(ch).stream().filter(e -> e.getPosition().blockY() >= y0 && e.getPosition().blockY() < y0 + 16).count();
            b.append("; сущностей ").append(ents);
            s.sendMessage(Component.text(b.toString(), NamedTextColor.AQUA));
        }
    }

    private static String h(int y) { return y == HeightIndex.NONE ? "—" : String.valueOf(y); }
}
