package ink.hoka.odyssey.survival;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;
import net.minestom.server.timer.Task;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /hopvp (только OP) — PvP-зоны: pos1/pos2 (у ног или «target» — блок под прицелом), create &lt;имя&gt;, remove &lt;имя&gt;,
 * list, show [имя] (контур частицами ~10 с), here (в зоне ли я), default &lt;on|off|unset&gt; (карта вне зон).
 */
final class PvpCommand extends Command {

    private final SurvivalModule mod;
    /** Выбранные углы игроков (до create). */
    private final Map<UUID, int[]> pos1 = new ConcurrentHashMap<>(), pos2 = new ConcurrentHashMap<>();
    /** Показ контура у игрока (новый показ — старый прочь). */
    private final Map<UUID, Task> shows = new ConcurrentHashMap<>();
    /** На какой карте ставились углы (create на другой карте — отказ). */
    private final Map<UUID, String> posMap = new ConcurrentHashMap<>();

    /** Игрок вышел — его углы прочь. */
    void forget(UUID id) {
        pos1.remove(id);
        pos2.remove(id);
        posMap.remove(id);
        Task t = shows.remove(id);
        if (t != null) t.cancel();
    }

    PvpCommand(SurvivalModule mod) {
        super("hopvp", "pvpzone");
        this.mod = mod;
        setCondition((s, c) -> !(s instanceof Player p) || mod.server().isOp(p));
        var name = ArgumentType.Word("имя").setSuggestionCallback((s, c, sug) -> {
            for (String n : mod.zones().names()) sug.addEntry(new SuggestionEntry(n));
        });
        var value = ArgumentType.Word("значение").from("on", "off", "unset");
        setDefaultExecutor((s, c) -> help(s));
        addSyntax((s, c) -> pos(s, 1, false), ArgumentType.Literal("pos1"));
        addSyntax((s, c) -> pos(s, 2, false), ArgumentType.Literal("pos2"));
        addSyntax((s, c) -> pos(s, 1, true), ArgumentType.Literal("pos1"), ArgumentType.Literal("target"));
        addSyntax((s, c) -> pos(s, 2, true), ArgumentType.Literal("pos2"), ArgumentType.Literal("target"));
        addSyntax((s, c) -> create(s, c.get(name)), ArgumentType.Literal("create"), name);
        addSyntax((s, c) -> remove(s, c.get(name)), ArgumentType.Literal("remove"), name);
        addSyntax((s, c) -> list(s), ArgumentType.Literal("list"));
        addSyntax((s, c) -> show(s, null), ArgumentType.Literal("show"));
        addSyntax((s, c) -> show(s, c.get(name)), ArgumentType.Literal("show"), name);
        addSyntax((s, c) -> here(s), ArgumentType.Literal("here"));
        addSyntax((s, c) -> setDefault(s, c.get(value)), ArgumentType.Literal("default"), value);
    }

    private static void help(CommandSender s) {
        s.sendMessage(Component.text("""
                /hopvp pos1|pos2 [target] — угол зоны: у ног (или блок под прицелом)
                /hopvp create <имя> — зона из pos1–pos2 на этой карте
                /hopvp remove <имя> · /hopvp list · /hopvp show [имя] · /hopvp here
                /hopvp default <on|off|unset> — PvP на этой карте вне зон""", NamedTextColor.YELLOW));
    }

    private static void ok(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.GREEN)); }

    private static void err(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.RED)); }

    private String map(Player p) { return p.getInstance() == null ? null : mod.server().mapName(p.getInstance()).orElse(null); }

    private void pos(CommandSender s, int which, boolean target) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        int[] at;
        if (target) {
            Point b = p.getTargetBlockPosition(120);
            if (b == null) { err(s, "Под прицелом нет блока (до 120 блоков)"); return; }
            at = new int[]{b.blockX(), b.blockY(), b.blockZ()};
        } else {
            var pp = p.getPosition();
            at = new int[]{pp.blockX(), pp.blockY(), pp.blockZ()};
        }
        (which == 1 ? pos1 : pos2).put(p.getUuid(), at);
        posMap.put(p.getUuid(), String.valueOf(map(p)));
        ok(s, "Точка " + which + ": " + at[0] + " " + at[1] + " " + at[2]);
    }

    private void create(CommandSender s, String name) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        String map = map(p);
        if (map == null) { err(s, "Этот мир — не карта из maps/"); return; }
        if (!ZoneBook.validName(name)) { err(s, "Имя: буквы, цифры, _ и -, до 32 знаков"); return; }
        int[] a = pos1.get(p.getUuid()), b = pos2.get(p.getUuid());
        if (a == null || b == null) { err(s, "Сначала /hopvp pos1 и /hopvp pos2"); return; }
        if (!map.equals(posMap.get(p.getUuid()))) { err(s, "Углы ставились на другой карте — поставьте pos1 и pos2 здесь"); return; }
        ZoneBook.Zone was = mod.zones().zone(name);
        ZoneBook.Zone z = ZoneBook.Zone.of(was != null ? was.name() : name, map, a[0], a[1], a[2], b[0], b[1], b[2]);
        if (!mod.editZones(mod.zones().with(z))) { err(s, "pvp-zones.json битый — зоны не сохраняются (см. лог)"); return; }
        ok(s, (was != null ? "Зона «" + z.name() + "» заменена: " : "PvP-зона «" + z.name() + "» создана: ") + map + " " + z.box()
                + " (" + z.volume() + " блоков)");
        show(s, z.name());
    }

    private void remove(CommandSender s, String name) {
        ZoneBook.Zone z = mod.zones().zone(name);
        if (z == null) { err(s, "Нет зоны «" + name + "»"); return; }
        if (!mod.editZones(mod.zones().without(z.name()))) { err(s, "pvp-zones.json битый — зоны не сохраняются (см. лог)"); return; }
        ok(s, "Зона «" + z.name() + "» удалена");
    }

    private void list(CommandSender s) {
        ZoneBook b = mod.zones();
        s.sendMessage(Component.text("PvP игроков: " + mod.playerMode() + " (config.yml pvp-mode). Зон: " + b.zones().size(), NamedTextColor.YELLOW));
        for (ZoneBook.Zone z : b.zones())
            s.sendMessage(Component.text(" " + z.name() + " — " + z.map() + " " + z.box(), NamedTextColor.WHITE));
        for (var e : b.defaults().entrySet())
            s.sendMessage(Component.text(" карта " + e.getKey() + " вне зон: " + (e.getValue() ? "PvP" : "без PvP"), NamedTextColor.GRAY));
    }

    private void here(CommandSender s) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        var pp = p.getPosition();
        String map = map(p);
        ZoneBook b = mod.zones();
        ZoneBook.Zone z = b.zoneAt(map, pp.x(), pp.y(), pp.z());
        boolean pvp = b.isPvp(map, pp.x(), pp.y(), pp.z());
        String where = z != null ? "в PvP-зоне «" + z.name() + "»" : "вне зон (карта " + map + ": " + (pvp ? "PvP" : "без PvP") + ")";
        s.sendMessage(Component.text("Вы " + where + ". Бой игроков: " + mod.playerMode(), pvp ? NamedTextColor.GOLD : NamedTextColor.AQUA));
    }

    private void setDefault(CommandSender s, String v) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        String map = map(p);
        if (map == null) { err(s, "Этот мир — не карта из maps/"); return; }
        Boolean val = v.equals("on") ? Boolean.TRUE : v.equals("off") ? Boolean.FALSE : null;
        if (!mod.editZones(mod.zones().withDefault(map, val))) { err(s, "pvp-zones.json битый — зоны не сохраняются (см. лог)"); return; }
        ok(s, "Карта " + map + " вне зон: " + (val == null ? "по умолчанию (без PvP)" : val ? "PvP" : "без PvP"));
    }

    /** Контур зоны (или ближайшей на этой карте) частицами ~10 с — только этому игроку. */
    private void show(CommandSender s, String name) {
        if (!(s instanceof Player p)) { err(s, "только в игре"); return; }
        ZoneBook.Zone z;
        if (name != null) {
            z = mod.zones().zone(name);
            if (z == null) { err(s, "Нет зоны «" + name + "»"); return; }
        } else {
            String map = map(p);
            var pp = p.getPosition();
            z = null;
            double best = Double.MAX_VALUE;
            for (ZoneBook.Zone o : mod.zones().zones(map)) {
                double dx = Math.max(0, Math.max(o.x0() - pp.x(), pp.x() - o.x1() - 1)), dy = Math.max(0, Math.max(o.y0() - pp.y(), pp.y() - o.y1() - 1));
                double dz = Math.max(0, Math.max(o.z0() - pp.z(), pp.z() - o.z1() - 1)), d = dx * dx + dy * dy + dz * dz;
                if (d < best) { best = d; z = o; }
            }
            if (z == null) { err(s, "На этой карте нет PvP-зон"); return; }
        }
        if (!z.map().equals(map(p))) { err(s, "Зона «" + z.name() + "» — на карте " + z.map()); return; }
        ZoneBook.Zone zone = z;
        Task old = shows.remove(p.getUuid());
        if (old != null) old.cancel();
        int[] left = {20};
        Task[] t = new Task[1];
        t[0] = mod.scheduler().repeat(() -> {
            if (!p.isOnline() || left[0]-- <= 0) { if (t[0] != null) { t[0].cancel(); shows.remove(p.getUuid(), t[0]); } return; }
            outline(p, zone);
        }, 1, 10);
        shows.put(p.getUuid(), t[0]);
        ok(s, "Контур зоны «" + z.name() + "» — 10 с");
    }

    /** 12 рёбер коробки, не больше ~600 частиц за раз. */
    private static void outline(Player p, ZoneBook.Zone z) {
        double x0 = z.x0(), y0 = z.y0(), z0 = z.z0(), x1 = z.x1() + 1, y1 = z.y1() + 1, z1 = z.z1() + 1;
        double total = 4 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        double step = Math.max(0.5, total / 600);
        double[][] edges = {
                {x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        for (double[] e : edges) {
            double len = Math.abs(e[3] - e[0]) + Math.abs(e[4] - e[1]) + Math.abs(e[5] - e[2]);
            int n = Math.max(1, (int) Math.ceil(len / step));
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                p.sendPacket(new ParticlePacket(Particle.FLAME, true, true, e[0] + (e[3] - e[0]) * t, e[1] + (e[4] - e[1]) * t,
                        e[2] + (e[5] - e[2]) * t, 0f, 0f, 0f, 0f, 1));
            }
        }
    }

    /** Модуль выключают — показы прочь. */
    void stop() {
        for (Task t : shows.values()) t.cancel();
        shows.clear();
    }
}
