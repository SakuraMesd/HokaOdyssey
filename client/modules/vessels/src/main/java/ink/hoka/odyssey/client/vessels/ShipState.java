package ink.hoka.odyssey.client.vessels;

import com.google.gson.JsonObject;

/** Сводка своего корабля от сервера (S2C STATE): топливо, перезарядки, варп. Тики — по часам клиента от прихода. */
final class ShipState {

    int fuel, capacity = 1, warpCost;
    double speedMul = 1, gunRange = 300;
    boolean empty;
    int gunReload = 10, warpPhase, warpCharge = 60, warpReload = 600;
    /** Тик клиента, когда кончится зарядка / перезарядка варпа. */
    long warpChargeEnd, warpReloadEnd;
    boolean known;
    /** Прочность (S2C HULL): сейчас, наибольшая, целость зон 0…1; known — пришло. */
    double hp, hpMax;
    final float[] zones = {1, 1, 1, 1, 1};
    boolean hullKnown;

    /** S2C HULL. Вернуть true — это попадание/удар (встряска, красный край). */
    boolean readHull(JsonObject o) {
        hp = d(o, "hp", hp);
        hpMax = Math.max(1, d(o, "max", hpMax));
        if (o.has("zones") && o.get("zones").isJsonArray()) {
            var a = o.getAsJsonArray("zones");
            for (int i = 0; i < Math.min(a.size(), zones.length); i++) {
                try { zones[i] = (float) Math.clamp(a.get(i).getAsDouble(), 0, 1); } catch (Exception ignored) { }
            }
        }
        hullKnown = true;
        return o.has("hit") && o.get("hit").getAsBoolean();
    }

    void read(JsonObject o, long now) {
        fuel = i(o, "fuel", fuel);
        capacity = Math.max(1, i(o, "capacity", capacity));
        warpCost = i(o, "warp-cost", warpCost);
        speedMul = d(o, "speed-mul", speedMul);
        empty = o.has("empty") && o.get("empty").getAsBoolean();
        gunReload = Math.max(1, i(o, "gun-reload", gunReload));
        gunRange = d(o, "gun-range", gunRange);
        warpPhase = i(o, "warp-phase", 0);
        warpCharge = Math.max(1, i(o, "warp-charge", warpCharge));
        warpReload = Math.max(1, i(o, "warp-reload", warpReload));
        warpChargeEnd = now + i(o, "warp-left", 0);
        warpReloadEnd = now + i(o, "warp-reload-left", 0);
        known = true;
    }

    private static int i(JsonObject o, String k, int def) {
        try { return o.has(k) ? o.get(k).getAsInt() : def; } catch (Exception e) { return def; }
    }

    private static double d(JsonObject o, String k, double def) {
        try { return o.has(k) ? o.get(k).getAsDouble() : def; } catch (Exception e) { return def; }
    }
}
