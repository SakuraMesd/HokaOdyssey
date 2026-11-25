package ink.hoka.odyssey.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

import java.nio.file.Files;
import java.util.*;

/**
 * /hoship — корабли.
 * <pre>
 * Игрокам:
 *   /hoship list               — какие корабли можно призвать
 *   /hoship summon &lt;корабль&gt;   — призвать свой корабль рядом (на свободное место; старый перенесётся)
 *   /hoship fly                — взлететь (стоя на своём корабле); или ПКМ по месту пилота
 *   /hoship land               — посадка (или клавиша F в полёте)
 *   /hoship dismiss            — убрать свой корабль
 * Админам (OP) — создание чертежа: построить корабль из блоков и
 *   /hoship sel1 | sel2        — углы корабля (блок, на который смотрите)
 *   /hoship seat               — место пилота (блок, на который смотрите)
 *   /hoship chest              — отметить/снять сундук (блок, на который смотрите)
 *   /hoship zone1 | zone2      — углы зоны, где игрокам можно строить; /hoship zoneadd — добавить её
 *   /hoship zoneclear          — убрать все зоны
 *   /hoship info               — что выбрано
 *   /hoship save &lt;имя&gt;         — сохранить чертёж
 *   /hoship delete &lt;имя&gt;       — удалить чертёж (если на нём нет кораблей игроков)
 * </pre>
 */
final class HoShipCommand extends Command {

    /** Выделение админа. */
    private static final class Session {
        Instance inst;
        Point sel1, sel2, seat, zone1, zone2;
        String forward;                                                        // перед корабля — куда смотрел админ при /hoship seat
        final List<Point> chests = new ArrayList<>();
        final List<Point[]> zones = new ArrayList<>();
    }

    private final ShipsModule mod;
    private final Map<UUID, Session> sessions = new HashMap<>();

    HoShipCommand(ShipsModule mod) {
        super("hoship");
        this.mod = mod;
        setDefaultExecutor((s, c) -> help(s));

        var tpl = ArgumentType.Word("корабль").setSuggestionCallback((sender, ctx, sug) -> {
            for (String n : mod.templates.keySet()) sug.addEntry(new SuggestionEntry(n));
        });
        var name = ArgumentType.Word("имя");

        player("list", p -> list(p));
        addSyntax((s, c) -> { if (s instanceof Player p) summon(p, c.get(tpl)); }, ArgumentType.Literal("summon"), tpl);
        player("fly", this::fly);
        player("land", p -> {
            Flight f = mod.flightOf(p);
            if (f == null) err(p, "Вы не управляете кораблём");
            else f.land();
        });
        player("body", this::body);
        player("deck", p -> {                                                   // админ: встать на сиденье своего корабля-модели
            if (!isOp(p)) { deny(p); return; }
            Ship s = mod.ships.get(p.getUuid().toString());
            if (s == null || s.state != Ship.State.MODEL) { err(p, "Корабль не стоит моделью"); return; }
            p.setFlying(false);
            p.teleport(new net.minestom.server.coordinate.Pos(s.mx, s.my - s.mseatH + 1.02, s.mz, s.myaw, 0));
        });
        addSyntax((s, c) -> { if (s instanceof Player p) yard(p, "build"); }, ArgumentType.Literal("yard"), ArgumentType.Literal("build"));
        addSyntax((s, c) -> { if (s instanceof Player p) yard(p, "tp"); }, ArgumentType.Literal("yard"), ArgumentType.Literal("tp"));
        addSyntax((s, c) -> { if (s instanceof Player p) yard(p, "back"); }, ArgumentType.Literal("yard"), ArgumentType.Literal("back"));
        addSyntax((s, c) -> { if (s instanceof Player p) yard(p, "turn"); }, ArgumentType.Literal("yard"), ArgumentType.Literal("turn"));
        player("dismiss", p -> { if (!mod.dismiss(p)) err(p, "У вас нет корабля"); });

        admin("sel1", p -> mark(p, (ss, b) -> { ss.sel1 = b; return "угол 1"; }));
        admin("sel2", p -> mark(p, (ss, b) -> { ss.sel2 = b; return "угол 2"; }));
        admin("seat", p -> mark(p, (ss, b) -> {
            ss.seat = b;
            ss.forward = ShipTemplate.dirOf(p.getPosition().yaw());
            return "место пилота (перед корабля — " + ru(ss.forward) + ", куда вы смотрите)";
        }));
        admin("chest", p -> mark(p, (ss, b) -> {
            String n = p.getInstance().getBlock(b).key().value();
            if (!n.contains("chest") && !n.equals("barrel")) return null;
            boolean removed = ss.chests.removeIf(x -> x.sameBlock(b));
            if (!removed) ss.chests.add(b);
            return removed ? "сундук снят" : "сундук отмечен (" + ss.chests.size() + ")";
        }));
        admin("zone1", p -> mark(p, (ss, b) -> { ss.zone1 = b; return "угол зоны 1"; }));
        admin("zone2", p -> mark(p, (ss, b) -> { ss.zone2 = b; return "угол зоны 2"; }));
        admin("zoneadd", p -> {
            Session ss = session(p);
            if (ss.zone1 == null || ss.zone2 == null) { err(p, "Сначала /hoship zone1 и zone2"); return; }
            ss.zones.add(new Point[]{ss.zone1, ss.zone2});
            ss.zone1 = ss.zone2 = null;
            ok(p, "Зона строительства добавлена (" + ss.zones.size() + ")");
        });
        admin("zoneclear", p -> { session(p).zones.clear(); ok(p, "Зоны убраны"); });
        admin("info", this::info);
        admin("forward", this::forward);
        addSyntax((s, c) -> { if (s instanceof Player p && isOp(p)) save(p, c.get(name).toLowerCase()); else deny(s); },
                ArgumentType.Literal("save"), name);
        addSyntax((s, c) -> { if (!(s instanceof Player p) || isOp(p)) delete(s, c.get(tpl)); else deny(s); },
                ArgumentType.Literal("delete"), tpl);
    }

    // ───────────────────────────── регистрация подкоманд ─────────────────────────────

    private void player(String sub, java.util.function.Consumer<Player> run) {
        addSyntax((s, c) -> { if (s instanceof Player p) run.accept(p); else err(s, "только в игре"); }, ArgumentType.Literal(sub));
    }

    private void admin(String sub, java.util.function.Consumer<Player> run) {
        addSyntax((s, c) -> {
            if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
            if (!isOp(p)) { deny(s); return; }
            run.accept(p);
        }, ArgumentType.Literal(sub));
    }

    private boolean isOp(Player p) { return mod.server().isOp(p); }

    private void help(CommandSender s) {
        info(s, "/hoship list — корабли  |  summon <корабль> — призвать  |  fly / land — взлёт / посадка  |  body — масса и двигатели  |  dismiss — убрать");
        if (!(s instanceof Player p) || isOp(p))
            info(s, "Админ: постройте корабль → sel1, sel2 (углы), seat (место пилота; смотрите вперёд), chest (сундуки), zone1/zone2 + zoneadd (где строить) → save <имя>. forward — перед у готового корабля");
    }

    // ───────────────────────────── игрок ─────────────────────────────

    private void list(Player p) {
        if (mod.templates.isEmpty()) { info(p, "Кораблей пока нет — их создаёт администратор"); return; }
        info(p, "Корабли (/hoship summon <имя>):");
        for (ShipTemplate t : mod.templates.values())
            p.sendMessage(Component.text("  " + t.name + " — " + t.sx + "×" + t.sy + "×" + t.sz + ", блоков " + t.solidCount(), NamedTextColor.GRAY));
        Ship mine = mod.ships.get(p.getUuid().toString());
        if (mine != null) {
            String st = switch (mine.state) {
                case PARKED -> "стоит: " + mine.map + " " + mine.seatWorld().blockX() + " " + mine.seatWorld().blockY() + " " + mine.seatWorld().blockZ();
                case FLYING -> "в полёте";
                case STOWED -> "убран (призовите снова)";
                case MODEL -> "стоит «как есть»: " + mine.map + " " + Math.round(mine.mx) + " " + Math.round(mine.my) + " " + Math.round(mine.mz) + " (/hoship fly рядом)";
            };
            info(p, "Ваш корабль «" + mine.template + "» — " + st);
        }
    }

    private void summon(Player p, String name) {
        ShipTemplate t = mod.templates.get(name.toLowerCase());
        if (t == null) { err(p, "Нет корабля «" + name + "». Список: /hoship list"); return; }
        mod.summon(p, t);
    }

    private void fly(Player p) {
        Ship s = mod.ships.get(p.getUuid().toString());
        if (s == null) { err(p, "У вас нет корабля — /hoship summon <корабль>"); return; }
        if (s.state == Ship.State.MODEL) {                                    // стоит моделью «как есть»
            if (!mod.nearModel(p, s, 12)) { err(p, "Подойдите к своему кораблю"); return; }
            mod.takeOff(p, s);
            return;
        }
        if (s.state != Ship.State.PARKED || !s.around(p.getPosition()) || mod.instanceOf(s) != p.getInstance()) {
            err(p, "Встаньте на свой корабль (или ПКМ по месту пилота)");
            return;
        }
        mod.takeOff(p, s);
    }

    // ───────────────────────────── админ: выделение ─────────────────────────────

    private Session session(Player p) { return sessions.computeIfAbsent(p.getUuid(), k -> new Session()); }

    /** Блок, на который смотрит админ → в выделение. fn возвращает подпись или null (не подходит). */
    private void mark(Player p, java.util.function.BiFunction<Session, Point, String> fn) {
        Point b = p.getTargetBlockPosition(8);
        if (b == null) { err(p, "Посмотрите на блок (до 8 блоков)"); return; }
        Session ss = session(p);
        if (ss.inst != null && ss.inst != p.getInstance()) { sessions.remove(p.getUuid()); ss = session(p); }
        ss.inst = p.getInstance();
        Point blk = new Vec(b.blockX(), b.blockY(), b.blockZ());
        String what = fn.apply(ss, blk);
        if (what == null) { err(p, "Это не сундук/бочка"); return; }
        ok(p, what + ": " + blk.blockX() + " " + blk.blockY() + " " + blk.blockZ() + sizeHint(ss));
    }

    private String sizeHint(Session ss) {
        if (ss.sel1 == null || ss.sel2 == null) return "";
        int[] d = dims(ss);
        return "  (корабль " + d[0] + "×" + d[1] + "×" + d[2] + ")";
    }

    private static int[] dims(Session ss) {
        return new int[]{Math.abs(ss.sel1.blockX() - ss.sel2.blockX()) + 1, Math.abs(ss.sel1.blockY() - ss.sel2.blockY()) + 1,
                Math.abs(ss.sel1.blockZ() - ss.sel2.blockZ()) + 1};
    }

    private void info(Player p) {
        Session ss = session(p);
        info(p, "Углы: " + fmt(ss.sel1) + " — " + fmt(ss.sel2) + sizeHint(ss));
        info(p, "Место пилота: " + fmt(ss.seat) + ", сундуков: " + ss.chests.size() + ", зон строительства: " + ss.zones.size()
                + (ss.zone1 != null || ss.zone2 != null ? " (не добавлена: " + fmt(ss.zone1) + " — " + fmt(ss.zone2) + ")" : ""));
    }

    private static String ru(String d) {
        return switch (d) { case "north" -> "север"; case "south" -> "юг"; case "west" -> "запад"; default -> "восток"; };
    }

    private static String fmt(Point p) { return p == null ? "—" : p.blockX() + " " + p.blockY() + " " + p.blockZ(); }

    /** Перед корабля для уже сохранённого чертежа: встать на стоящий корабль и смотреть вперёд. */
    private void forward(Player p) {
        Ship s = null;
        for (Ship x : mod.ships.values())
            if (x.state == Ship.State.PARKED && x.around(p.getPosition()) && mod.instanceOf(x) == p.getInstance()) { s = x; break; }
        if (s == null) { err(p, "Встаньте на стоящий корабль и смотрите туда, где у него перед"); return; }
        String world = ShipTemplate.dirOf(p.getPosition().yaw());
        s.t.forward = Rot.dir(world, 4 - (s.rot & 3));                       // в координаты чертежа (поворот 0)
        try { s.t.save(mod.templatesDir().resolve(s.t.name + ".json")); }
        catch (Exception e) { err(p, "Не сохранить: " + e.getMessage()); return; }
        ok(p, "Перед корабля «" + s.t.name + "» — " + ru(world) + " (для всех кораблей этого чертежа)");
    }

    // ───────────────────────────── админ: сохранить / удалить ─────────────────────────────

    private void save(Player p, String name) {
        if (!name.matches("[a-z0-9_\\-]{1,32}")) { err(p, "Имя: a-z 0-9 _ - (до 32)"); return; }
        Session ss = session(p);
        if (ss.sel1 == null || ss.sel2 == null) { err(p, "Сначала углы: /hoship sel1, /hoship sel2"); return; }
        if (ss.seat == null) { err(p, "Сначала место пилота: /hoship seat"); return; }
        if (ss.inst != p.getInstance()) { err(p, "Выделение сделано на другой карте"); return; }
        if (mod.templates.containsKey(name) && mod.ships.values().stream().anyMatch(s -> s.template.equals(name))) {
            err(p, "На чертеже «" + name + "» уже есть корабли игроков — сохраните под другим именем");
            return;
        }
        int x0 = Math.min(ss.sel1.blockX(), ss.sel2.blockX()), y0 = Math.min(ss.sel1.blockY(), ss.sel2.blockY()), z0 = Math.min(ss.sel1.blockZ(), ss.sel2.blockZ());
        int[] d = dims(ss);
        long volume = (long) d[0] * d[1] * d[2];
        if (d[0] > 128 || d[1] > 128 || d[2] > 128 || volume > 500_000) { err(p, "Выделение слишком большое: " + d[0] + "×" + d[1] + "×" + d[2] + " (до 128 по стороне)"); return; }
        if (!mod.server().areCubesLoaded(p.getInstance(), new net.minestom.server.coordinate.Vec(x0, y0, z0),
                new net.minestom.server.coordinate.Vec(x0 + d[0] - 1, y0 + d[1] - 1, z0 + d[2] - 1))) { err(p, "Часть корабля в незагруженном мире — подойдите ближе"); return; }
        int[] seat = rel(ss.seat, x0, y0, z0, d);
        if (seat == null) { err(p, "Место пилота вне выделения"); return; }
        Instance inst = p.getInstance();
        Block[] blocks = new Block[d[0] * d[1] * d[2]];
        int solid = 0;
        for (int y = 0; y < d[1]; y++) for (int z = 0; z < d[2]; z++) for (int x = 0; x < d[0]; x++) {
            Block b = inst.getBlock(x0 + x, y0 + y, z0 + z);
            blocks[(y * d[2] + z) * d[0] + x] = b;
            if (!b.isAir()) solid++;
        }
        int max = mod.config().getInt("templates.max-blocks", 3000);
        if (solid > max) { err(p, "Блоков " + solid + " — больше предела " + max + " (templates.max-blocks)"); return; }
        if (solid == 0) { err(p, "В выделении нет блоков"); return; }
        if (blocks[(seat[1] * d[2] + seat[2]) * d[0] + seat[0]].isAir()) { err(p, "Место пилота — воздух; выберите блок"); return; }
        ShipTemplate t = ShipTemplate.of(name, p.getUsername(), d[0], d[1], d[2], blocks);
        t.seat = seat;
        t.forward = ss.forward;
        for (Point c : ss.chests) { int[] r = rel(c, x0, y0, z0, d); if (r != null) t.chests.add(r); }
        for (Point[] z : ss.zones) {
            if (!intersects(z, x0, y0, z0, d)) continue;                      // зона от другого корабля — не берём
            int[] a = clip(z[0], x0, y0, z0, d), b = clip(z[1], x0, y0, z0, d);
            t.zones.add(new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])});
        }
        try {
            t.save(mod.templatesDir().resolve(name + ".json"));
        } catch (Exception e) {
            err(p, "Не сохранить: " + e.getMessage());
            return;
        }
        mod.templates.put(name, t);
        mod.modelChanged(t);
        sessions.remove(p.getUuid());                                           // следующий чертёж — с чистого выделения
        ok(p, "Корабль «" + name + "» сохранён: " + d[0] + "×" + d[1] + "×" + d[2] + ", блоков " + solid + ", сундуков " + t.chests.size()
                + ", зон " + t.zones.size() + ". Игроки: /hoship summon " + name);
    }

    private static int[] rel(Point p, int x0, int y0, int z0, int[] d) {
        int x = p.blockX() - x0, y = p.blockY() - y0, z = p.blockZ() - z0;
        return x < 0 || y < 0 || z < 0 || x >= d[0] || y >= d[1] || z >= d[2] ? null : new int[]{x, y, z};
    }

    private static boolean intersects(Point[] z, int x0, int y0, int z0, int[] d) {
        int ax = Math.min(z[0].blockX(), z[1].blockX()), bx = Math.max(z[0].blockX(), z[1].blockX());
        int ay = Math.min(z[0].blockY(), z[1].blockY()), by = Math.max(z[0].blockY(), z[1].blockY());
        int az = Math.min(z[0].blockZ(), z[1].blockZ()), bz = Math.max(z[0].blockZ(), z[1].blockZ());
        return bx >= x0 && ax < x0 + d[0] && by >= y0 && ay < y0 + d[1] && bz >= z0 && az < z0 + d[2];
    }

    private static int[] clip(Point p, int x0, int y0, int z0, int[] d) {
        return new int[]{Math.clamp(p.blockX() - x0, 0, d[0] - 1), Math.clamp(p.blockY() - y0, 0, d[1] - 1), Math.clamp(p.blockZ() - z0, 0, d[2] - 1)};
    }

    private void delete(CommandSender s, String name) {
        if (!mod.templates.containsKey(name)) { err(s, "Нет чертежа «" + name + "»"); return; }
        long used = mod.ships.values().stream().filter(x -> x.template.equals(name)).count();
        if (used > 0) { err(s, "На чертеже " + used + " корабл(ей) игроков — пусть уберут (/hoship dismiss)"); return; }
        try { Files.deleteIfExists(mod.templatesDir().resolve(name + ".json")); } catch (Exception e) { err(s, e.getMessage()); return; }
        mod.templates.remove(name);
        mod.modelRemoved(name);
        ok(s, "Чертёж «" + name + "» удалён");
    }

    // ───────────────────────────── сообщения ─────────────────────────────

    private static void deny(CommandSender s) { err(s, "Только для администраторов"); }
    static void ok(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.GREEN)); }
    static void info(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.AQUA)); }
    static void err(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.RED)); }

    /** /hoship body — масса, центр масс, инерция и двигатели корабля, которым управляешь (этап 0 физики). */
    private void body(Player p) {
        Flight f = mod.flightOf(p);
        if (f == null) { err(p, "Сядьте за штурвал своего корабля (/hoship fly)"); return; }
        var b = f.body(new ShipPhysics.Config(mod.config()));
        double[] c = b.com(), I = b.inertia();
        p.sendMessage(net.kyori.adventure.text.Component.text(String.format(java.util.Locale.ROOT,
                "Корабль: блоков с массой %d, масса %.1f т (в динамике %.1f т)%nЦентр масс от сиденья: x %.2f, y %.2f, z %.2f%n"
                        + "Инерция Ixx %.0f, Iyy %.0f, Izz %.0f%nДвигателей %d: тяга вперёд %.0f, назад %.0f, вверх %.0f, вниз %.0f (т·б/с²)",
                b.blocks(), b.mass(), b.massEff(), c[0], c[1], c[2], I[0], I[1], I[2], b.engines().size(),
                b.thrustForward(), b.thrustBack(), b.thrustUp(), b.thrustDown()), net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    /** Где админ был до /hoship yard tp. */
    private final java.util.Map<java.util.UUID, net.minestom.server.coordinate.Pos> beforeYard = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Верфь, этап 2 (админ): build — перенести блоки своего корабля в плот верфи (настоящие блоки), tp — встать на
     * корабль в верфи и проверить, что всё работает как в обычном мире, back — вернуться.
     */
    private void yard(Player p, String what) {
        if (!isOp(p)) { err(p, "Только для админа (проверка верфи)"); return; }
        if (what.equals("back")) {
            var to = beforeYard.remove(p.getUuid());
            if (to == null) { err(p, "Вы не в верфи"); return; }
            p.teleport(to);
            return;
        }
        Ship s = mod.ships.get(p.getUuid().toString());
        if (s == null || s.t == null) { err(p, "У вас нет корабля (/hoship summon)"); return; }
        var inst = mod.instanceOf(s);
        if (inst == null) { err(p, "Карта корабля не загружена"); return; }
        if (what.equals("turn")) {                                              // нос плота не туда — развернуть на 180°
            if (s.yard == null) { err(p, "Корабль ещё не в верфи"); return; }
            s.yardTurn = (s.yardTurn + 180) % 360;
            if (s.state == Ship.State.MODEL) mod.summonYard(p, s);
            mod.saveShips();
            info(p, "Корабль развёрнут: нос плота " + Math.round(s.yardTurn) + "°");
            return;
        }
        if (what.equals("build")) {
            if (s.state != Ship.State.MODEL || s.mparts == null) { err(p, "Сначала поставьте корабль моделью: взлетите (/hoship fly) и нажмите F"); return; }
            Shipyard.build(mod, s, inst).thenAccept(n -> {
                mod.saveShips();
                var a = mod.parked.get(s.owner);
                if (a != null) mod.client.attachModel(s, a);                     // моду — где плот (ходьба по настоящим блокам)
                info(p, "Корабль перенесён в верфь: плот " + s.yard[0] + ", блоков " + n + ". /hoship yard tp — встать на него");
            }).exceptionally(e -> { err(p, "Не вышло: " + e); return null; });
            return;
        }
        if (s.yard == null) { err(p, "Корабль ещё не в верфи: /hoship yard build"); return; }
        if (p.getInstance() != inst) { err(p, "Корабль на другой карте: " + s.map); return; }
        beforeYard.putIfAbsent(p.getUuid(), p.getPosition());
        p.teleport(Shipyard.seat(s));
        info(p, "Вы на корабле в верфи. Это обычные блоки мира — двери, сундуки, ступени, ломание. /hoship yard back — назад");
    }
}
