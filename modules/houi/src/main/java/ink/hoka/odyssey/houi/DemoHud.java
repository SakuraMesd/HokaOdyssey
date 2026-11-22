package ink.hoka.odyssey.houi;

import com.google.gson.JsonObject;
import ink.hoka.odyssey.api.ModuleScheduler;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Тестовый HUD — пример для модулей: слой demo_hud (кислород плавно убывает, монеты из тестового магазина) и
 * всплывающая подсказка demo_toast; пока включён — спрятаны ванильные голод и опыт. {@code /houi hud} — вкл/выкл.
 */
final class DemoHud {

    private final HoHud hud;
    private final DemoShop shop;
    private final Map<UUID, Integer> oxygen = new ConcurrentHashMap<>();
    private final Map<UUID, Long> toastUntil = new ConcurrentHashMap<>();

    DemoHud(HoHud hud, DemoShop shop, ModuleScheduler scheduler) {
        this.hud = hud;
        this.shop = shop;
        scheduler.repeat(this::tick, 20, 20);
    }

    boolean toggle(Player p) {
        if (oxygen.remove(p.getUuid()) != null) {
            hud.hide(p, "demo_hud");
            hud.hide(p, "demo_toast");
            hud.hideVanilla(p, List.of());
            return false;
        }
        oxygen.put(p.getUuid(), 100);
        JsonObject d = new JsonObject();
        d.addProperty("oxygen", 100);
        d.addProperty("coins", shop.coins(p));
        if (!hud.show(p, "demo_hud", d)) { oxygen.remove(p.getUuid()); return false; }
        hud.hideVanilla(p, List.of("food", "xp", "xp_level"));
        toast(p, "&bHUD включён — кислород убывает");
        return true;
    }

    /** Подсказка на 3 секунды (каждая — с анимацией появления). */
    void toast(Player p, String text) {
        if (!oxygen.containsKey(p.getUuid())) return;
        JsonObject d = new JsonObject();
        d.addProperty("toast", text);
        hud.show(p, "demo_toast", d);
        toastUntil.put(p.getUuid(), System.currentTimeMillis() + 3000);
    }

    /** Монеты сменились (магазин) — обновить HUD. */
    void coins(Player p) {
        if (!oxygen.containsKey(p.getUuid())) return;
        JsonObject d = new JsonObject();
        d.addProperty("coins", shop.coins(p));
        hud.update(p, "demo_hud", d);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            Integer o = oxygen.get(p.getUuid());
            if (o == null) continue;
            int n = o - 4;
            if (n <= 0) { n = 100; toast(p, "&aКислород пополнен"); }
            oxygen.put(p.getUuid(), n);
            JsonObject d = new JsonObject();
            d.addProperty("oxygen", n);
            hud.update(p, "demo_hud", d);
            Long until = toastUntil.get(p.getUuid());
            if (until != null && now > until) { toastUntil.remove(p.getUuid()); hud.hide(p, "demo_toast"); }
        }
    }

    void forget(UUID id) {
        oxygen.remove(id);
        toastUntil.remove(id);
    }
}
