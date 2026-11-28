package ink.hoka.odyssey.vessels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.entity.Player;

import java.util.Arrays;
import java.util.List;

/**
 * /hovessel camera … (OP) — камеры кораблей (vessels-5). Область — [вид|id]: слово — вид, число — корабль; не указана —
 * вид корабля, в котором вы пилот.
 * <pre>
 * edit [first | f5 &lt;n&gt;] [kind | ship]   — редактор в игре (пилот): камера летает свободно, корабль стоит
 * list [вид|id]                          — камеры (без области — корабля в деле: свои и от вида)
 * remove &lt;first | f5 n&gt; [вид|id]          — убрать камеру
 * reset [вид|id]                         — всё по умолчанию
 * copy &lt;id&gt; &lt;вид&gt;                        — камеры корабля (в деле) — виду
 * move &lt;n&gt; &lt;новый n&gt; [вид|id]            — переставить вид F5
 * set &lt;first | f5 n&gt; &lt;x y z yaw pitch&gt; [fov] [вид|id] — задать числами (одно число после углов — FOV;
 *                                          для корабля без FOV — fov 0)
 * </pre>
 */
final class CameraCommand {

    private final VesselsModule mod;

    CameraCommand(VesselsModule mod) { this.mod = mod; }

    static final String HELP = "/hovessel camera edit [first|f5 <n>] [kind|ship] — редактор (вы пилот)  |  list [вид|id]  |  "
            + "remove <first|f5 n> [вид|id]  |  reset [вид|id]  |  copy <id> <вид>  |  move <n> <новый n> [вид|id]  |  "
            + "set <first|f5 n> <x y z yaw pitch> [fov] [вид|id]. Область: слово — вид, число — корабль; без неё — вид вашего корабля.";

    private static void ok(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.GREEN)); }

    private static void err(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.RED)); }

    private static void info(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.YELLOW)); }

    void run(CommandSender s, String[] a) {
        if (a.length == 0) { info(s, HELP); return; }
        try {
            switch (a[0].toLowerCase(java.util.Locale.ROOT)) {
                case "edit" -> edit(s, Arrays.copyOfRange(a, 1, a.length));
                case "list" -> list(s, a.length > 1 ? a[1] : null);
                case "remove" -> remove(s, Arrays.copyOfRange(a, 1, a.length));
                case "reset" -> reset(s, a.length > 1 ? a[1] : null);
                case "copy" -> copy(s, a);
                case "move" -> move(s, a);
                case "set" -> set(s, Arrays.copyOfRange(a, 1, a.length));
                default -> info(s, HELP);
            }
        } catch (IllegalArgumentException e) {
            err(s, e.getMessage());
        }
    }

    /** Подсказки к аргументам (по месту). */
    List<String> suggest(String[] a) {
        if (a.length <= 1) return List.of("edit", "list", "remove", "reset", "copy", "move", "set");
        return switch (a[0]) {
            case "edit" -> a.length == 2 ? List.of("first", "f5") : List.of("kind", "ship");
            case "remove", "set" -> a.length == 2 ? List.of("first", "f5") : List.copyOf(mod.kinds().keySet());
            default -> List.copyOf(mod.kinds().keySet());
        };
    }

    // ───────────────────────── область и номер ─────────────────────────

    /** Область по слову (вид) или числу (корабль); null — вид корабля пилота. */
    CameraBook.Scope scope(CommandSender s, String t) {
        if (t == null) {
            Piloting pl = s instanceof Player p ? mod.pilotOf(p) : null;
            if (pl == null) throw new IllegalArgumentException("Укажите вид или id корабля (или сядьте пилотом)");
            return CameraBook.Scope.kind(pl.vessel.kind);
        }
        if (t.matches("-?\\d+")) {
            int id = Integer.parseInt(t);
            if (mod.vessel(id) == null) throw new IllegalArgumentException("Нет корабля #" + id);
            return CameraBook.Scope.ship(id);
        }
        if (!mod.kinds().containsKey(t)) throw new IllegalArgumentException("Нет вида «" + t + "». Есть: " + String.join(", ", mod.kinds().keySet()));
        return CameraBook.Scope.kind(t);
    }

    /** «first» → 0, «f5 n» → n; сколько слов занято — в used[0]. */
    static int which(String[] a, int from, int[] used) {
        if (from >= a.length) throw new IllegalArgumentException("Укажите first или f5 <n>");
        String w = a[from].toLowerCase(java.util.Locale.ROOT);
        if (w.equals("first")) { used[0] = 1; return 0; }
        if (w.equals("f5")) {
            if (from + 1 >= a.length || !a[from + 1].matches("\\d+")) throw new IllegalArgumentException("После f5 — номер вида (1…" + CameraBook.MAX_F5 + ")");
            int n = Integer.parseInt(a[from + 1]);
            if (n < 1 || n > CameraBook.MAX_F5) throw new IllegalArgumentException("Номер вида F5 — 1…" + CameraBook.MAX_F5);
            used[0] = 2;
            return n;
        }
        throw new IllegalArgumentException("Укажите first или f5 <n>, а не «" + a[from] + "»");
    }

    static String name(int which) { return which <= 0 ? "первое лицо" : "вид F5 " + which; }

    private void apply(CommandSender s, CameraBook next, String done) {
        if (!mod.cameras.change(next)) { err(s, "cameras.json битый — камеры не сохраняются (см. лог)"); return; }
        ok(s, done);
    }

    // ───────────────────────── команды ─────────────────────────

    private void edit(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        int which = 0, i = 0;
        if (a.length > 0 && !a[0].equalsIgnoreCase("kind") && !a[0].equalsIgnoreCase("ship")) {
            int[] used = new int[1];
            which = which(a, 0, used);
            i = used[0];
        }
        boolean ship = false;
        if (i < a.length) {
            if (a[i].equalsIgnoreCase("ship")) ship = true;
            else if (!a[i].equalsIgnoreCase("kind")) throw new IllegalArgumentException("В конце — kind (для всего вида) или ship (только этот корабль)");
        }
        String why = mod.cameras.startEdit(p, which, ship);
        if (why != null) err(s, why);
        else info(s, "Редактор камер: мышь — взгляд, WASD — лететь, пробел/C — вверх/вниз, Shift — медленно, Ctrl — быстро, "
                + "колёсико — скорость, Alt+колёсико — FOV, Ctrl+колёсико или 1–6 — другая камера, [ ] — плавность F5, V — смотреть на корабль, "
                + "ЛКМ/Enter — сохранить, R — сброс, ПКМ/Esc — выйти");
    }

    private void list(CommandSender s, String t) {
        if (t == null && s instanceof Player p && mod.pilotOf(p) != null) {
            Vessel v = mod.pilotOf(p).vessel;
            CameraBook b = mod.cameras.book();
            CameraBook.Set own = b.ship(v.id), kind = b.kind(v.kind);
            info(s, "Камеры корабля #" + v.id + " (" + v.kind + ") в деле:");
            if (own.first() != null) s.sendMessage(line("первое лицо [корабль]", own.first()));
            else if (kind.first() != null) s.sendMessage(line("первое лицо [вид]", kind.first()));
            else s.sendMessage(Component.text(" первое лицо — по умолчанию (место пилота)", NamedTextColor.GRAY));
            List<CameraBook.Cam> f5 = !own.f5().isEmpty() ? own.f5() : kind.f5();
            String src = !own.f5().isEmpty() ? "корабль" : "вид";
            if (f5.isEmpty()) s.sendMessage(Component.text(" F5 — по умолчанию (сзади)", NamedTextColor.GRAY));
            for (int i = 0; i < f5.size(); i++) s.sendMessage(line("F5 " + (i + 1) + " [" + src + "]", f5.get(i)));
            return;
        }
        CameraBook.Scope sc = scope(s, t);
        CameraBook.Set set = mod.cameras.book().get(sc);
        info(s, "Камеры — " + sc.title() + (set.isEmpty() ? ": своих нет (по умолчанию" + (sc.ship() ? " — как у вида)" : ")") : ":"));
        if (set.first() != null) s.sendMessage(line("первое лицо", set.first()));
        for (int i = 0; i < set.f5().size(); i++) s.sendMessage(line("F5 " + (i + 1), set.f5().get(i)));
    }

    private static Component line(String what, CameraBook.Cam c) {
        return Component.text(" " + what + ": ", NamedTextColor.WHITE).append(Component.text(c.brief(), NamedTextColor.GRAY));
    }

    private void remove(CommandSender s, String[] a) {
        int[] used = new int[1];
        int which = which(a, 0, used);
        CameraBook.Scope sc = scope(s, used[0] < a.length ? a[used[0]] : null);
        CameraBook b = mod.cameras.book();
        if (which == 0) {
            if (b.get(sc).first() == null) throw new IllegalArgumentException("У области " + sc.title() + " нет своей камеры первого лица");
            apply(s, b.withFirst(sc, null), "Убрано: первое лицо — " + sc.title());
        } else apply(s, b.withoutF5(sc, which), "Убран вид F5 " + which + " — " + sc.title() + " (следующие сдвинулись)");
    }

    private void reset(CommandSender s, String t) {
        CameraBook.Scope sc = scope(s, t);
        apply(s, mod.cameras.book().reset(sc), "Камеры — " + sc.title() + ": по умолчанию");
    }

    private void copy(CommandSender s, String[] a) {
        if (a.length < 3 || !a[1].matches("\\d+")) throw new IllegalArgumentException("/hovessel camera copy <id корабля> <вид>");
        int id = Integer.parseInt(a[1]);
        Vessel v = mod.vessel(id);
        if (v == null) throw new IllegalArgumentException("Нет корабля #" + id);
        if (!mod.kinds().containsKey(a[2])) throw new IllegalArgumentException("Нет вида «" + a[2] + "»");
        CameraBook.Set eff = mod.cameras.book().effective(v.kind, id);
        if (eff.isEmpty()) throw new IllegalArgumentException("У корабля #" + id + " камеры по умолчанию — копировать нечего");
        apply(s, mod.cameras.book().copyToKind(id, v.kind, a[2]), "Камеры корабля #" + id + " → вид " + a[2]
                + " (" + (eff.first() != null ? "первое лицо, " : "") + "видов F5: " + eff.f5().size() + ")");
    }

    private void move(CommandSender s, String[] a) {
        if (a.length < 3 || !a[1].matches("\\d+") || !a[2].matches("\\d+")) throw new IllegalArgumentException("/hovessel camera move <n> <новый n> [вид|id]");
        CameraBook.Scope sc = scope(s, a.length > 3 ? a[3] : null);
        int from = Integer.parseInt(a[1]), to = Integer.parseInt(a[2]);
        apply(s, mod.cameras.book().moveF5(sc, from, to), "Вид F5 " + from + " → " + to + " — " + sc.title());
    }

    private void set(CommandSender s, String[] a) {
        int[] used = new int[1];
        int which = which(a, 0, used);
        int i = used[0];
        if (a.length < i + 5) throw new IllegalArgumentException("/hovessel camera set <first|f5 n> <x y z yaw pitch> [fov] [вид|id]");
        double[] n = new double[5];
        for (int k = 0; k < 5; k++) n[k] = num(a[i + k]);
        i += 5;
        double fov = 0;
        String target = null;
        if (i < a.length) {
            if (a.length - i >= 2) { fov = num(a[i]); target = a[i + 1]; }
            else if (a[i].matches("-?[\\d.]+")) fov = num(a[i]);
            else target = a[i];
        }
        CameraBook.Scope sc = scope(s, target);
        CameraBook b = mod.cameras.book();
        CameraBook.Cam old = which == 0 ? b.get(sc).first() : which <= b.get(sc).f5().size() ? b.get(sc).f5().get(which - 1) : null;
        CameraBook.Cam c = new CameraBook.Cam(n[0], n[1], n[2], n[3], n[4], fov, null, old != null ? old.smooth() : Double.NaN);
        apply(s, which == 0 ? b.withFirst(sc, c) : b.withF5(sc, which, c), "Камера «" + name(which) + "» — " + sc.title() + ": " + c.brief());
    }

    private static double num(String t) {
        try {
            double d = Double.parseDouble(t.replace(',', '.'));
            if (!Double.isFinite(d)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Не число: «" + t + "»");
        }
    }
}
