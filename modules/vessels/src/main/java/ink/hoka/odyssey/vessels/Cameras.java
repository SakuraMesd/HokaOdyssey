package ink.hoka.odyssey.vessels;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minestom.server.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Камеры кораблей на сервере (vessels-5): {@link CameraBook} из cameras.json, редактор в игре (OP, пилот), рассылка
 * пилотам. Редактор: /hovessel camera edit … → моду S2C CAMEDIT (камеры области правки, камеры корабля в деле, какую
 * камеру начать); мод отвечает C2S CAMEDIT 1 (запущен — иначе через 2 с «нужен мод новее»), сохраняет C2S CAMSAVE (сервер
 * проверяет OP и что игрок — пилот этого корабля, пишет файл и сразу шлёт камеры всем пилотам, кого это касается),
 * выход — C2S CAMEDIT 0.
 */
final class Cameras {

    /** Правка у игрока: корабль (стоит), область (вид или корабль), когда начата (тик), мод ответил. */
    static final class Edit {
        final int vessel;
        final CameraBook.Scope scope;
        final long at;
        boolean acked;

        Edit(int vessel, CameraBook.Scope scope, long at) { this.vessel = vessel; this.scope = scope; this.at = at; }
    }

    private final VesselsModule mod;
    private volatile CameraBook book = CameraBook.EMPTY;
    private CameraBook.Store store;
    private final Map<UUID, Edit> edits = new ConcurrentHashMap<>();

    Cameras(VesselsModule mod) { this.mod = mod; }

    void load(java.nio.file.Path file) {
        store = new CameraBook.Store(file);
        book = store.load();
        if (store.broken())
            mod.logger().error("cameras.json не прочитан ({}) — файл не будет перезаписан (копия — cameras.json.broken); камеры — по умолчанию",
                    store.brokenWhy());
    }

    CameraBook book() { return book; }

    boolean broken() { return store == null || store.broken(); }

    /** Камеры корабля в деле (корабль поверх вида). */
    CameraBook.Set of(Vessel v) { return book.effective(v.kind, v.id); }

    /** Новый снимок: сразу в силе, файл — в фоне, пилотам — новые камеры. false — файл битый (правка не принята). */
    boolean change(CameraBook next) {
        if (broken()) return false;
        book = next;
        String json = next.toJson();
        mod.scheduler().async(() -> {
            try { store.write(json); }
            catch (Exception e) { mod.logger().error("cameras.json не записан: {}", e.toString()); }
        });
        for (Vessel v : mod.vessels()) if (v.pilot != null) mod.client.cameras(v);
        return true;
    }

    /** Корабль удалён — его камеры прочь (если были). */
    void forgetShip(int id) {
        if (!book.ships().containsKey(id) || broken()) return;
        change(book.forgetShip(id));
    }

    // ───────────────────────── редактор ─────────────────────────

    Edit editOf(Player p) { return edits.get(p.getUuid()); }

    /** Начать правку: which — 0 первое лицо, n — вид F5 n; ship — у этого корабля (иначе — у вида). */
    String startEdit(Player p, int which, boolean ship) {
        if (!mod.server().isOp(p)) return "Только для OP";
        Piloting pl = mod.pilotOf(p);
        if (pl == null) return "Сядьте пилотом в корабль, камеры которого правите";
        if (!mod.client.isReady(p)) return "Нужен клиентский модуль vessels";
        if (broken()) return "cameras.json битый — камеры не сохраняются (см. лог)";
        Vessel v = pl.vessel;
        CameraBook.Scope scope = ship ? CameraBook.Scope.ship(v.id) : CameraBook.Scope.kind(v.kind);
        int n = Math.clamp(which, 0, CameraBook.MAX_F5);
        int have = book.get(scope).f5().size();
        if (n > have + 1) n = have + 1;                                          // дальше конца — новый вид в конец
        edits.put(p.getUuid(), new Edit(v.id, scope, mod.ticks()));
        mod.client.camEdit(p, v, scope, n, book.get(scope), of(v));
        return null;
    }

    /** C2S CAMEDIT: 1 — мод открыл редактор, 0 — вышел. */
    void onEditState(Player p, int state) {
        Edit e = edits.get(p.getUuid());
        if (e == null) return;
        if (state == 1) e.acked = true;
        else edits.remove(p.getUuid());
    }

    /** C2S CAMSAVE: {which, cam}. Проверки: OP, правка открыта, игрок — пилот того же корабля. */
    void onSave(Player p, String json) {
        Edit e = edits.get(p.getUuid());
        if (e == null) return;
        Piloting pl = mod.pilotOf(p);
        if (!mod.server().isOp(p) || pl == null || pl.vessel.id != e.vessel) {
            edits.remove(p.getUuid());
            mod.client.message(p, "Камера не сохранена: нужно быть OP и пилотом этого корабля", true);
            return;
        }
        int which;
        CameraBook.Cam cam;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            which = o.get("which").getAsInt();
            cam = CameraBook.camFrom(o.getAsJsonObject("cam"));
        } catch (RuntimeException ex) {
            mod.client.message(p, "Камера не сохранена: плохие данные", true);
            return;
        }
        VesselKind k = mod.kinds().get(pl.vessel.kind);
        double lim = (k != null ? k.radius() : 8) * 6 + 64;                    // камера — не дальше разумного от корабля
        if (Math.abs(cam.x()) > lim || Math.abs(cam.y()) > lim || Math.abs(cam.z()) > lim) {
            mod.client.message(p, "Камера слишком далеко от корабля", true);
            return;
        }
        CameraBook next;
        try {
            next = which <= 0 ? book.withFirst(e.scope, cam) : book.withF5(e.scope, which, cam);
        } catch (IllegalArgumentException ex) {
            mod.client.message(p, "Камера не сохранена: " + ex.getMessage(), true);
            return;
        }
        if (!change(next)) { mod.client.message(p, "cameras.json битый — камеры не сохраняются", true); return; }
        String name = which <= 0 ? "первое лицо" : "вид F5 " + which;
        mod.logger().info("{}: камера «{}» — {}: {}", p.getUsername(), name, e.scope.title(), cam.brief());
        mod.client.camSaved(p, which, book.get(e.scope), of(pl.vessel), "Сохранено: " + name + " (" + e.scope.title() + ")");
    }

    /** Раз в тик: мод не ответил на правку за 2 с — сказать, что нужен мод новее. Пилот вышел — правка закрыта. */
    void tick() {
        long now = mod.ticks();
        for (var it = edits.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Edit e = en.getValue();
            Vessel v = mod.vessel(e.vessel);
            Player p = v != null && v.pilot != null ? v.pilot.player : null;
            if (p == null || !p.getUuid().equals(en.getKey())) { it.remove(); continue; }
            if (!e.acked && now - e.at > 40) {
                it.remove();
                p.sendMessage(net.kyori.adventure.text.Component.text("Редактор камер не запустился — нужен клиентский модуль vessels 0.5+",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
        }
    }

    void forget(Player p) { edits.remove(p.getUuid()); }

    void clear() { edits.clear(); }
}
