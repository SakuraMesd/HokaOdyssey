package ink.hoka.odyssey.client.vessels.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Сводка корабля для окна (S2C MENU): номер окна и заголовок (по ним мод узнаёт окно), вид, владелец, бак, варп,
 * орудие, улучшения и их множители. Приходит перед открытием окна и при каждой смене груза.
 */
public final class MenuInfo {

    public int window = Integer.MIN_VALUE, id, fuel, capacity = 1, warpCost, upgrades;
    public String title = "", kind = "", owner;
    public double warpDistance, warpCharge, warpReload, gunDamage, gunReload, gunRange, speedMul = 1, burn;
    /** Множители улучшений: скорость, цена варпа, перезарядка орудия, урон, перезарядка варпа (1 — без улучшений). */
    public double mulSpeed = 1, mulWarpCost = 1, mulGunReload = 1, mulGunDamage = 1, mulWarpReload = 1;
    /** Прочность (vessels-4): сейчас, наибольшая (0 — сервер старый, не пришло), целость зон 0…1. */
    public double hp, hpMax;
    public final float[] zones = {1, 1, 1, 1, 1};
    /** vessels-5: разбитые зоны с эффектом (биты {@link HudView#GUN} …) и поворот (1 — как обычно). */
    public int broken;
    public double turn = 1;

    public static MenuInfo parse(String json) {
        MenuInfo m = new MenuInfo();
        m.read(JsonParser.parseString(json).getAsJsonObject());
        return m;
    }

    public void read(JsonObject o) {
        window = i(o, "window", window);
        id = i(o, "id", id);
        title = s(o, "title", title);
        kind = s(o, "kind", kind);
        owner = s(o, "owner", null);
        fuel = i(o, "fuel", fuel);
        capacity = Math.max(1, i(o, "capacity", capacity));
        warpCost = i(o, "warp-cost", warpCost);
        warpDistance = d(o, "warp-distance", warpDistance);
        warpCharge = d(o, "warp-charge", warpCharge);
        warpReload = d(o, "warp-reload", warpReload);
        gunDamage = d(o, "gun-damage", gunDamage);
        gunReload = d(o, "gun-reload", gunReload);
        gunRange = d(o, "gun-range", gunRange);
        speedMul = d(o, "speed-mul", speedMul);
        burn = d(o, "burn", burn);
        upgrades = i(o, "upgrades", upgrades);
        hp = d(o, "hp", hp);
        hpMax = d(o, "hp-max", hpMax);
        if (o.has("zones") && o.get("zones").isJsonArray()) {
            var a = o.getAsJsonArray("zones");
            for (int i = 0; i < Math.min(a.size(), zones.length); i++) {
                try { zones[i] = (float) Math.clamp(a.get(i).getAsDouble(), 0, 1); } catch (Exception ignored) { }
            }
        }
        broken = i(o, "broken", 0);
        JsonObject ef = o.has("effects") && o.get("effects").isJsonObject() ? o.getAsJsonObject("effects") : new JsonObject();
        turn = d(ef, "turn", 1);
        JsonObject mul = o.has("mul") && o.get("mul").isJsonObject() ? o.getAsJsonObject("mul") : new JsonObject();
        mulSpeed = d(mul, "speed", 1);
        mulWarpCost = d(mul, "warp-cost", 1);
        mulGunReload = d(mul, "gun-reload", 1);
        mulGunDamage = d(mul, "gun-damage", 1);
        mulWarpReload = d(mul, "warp-reload", 1);
    }

    /** То же окно: номер окна игры (сервер шлёт байт Minestom) и заголовок совпадают. */
    public boolean matches(int containerId, String screenTitle) {
        return window != Integer.MIN_VALUE && (byte) containerId == (byte) window && (title.isEmpty() || title.equals(screenTitle));
    }

    private static int i(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; } catch (Exception e) { return def; }
    }

    private static double d(JsonObject o, String k, double def) {
        try { return o.has(k) ? o.get(k).getAsDouble() : def; } catch (Exception e) { return def; }
    }

    private static String s(JsonObject o, String k, String def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def; } catch (Exception e) { return def; }
    }
}
