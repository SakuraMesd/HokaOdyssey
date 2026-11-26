package ink.hoka.odyssey.server.objects;

import ink.hoka.odyssey.gen.GenObject;
import ink.hoka.odyssey.gen.GenObjectCodec;
import ink.hoka.odyssey.gen.GenObjectMeta;
import ink.hoka.odyssey.gen.GenObjects;
import ink.hoka.odyssey.server.maps.Maps;
import ink.hoka.odyssey.server.perms.Ops;
import ink.hoka.odyssey.server.tall.gen.CubeGens;
import ink.hoka.odyssey.server.tall.gen.ModuleCubeGen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /hoobj — объекты-шаблоны для генерации (деревья, камни…), только OP:
 * <pre>
 * pos1|pos2 [feet]                      — угол выделения: блок под прицелом (нет — под ногами) или блок ног
 * sel | clear                           — показать / сбросить выделение
 * save &lt;кат/имя&gt; [center|feet] [keep-air] [force] — сохранить без воздуха в objects/&lt;кат&gt;/&lt;имя&gt;.hobj
 * list [кат] | info &lt;кат/имя&gt; | delete &lt;кат/имя&gt; (в objects/.trash)
 * paste &lt;кат/имя&gt; [0|90|180|270] [mirror] [force] — поставить якорем в ноги; undo — вернуть
 * preview &lt;кат/имя&gt; [0|90|180|270] [mirror]  — рамка частицами, без блоков
 * set &lt;кат/имя&gt; &lt;ключ&gt; &lt;значение&gt; | unset &lt;кат/имя&gt; &lt;ключ&gt; — метаданные (.json рядом)
 * anchor &lt;кат/имя&gt; feet|center|&lt;dx&gt; &lt;dy&gt; &lt;dz&gt;  — якорь: в ноги / центр-низ / сдвинуть
 * reload | pack [карта]
 * </pre>
 */
public final class HoObjCommand extends Command {

    private final ObjectLibrary lib;
    private final Maps maps;
    /** Где в мире лежит рамка объекта (после save или paste без поворота) — для anchor feet. */
    private final Map<UUID, Origin> origins = new ConcurrentHashMap<>();

    record Origin(String id, Instance inst, int x, int y, int z) {}

    public HoObjCommand(ObjectLibrary lib, Maps maps, Ops ops) {
        super("hoobj");
        this.lib = lib;
        this.maps = maps;
        setCondition(ops.admin());
        setDefaultExecutor((s, c) -> help(s));

        ArgumentWord obj = ArgumentType.Word("объект");
        obj.setSuggestionCallback((sender, ctx, sug) -> { for (String id : lib.ids()) sug.addEntry(new SuggestionEntry(id)); });
        ArgumentWord newObj = ArgumentType.Word("кат/имя");
        newObj.setSuggestionCallback((sender, ctx, sug) -> {
            for (String c : lib.categories()) sug.addEntry(new SuggestionEntry(c + "/"));
            for (String c : new String[]{"trees", "rocks", "bushes", "flowers", "ruins", "caves"}) sug.addEntry(new SuggestionEntry(c + "/"));
        });
        ArgumentWord cat = ArgumentType.Word("категория");
        cat.setSuggestionCallback((sender, ctx, sug) -> { for (String c : lib.categories()) sug.addEntry(new SuggestionEntry(c)); });
        ArgumentWord key = ArgumentType.Word("ключ");
        key.setSuggestionCallback((sender, ctx, sug) -> { for (String k : GenObjectMeta.KEYS.keySet()) sug.addEntry(new SuggestionEntry(k)); });
        ArgumentStringArray opts = ArgumentType.StringArray("опции");
        ArgumentStringArray value = ArgumentType.StringArray("значение");
        ArgumentWord map = ArgumentType.Word("карта");
        map.setSuggestionCallback((sender, ctx, sug) -> { for (String n : maps.names()) sug.addEntry(new SuggestionEntry(n)); });

        for (int w = 1; w <= 2; w++) {
            int which = w;
            addSyntax((s, c) -> pos(s, which, false), ArgumentType.Literal("pos" + w));
            addSyntax((s, c) -> pos(s, which, true), ArgumentType.Literal("pos" + w), ArgumentType.Literal("feet"));
        }
        addSyntax((s, c) -> sel(s), ArgumentType.Literal("sel"));
        addSyntax((s, c) -> { if (s instanceof Player p) { Selections.clear(p); ok(s, "Выделение сброшено"); } }, ArgumentType.Literal("clear"));
        addSyntax((s, c) -> save(s, c.get(newObj), new String[0]), ArgumentType.Literal("save"), newObj);
        addSyntax((s, c) -> save(s, c.get(newObj), c.get(opts)), ArgumentType.Literal("save"), newObj, opts);
        addSyntax((s, c) -> list(s, null), ArgumentType.Literal("list"));
        addSyntax((s, c) -> list(s, c.get(cat)), ArgumentType.Literal("list"), cat);
        addSyntax((s, c) -> showInfo(s, c.get(obj)), ArgumentType.Literal("info"), obj);
        addSyntax((s, c) -> delete(s, c.get(obj)), ArgumentType.Literal("delete"), obj);
        addSyntax((s, c) -> paste(s, c.get(obj), new String[0], false), ArgumentType.Literal("paste"), obj);
        addSyntax((s, c) -> paste(s, c.get(obj), c.get(opts), false), ArgumentType.Literal("paste"), obj, opts);
        addSyntax((s, c) -> paste(s, c.get(obj), new String[0], true), ArgumentType.Literal("preview"), obj);
        addSyntax((s, c) -> paste(s, c.get(obj), c.get(opts), true), ArgumentType.Literal("preview"), obj, opts);
        addSyntax((s, c) -> undo(s), ArgumentType.Literal("undo"));
        addSyntax((s, c) -> set(s, c.get(obj), c.get(key), String.join(" ", c.get(value))), ArgumentType.Literal("set"), obj, key, value);
        addSyntax((s, c) -> unset(s, c.get(obj), c.get(key)), ArgumentType.Literal("unset"), obj, key);
        addSyntax((s, c) -> anchor(s, c.get(obj), c.get(opts)), ArgumentType.Literal("anchor"), obj, opts);
        addSyntax((s, c) -> reload(s), ArgumentType.Literal("reload"));
        addSyntax((s, c) -> pack(s, null), ArgumentType.Literal("pack"));
        addSyntax((s, c) -> pack(s, c.get(map)), ArgumentType.Literal("pack"), map);
    }

    private void help(CommandSender s) {
        info(s, "/hoobj pos1|pos2 [feet] — угол выделения (прицел / ноги); sel — показать; clear — сбросить");
        info(s, "/hoobj save <кат/имя> [center|feet] [keep-air] [force] — сохранить без воздуха (якорь: центр-низ или ноги)");
        info(s, "/hoobj list [кат] | info <кат/имя> | delete <кат/имя>");
        info(s, "/hoobj paste <кат/имя> [0|90|180|270] [mirror] [force] — поставить якорем в ноги; undo — вернуть");
        info(s, "/hoobj preview <кат/имя> [0|90|180|270] [mirror] — рамка частицами");
        info(s, "/hoobj set <кат/имя> <ключ> <значение> | unset <кат/имя> <ключ> — ключи: " + String.join(", ", GenObjectMeta.KEYS.keySet()) + ", x.*");
        info(s, "/hoobj anchor <кат/имя> feet|center|<dx> <dy> <dz> | reload | pack [карта]");
    }

    // ───────────── выделение ─────────────

    private static Point feet(Player p) { return new Vec(p.getPosition().blockX(), p.getPosition().blockY(), p.getPosition().blockZ()); }

    private void pos(CommandSender s, int which, boolean atFeet) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        Point b;
        if (atFeet) b = feet(p);
        else {
            b = p.getTargetBlockPosition(64);
            if (b == null) b = feet(p).sub(0, 1, 0);
        }
        Selections.Sel sel = Selections.set(p, which, b);
        ok(s, "pos" + which + ": " + b.blockX() + " " + b.blockY() + " " + b.blockZ() + (sel.complete() ? " — " + describe(sel) : ""));
    }

    private static String describe(Selections.Sel s) {
        return (s.maxX() - s.minX() + 1) + "×" + (s.maxY() - s.minY() + 1) + "×" + (s.maxZ() - s.minZ() + 1) + ", объём " + s.volume();
    }

    private void sel(CommandSender s) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        Selections.Sel sel = Selections.get(p);
        if (sel == null) { err(s, "выделения нет: /hoobj pos1 и pos2"); return; }
        Selections.show(p);
        if (!sel.complete()) { info(s, "pos1 " + str(sel.pos1()) + ", pos2 " + str(sel.pos2())); return; }
        int air = 0;
        long vol = sel.volume();
        boolean counted = false;
        if (vol <= 262_144 && sel.inst() == p.getInstance()) {                // небольшое — посчитать сразу (незагруженное — воздух)
            try {
                for (int x = sel.minX(); x <= sel.maxX(); x++) for (int y = sel.minY(); y <= sel.maxY(); y++) for (int z = sel.minZ(); z <= sel.maxZ(); z++)
                    if (sel.inst().getBlock(x, y, z).isAir()) air++;
                counted = true;
            } catch (RuntimeException ignored) {}                              // столбец не загружен — без подсчёта
        }
        info(s, describe(sel) + (counted ? ", не-воздуха ≈ " + (vol - air) : ""));
        String why = ObjectCapture.checkSize(sel);
        if (why != null) err(s, why);
    }

    private static String str(Point p) { return p == null ? "—" : p.blockX() + " " + p.blockY() + " " + p.blockZ(); }

    // ───────────── save ─────────────

    private void save(CommandSender s, String id, String[] opts) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        try { ObjectLibrary.checkId(id); } catch (IllegalArgumentException e) { err(s, e.getMessage()); return; }
        boolean keepAir = false, force = false, atFeet = false;
        for (String o : opts) {
            switch (o) {
                case "keep-air" -> keepAir = true;
                case "force" -> force = true;
                case "feet" -> atFeet = true;
                case "center" -> atFeet = false;
                default -> { err(s, "опция «" + o + "»: center, feet, keep-air, force"); return; }
            }
        }
        Selections.Sel sel = Selections.get(p);
        if (sel == null || !sel.complete()) { err(s, "нет выделения: /hoobj pos1 и pos2"); return; }
        if (sel.inst() != p.getInstance()) { err(s, "выделение в другом мире"); return; }
        String why = ObjectCapture.checkSize(sel);
        if (why != null) { err(s, why); return; }
        if (lib.get(id) != null && !force) { err(s, "объект " + id + " уже есть — добавьте force"); return; }
        Point f = feet(p);
        boolean fk = keepAir, ff = force, fa = atFeet;
        info(s, "Читаю " + describe(sel) + "…");
        ObjectCapture.capture(sel.inst(), sel, keepAir).whenComplete((r, e) -> {
            if (e != null) { err(s, "не сохранено: " + msg(e)); return; }
            try {
                var b = r.builder();
                if (fa) b.anchor(f.blockX(), f.blockY(), f.blockZ());
                GenObject o = lib.save(b.build(id, null), ff);
                origins.put(p.getUuid(), new Origin(id, sel.inst(), b.minX(), b.minY(), b.minZ()));
                ok(s, "Сохранён " + id + ": " + o.sizeX() + "×" + o.sizeY() + "×" + o.sizeZ() + ", блоков " + o.count() + ", палитра "
                        + o.palette().size() + ", якорь " + o.anchorX() + " " + o.anchorY() + " " + o.anchorZ()
                        + (o.hasData() ? ", блок-сущностей " + o.data().size() : "") + (fk ? ", с воздухом (keep-air)" : ""));
                info(s, "В генерацию попадёт после /homap unload + load карты (/hoobj pack).");
            } catch (Exception ex) { err(s, "не сохранено: " + msg(ex)); }
        });
    }

    private static String msg(Throwable e) {
        Throwable t = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    // ───────────── list / info / delete ─────────────

    private void list(CommandSender s, String cat) {
        if (cat == null) {
            if (lib.categories().isEmpty()) info(s, "Объектов нет (папка " + lib.root() + ")");
            for (String c : lib.categories()) info(s, c + ": " + lib.category(c).size());
        } else {
            var l = lib.category(cat);
            if (l.isEmpty()) info(s, "в «" + cat + "» пусто");
            for (GenObject o : l)
                info(s, o.id() + " — " + o.sizeX() + "×" + o.sizeY() + "×" + o.sizeZ() + ", блоков " + o.count() + ", вес " + o.meta().weight());
        }
        lib.errors().forEach((id, e) -> err(s, id + ": " + e));
    }

    private void showInfo(CommandSender s, String id) {
        GenObject o = lib.get(id);
        if (o == null) { err(s, "нет объекта " + id + (lib.errors().containsKey(id) ? ": " + lib.errors().get(id) : "")); return; }
        info(s, o.id() + ": " + o.sizeX() + "×" + o.sizeY() + "×" + o.sizeZ() + ", блоков " + o.count() + ", якорь " + o.anchorX() + " "
                + o.anchorY() + " " + o.anchorZ() + (o.keepAir() ? ", keep-air" : ""));
        info(s, "охват ±" + o.reach() + ", вверх " + o.up() + ", вниз " + o.down());
        var pal = o.palette();
        info(s, "палитра (" + pal.size() + "): " + String.join(", ", pal.subList(0, Math.min(10, pal.size()))) + (pal.size() > 10 ? " …" : ""));
        if (o.hasData()) info(s, "блок-сущностей: " + o.data().size());
        info(s, "метаданные: " + ink.hoka.odyssey.gen.GenJson.write(o.meta().raw()));
        info(s, "хэш " + String.format("%016x", GenObjects.hash(GenObjectCodec.writeBody(o))) + ", файл " + lib.fileSize(id) + " Б");
        String lim = GenObjects.checkLimits(o);
        if (lim != null) err(s, "в генерацию не попадёт: " + lim);
        else if (o.reach() > 32) err(s, "большой охват (" + o.reach() + ") — генерация медленнее");
    }

    private void delete(CommandSender s, String id) {
        try { ok(s, "Удалён " + id + " → " + lib.delete(id)); }
        catch (Exception e) { err(s, msg(e)); }
    }

    // ───────────── paste / preview / undo ─────────────

    private void paste(CommandSender s, String id, String[] opts, boolean preview) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        GenObject o = lib.get(id);
        if (o == null) { err(s, "нет объекта " + id); return; }
        int rot = 0;
        boolean mir = false, force = false;
        for (String a : opts) {
            switch (a) {
                case "0" -> rot = 0;
                case "90" -> rot = 1;
                case "180" -> rot = 2;
                case "270" -> rot = 3;
                case "mirror" -> mir = true;
                case "force" -> force = true;
                default -> { err(s, "опция «" + a + "»: 0|90|180|270, mirror" + (preview ? "" : ", force")); return; }
            }
        }
        Point f = feet(p);
        int ax = f.blockX(), ay = f.blockY(), az = f.blockZ();
        int[] b = ObjectPaste.bounds(o, ax, ay, az, rot, mir);
        if (preview) {
            Selections.preview(p, new Selections.Preview(p.getInstance(), b[0], b[1], b[2], b[3], b[4], b[5], ax, ay, az));
            ok(s, "Предпросмотр " + id + ": рамка " + (b[3] - b[0] + 1) + "×" + (b[4] - b[1] + 1) + "×" + (b[5] - b[2] + 1) + ", якорь — голубой (60 с)");
            return;
        }
        int frot = rot;
        boolean fmir = mir;
        Instance inst = p.getInstance();
        ObjectPaste.paste(p, inst, o, ax, ay, az, rot, mir, force).whenComplete((r, e) -> {
            if (e != null) { err(s, "не вставлено: " + msg(e)); return; }
            if (frot == 0 && !fmir) origins.put(p.getUuid(), new Origin(id, inst, ax - o.anchorX(), ay - o.anchorY(), az - o.anchorZ()));
            ok(s, "Поставлен " + id + ": блоков " + r.blocks() + (r.skippedCubes() > 0 ? ", пропущено кубов (не загрузились) " + r.skippedCubes() : "")
                    + " — /hoobj undo вернёт");
        });
    }

    private void undo(CommandSender s) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        int n = ObjectPaste.undo(p);
        if (n < 0) err(s, "нечего отменять");
        else ok(s, "Возвращаю " + n + " блоков");
    }

    // ───────────── метаданные / якорь ─────────────

    private void set(CommandSender s, String id, String key, String value) {
        try {
            GenObject o = lib.setMeta(id, key, value);
            ok(s, id + ": " + key + " = " + ink.hoka.odyssey.gen.GenJson.write(o.meta().raw().get(key)));
        } catch (Exception e) { err(s, msg(e)); }
    }

    private void unset(CommandSender s, String id, String key) {
        try { lib.unsetMeta(id, key); ok(s, id + ": " + key + " убран"); }
        catch (Exception e) { err(s, msg(e)); }
    }

    private void anchor(CommandSender s, String id, String[] a) {
        GenObject o = lib.get(id);
        if (o == null) { err(s, "нет объекта " + id); return; }
        try {
            GenObject n;
            if (a.length == 1 && "center".equals(a[0])) n = lib.setAnchor(id, Math.floorDiv(o.sizeX() - 1, 2), 0, Math.floorDiv(o.sizeZ() - 1, 2));
            else if (a.length == 1 && "feet".equals(a[0])) {
                if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
                Origin or = origins.get(p.getUuid());
                if (or == null || !or.id().equals(id) || or.inst() != p.getInstance()) {
                    err(s, "не знаю, где " + id + " стоит в мире: сначала save или paste без поворота");
                    return;
                }
                Point f = feet(p);
                n = lib.setAnchor(id, f.blockX() - or.x(), f.blockY() - or.y(), f.blockZ() - or.z());
            } else if (a.length == 3) {
                int dx = Integer.parseInt(a[0]), dy = Integer.parseInt(a[1]), dz = Integer.parseInt(a[2]);
                n = lib.setAnchor(id, o.anchorX() + dx, o.anchorY() + dy, o.anchorZ() + dz);
            } else { err(s, "/hoobj anchor <кат/имя> feet|center|<dx> <dy> <dz>"); return; }
            ok(s, id + ": якорь " + n.anchorX() + " " + n.anchorY() + " " + n.anchorZ() + " (в рамке " + n.sizeX() + "×" + n.sizeY() + "×" + n.sizeZ() + ")");
        } catch (NumberFormatException e) { err(s, "сдвиг — три целых числа"); }
        catch (Exception e) { err(s, msg(e)); }
    }

    // ───────────── reload / pack ─────────────

    private void reload(CommandSender s) {
        int n = lib.scan();
        ok(s, "Объектов: " + n + ", категорий " + lib.categories().size() + (lib.errors().isEmpty() ? "" : ", с ошибками " + lib.errors().size()));
        lib.errors().forEach((id, e) -> err(s, id + ": " + e));
        info(s, "Карты применят изменения после /homap unload + load (/hoobj pack).");
    }

    private void pack(CommandSender s, String mapName) {
        String name = mapName;
        if (name == null) {
            if (!(s instanceof Player p)) { err(s, "/hoobj pack <карта>"); return; }
            ModuleCubeGen here = CubeGens.module(p.getInstance());
            for (String n : maps.names()) if (here != null && maps.liveGen(n) == here) name = n;
            if (name == null) { err(s, "здесь нет генератора карты — /hoobj pack <карта>"); return; }
        }
        ModuleCubeGen g = maps.liveGen(name);
        if (g == null) { err(s, "карта «" + name + "» не загружена или без генератора"); return; }
        var pp = g.portableProvider();
        if (pp == null) { err(s, "генератор карты не переносимый — объектов нет"); return; }
        GenObjects now = g.objects, disk;
        try { disk = ObjectPacks.forMap(maps.meta(name).get("objects"), pp.wantsObjects); }
        catch (Exception e) { err(s, "с диска не собрать: " + msg(e)); return; }
        info(s, "«" + name + "» (" + g.tag() + "): пакет " + hex(now.hash()) + ", объектов " + now.size() + ", категории " + now.categories());
        if (now.hash() == disk.hash()) ok(s, "с диска — то же");
        else err(s, "с диска — " + hex(disk.hash()) + " (объектов " + disk.size() + "): применится после /homap unload " + name + " и загрузки");
    }

    private static String hex(long h) { return h == 0 ? "пусто" : String.format("%016x", h); }

    private static void ok(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.GREEN)); }
    private static void info(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.AQUA)); }
    private static void err(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.RED)); }
}
