package ink.hoka.odyssey.vessels;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;

/**
 * /hovessel (то же — /vessel), только OP: spawn &lt;вид&gt; — поставить перед собой (владелец — кто поставил), remove [id]
 * — убрать (по id, на который смотришь или ближайший; груз выпадает), list, tp &lt;id&gt;, reload — перечитать виды
 * из config.yml, owner &lt;id&gt; &lt;ник&gt;, fuel [кол-во] [ник], upgrade &lt;вид&gt; [ник], give &lt;repair|crystal&gt; [кол-во] [ник],
 * repair &lt;id&gt; [hp], hp &lt;id&gt; &lt;hp&gt; (0 — разрушить).
 */
final class HoVesselCommand extends Command {

    private final VesselsModule mod;

    HoVesselCommand(VesselsModule mod) {
        super("hovessel", "vessel");
        this.mod = mod;
        setCondition((s, c) -> !(s instanceof Player p) || mod.server().isOp(p));
        setDefaultExecutor((s, c) -> help(s));

        var kind = ArgumentType.Word("вид").setSuggestionCallback((sender, ctx, sug) -> {
            for (String k : mod.kinds().keySet()) sug.addEntry(new SuggestionEntry(k));
        });
        var id = ArgumentType.Integer("id");

        addSyntax((s, c) -> { if (s instanceof Player p) spawn(p, c.get(kind)); else err(s, "только в игре"); }, ArgumentType.Literal("spawn"), kind);
        addSyntax((s, c) -> { if (s instanceof Player p) removeLooked(p); else err(s, "только в игре (или remove <id>)"); }, ArgumentType.Literal("remove"));
        addSyntax((s, c) -> removeId(s, c.get(id)), ArgumentType.Literal("remove"), id);
        addSyntax((s, c) -> list(s), ArgumentType.Literal("list"));
        addSyntax((s, c) -> { if (s instanceof Player p) tp(p, c.get(id)); else err(s, "только в игре"); }, ArgumentType.Literal("tp"), id);
        addSyntax((s, c) -> reload(s), ArgumentType.Literal("reload"));

        var nick = ArgumentType.Word("ник").setSuggestionCallback((sender, ctx, sug) -> {
            for (Player p : net.minestom.server.MinecraftServer.getConnectionManager().getOnlinePlayers()) sug.addEntry(new SuggestionEntry(p.getUsername()));
        });
        var count = ArgumentType.Integer("кол-во").between(1, 64 * 36);
        var up = ArgumentType.Word("улучшение").from("engine", "tank", "gun", "warp");
        addSyntax((s, c) -> owner(s, c.get(id), c.get(nick)), ArgumentType.Literal("owner"), id, nick);
        addSyntax((s, c) -> giveFuel(s, 64, null), ArgumentType.Literal("fuel"));
        addSyntax((s, c) -> giveFuel(s, c.get(count), null), ArgumentType.Literal("fuel"), count);
        addSyntax((s, c) -> giveFuel(s, c.get(count), c.get(nick)), ArgumentType.Literal("fuel"), count, nick);
        addSyntax((s, c) -> giveUpgrade(s, c.get(up), null), ArgumentType.Literal("upgrade"), up);
        addSyntax((s, c) -> giveUpgrade(s, c.get(up), c.get(nick)), ArgumentType.Literal("upgrade"), up, nick);

        var hp = ArgumentType.Double("прочность").min(0.0);
        addSyntax((s, c) -> repair(s, c.get(id), -1), ArgumentType.Literal("repair"), id);
        addSyntax((s, c) -> repair(s, c.get(id), c.get(hp)), ArgumentType.Literal("repair"), id, hp);
        addSyntax((s, c) -> setHp(s, c.get(id), c.get(hp)), ArgumentType.Literal("hp"), id, hp);
        var item = ArgumentType.Word("предмет").from("repair", "crystal");
        addSyntax((s, c) -> giveItem(s, c.get(item), 1, null), ArgumentType.Literal("give"), item);
        addSyntax((s, c) -> giveItem(s, c.get(item), c.get(count), null), ArgumentType.Literal("give"), item, count);
        addSyntax((s, c) -> giveItem(s, c.get(item), c.get(count), c.get(nick)), ArgumentType.Literal("give"), item, count, nick);
    }

    private void help(CommandSender s) {
        info(s, "/hovessel (/vessel) spawn <вид> — поставить перед собой  |  remove [id] — убрать (на который смотрите / ближайший)  |  "
                + "list  |  tp <id>  |  reload — перечитать виды  |  owner <id> <ник>  |  fuel [кол-во] [ник]  |  "
                + "upgrade <engine|tank|gun|warp> [ник]  |  give <repair|crystal> [кол-во] [ник]  |  repair <id> [hp]  |  hp <id> <hp>. "
                + "Сесть — ПКМ по кораблю, выйти — F, меню — G.");
    }

    private void spawn(Player p, String kindId) {
        VesselKind k = mod.kinds().get(kindId);
        if (k == null) { err(p, "Нет вида «" + kindId + "». Есть: " + String.join(", ", mod.kinds().keySet())); return; }
        Pos pp = p.getPosition();
        Quat rot = Quat.yaw(pp.yaw());                                          // нос — куда смотрит игрок
        double yaw = Math.toRadians(pp.yaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double ahead = k.half[2] + 2;                                           // корма — в 2 блоках перед игроком
        Vessel v = mod.create(kindId, p.getInstance(), pp.x() + fx * ahead, pp.y() + k.half[1], pp.z() + fz * ahead, rot, p);
        if (v == null) { err(p, "Этот мир не из maps/ — здесь корабли не сохраняются"); return; }
        ok(p, "Корабль " + v.id + " (" + kindId + ") поставлен, владелец — вы. ПКМ по нему — сесть, G рядом — меню.");
        if (mod.storeBroken()) err(p, "vessels.json битый — корабль НЕ сохранится после рестарта (см. лог)");
    }

    /** На какой корабль смотрит игрок (луч до 64 блоков), иначе ближайший в 32 блоках. */
    private void removeLooked(Player p) {
        Vessel best = null;
        double bestT = Double.MAX_VALUE;
        Pos pp = p.getPosition();
        Vec dir = pp.direction();
        double[] o = {pp.x(), pp.y() + p.getEyeHeight(), pp.z()}, d = {dir.x(), dir.y(), dir.z()};
        for (Vessel v : mod.vessels()) {
            VesselKind k = mod.kinds().get(v.kind);
            if (k == null || mod.instanceOf(v) != p.getInstance()) continue;
            double t = VesselMath.rayBox(o, d, 64, v.pos(), v.rot, k.half);
            if (t >= 0 && t < bestT) { bestT = t; best = v; }
        }
        if (best == null) {
            for (Vessel v : mod.vessels()) {
                if (mod.instanceOf(v) != p.getInstance()) continue;
                double dd = Math.sqrt(Math.pow(v.x - o[0], 2) + Math.pow(v.y - o[1], 2) + Math.pow(v.z - o[2], 2));
                if (dd <= 32 && dd < bestT) { bestT = dd; best = v; }
            }
        }
        if (best == null) { err(p, "Рядом нет кораблей (или /hovessel remove <id>)"); return; }
        boolean cargo = !best.cargo.isEmpty();
        mod.remove(best);
        ok(p, "Корабль " + best.id + " убран" + (cargo ? " (груз выпал рядом)" : ""));
    }

    private void removeId(CommandSender s, int id) {
        Vessel v = mod.vessel(id);
        if (v == null) { err(s, "Нет корабля " + id); return; }
        boolean cargo = !v.cargo.isEmpty();
        mod.remove(v);
        ok(s, "Корабль " + id + " убран" + (cargo ? " (груз выпал рядом с ним или записан в лог)" : ""));
    }

    private void list(CommandSender s) {
        if (mod.vessels().isEmpty()) { info(s, "Кораблей нет. Виды: " + String.join(", ", mod.kinds().keySet())); return; }
        for (Vessel v : mod.vessels()) {
            String state = !mod.kinds().containsKey(v.kind) ? " — вида нет в config.yml"
                    : v.pilot != null ? " — пилот " + v.pilot.player.getUsername() : "";
            info(s, "#" + v.id + " " + v.kind + " — " + v.map + " " + Math.round(v.x) + " " + Math.round(v.y) + " " + Math.round(v.z) + state);
        }
    }

    private void tp(Player p, int id) {
        Vessel v = mod.vessel(id);
        VesselKind k = v == null ? null : mod.kinds().get(v.kind);
        if (v == null) { err(p, "Нет корабля " + id); return; }
        Instance inst = mod.instanceOf(v);
        if (inst == null) { err(p, "Карты «" + v.map + "» нет"); return; }
        double up = k != null ? k.radius() + 1 : 3;
        Pos to = new Pos(v.x, v.y + up, v.z, (float) v.rot.yawDeg(), 30f);
        mod.server().transfer(p, inst, to);
        ok(p, "Корабль " + id);
    }

    private void reload(CommandSender s) {
        mod.config().reload();
        mod.loadKinds();
        mod.client.kindsToAll();
        ok(s, "Видов: " + mod.kinds().size() + " (новые размеры и места пилота — сразу; носители кораблей — как были)");
    }

    /** Найти игрока в сети по нику; null — нет (и сообщение). */
    private static Player online(CommandSender s, String nick) {
        Player p = net.minestom.server.MinecraftServer.getConnectionManager().findOnlinePlayer(nick);
        if (p == null) err(s, "Игрок «" + nick + "» не в сети");
        return p;
    }

    private void owner(CommandSender s, int id, String nick) {
        Vessel v = mod.vessel(id);
        if (v == null) { err(s, "Нет корабля " + id); return; }
        Player p = online(s, nick);
        if (p == null) return;
        v.owner = p.getUuid().toString();
        v.ownerName = p.getUsername();
        if (v.menu != null) { v.menu.closeAll(); v.menu = null; }               // заголовок окна — с новым владельцем
        mod.save();
        ok(s, "Корабль " + id + ": владелец — " + p.getUsername());
    }

    /** Кому выдать: ник или сам (игрок). */
    private static Player target(CommandSender s, String nick) {
        if (nick != null) return online(s, nick);
        if (s instanceof Player p) return p;
        err(s, "Укажите ник игрока");
        return null;
    }

    private void giveFuel(CommandSender s, int n, String nick) {
        Player p = target(s, nick);
        if (p == null) return;
        int left = n, stack = mod.items.fuelStack();
        while (left > 0) {
            int c = Math.min(stack, left);
            ItemStack it = mod.items.fuel(c);
            if (!p.getInventory().addItemStack(it)) p.dropItem(it);
            left -= c;
        }
        ok(s, "Топливо ×" + n + " → " + p.getUsername());
    }

    private void giveUpgrade(CommandSender s, String kind, String nick) {
        Upgrades.Type t = Upgrades.Type.of(kind);
        if (t == null) { err(s, "Улучшения: engine (Двигатель+), tank (Бак+), gun (Орудие+), warp (Варп-катушка)"); return; }
        Player p = target(s, nick);
        if (p == null) return;
        ItemStack it = mod.items.upgrade(t);
        if (!p.getInventory().addItemStack(it)) p.dropItem(it);
        ok(s, t.title + " → " + p.getUsername());
    }

    /** Починить: на hp (−1 — полностью). */
    private void repair(CommandSender s, int id, double hp) {
        Vessel v = mod.vessel(id);
        if (v == null) { err(s, "Нет корабля " + id); return; }
        double max = mod.hull.maxHp(v);
        double add = mod.hull.repair(v, hp < 0 ? max : hp);
        ok(s, "Корабль " + id + ": +" + Math.round(add) + ", прочность " + Math.round(v.hp(max)) + " / " + Math.round(max));
    }

    /** Задать прочность (0 — корабль разрушается, как от урона). */
    private void setHp(CommandSender s, int id, double hp) {
        Vessel v = mod.vessel(id);
        if (v == null) { err(s, "Нет корабля " + id); return; }
        double max = mod.hull.maxHp(v);
        if (hp <= 0) {
            mod.hull.destroy(v, null, "команда");
            ok(s, "Корабль " + id + " разрушен (груз выпал на месте)");
            return;
        }
        v.setHp(hp, max);
        mod.save();
        mod.client.hull(v, false, -1);
        if (v.menu != null) v.menu.info();
        ok(s, "Корабль " + id + ": прочность " + Math.round(v.hp(max)) + " / " + Math.round(max));
    }

    private void giveItem(CommandSender s, String what, int n, String nick) {
        Player p = target(s, nick);
        if (p == null) return;
        int left = n;
        while (left > 0) {
            ItemStack it = mod.items.of(what, 1);
            if (it == null) { err(s, "Нет предмета " + what); return; }
            int c = Math.min(Math.max(1, it.maxStackSize()), left);
            it = it.withAmount(c);
            if (!p.getInventory().addItemStack(it)) p.dropItem(it);
            left -= c;
        }
        ok(s, what + " ×" + n + " → " + p.getUsername());
    }

    private static void info(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.AQUA)); }

    private static void ok(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.GREEN)); }

    private static void err(CommandSender s, String t) { s.sendMessage(Component.text(t, NamedTextColor.RED)); }
}
