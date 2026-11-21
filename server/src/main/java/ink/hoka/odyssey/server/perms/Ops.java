package ink.hoka.odyssey.server.perms;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.command.builder.condition.CommandCondition;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Операторы сервера (ops.json, как у ванильного сервера). Все админ-команды /ho… — только для OP и консоли.
 * Оператора можно добавить по нику, даже если игрок не в сети: UUID запишется при его первом входе
 * (сервер в online-mode, ник подтверждён Mojang при входе).
 */
public final class Ops {

    private static final Logger LOG = LoggerFactory.getLogger("ops");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Запись в ops.json. uuid может быть пустым — до первого входа игрока. */
    static final class Entry {
        String uuid;
        String name;
        Entry(String uuid, String name) { this.uuid = uuid; this.name = name; }
    }

    private final Path file;
    private final List<Entry> list = new CopyOnWriteArrayList<>();

    public Ops(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            List<Entry> l = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Entry>>() {}.getType());
            if (l != null) for (Entry e : l) if (e != null && e.name != null) list.add(e);
        } catch (IOException | RuntimeException e) {
            LOG.error("ops.json не прочитан: {}", e.toString());
        }
    }

    private synchronized void save() {
        try { Files.writeString(file, GSON.toJson(list), StandardCharsets.UTF_8); }
        catch (IOException e) { LOG.error("ops.json не записан: {}", e.toString()); }
    }

    public boolean isOp(Player p) {
        String id = p.getUuid().toString();
        for (Entry e : list) {
            if (id.equals(e.uuid)) return true;
            if ((e.uuid == null || e.uuid.isEmpty()) && e.name.equalsIgnoreCase(p.getUsername())) {
                e.uuid = id;                                              // первый вход — запоминаем UUID
                e.name = p.getUsername();
                save();
                return true;
            }
        }
        return false;
    }

    /** OP по нику (без учёта регистра). */
    public boolean isOp(String name) {
        for (Entry e : list) if (e.name.equalsIgnoreCase(name)) return true;
        return false;
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Entry e : list) out.add(e.name + (e.uuid == null || e.uuid.isEmpty() ? " (ещё не заходил)" : ""));
        return out;
    }

    /** Дать или забрать OP. Возвращает true, если теперь OP. */
    public synchronized boolean toggle(String name) {
        Player online = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(name);
        String key = online != null ? online.getUsername() : name;
        boolean removed = list.removeIf(e -> e.name.equalsIgnoreCase(key) || (online != null && online.getUuid().toString().equals(e.uuid)));
        if (!removed) list.add(new Entry(online != null ? online.getUuid().toString() : "", key));
        save();
        if (online != null) apply(online);
        return !removed;
    }

    /** Уровень прав игрока (клиент по нему показывает команды и F3+F4) и список команд. */
    public void apply(Player p) {
        p.setPermissionLevel(isOp(p) ? 4 : 0);
        p.refreshCommands();
    }

    /** Условие для админ-команд: консоль или OP. Скрывает команду из подсказок у остальных. */
    public CommandCondition admin() {
        return (sender, cmd) -> isAdmin(sender);
    }

    public boolean isAdmin(CommandSender s) {
        return s instanceof ConsoleSender || (s instanceof Player p && isOp(p));
    }
}
