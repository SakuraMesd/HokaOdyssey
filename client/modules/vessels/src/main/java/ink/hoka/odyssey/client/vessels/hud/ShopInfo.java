package ink.hoka.odyssey.client.vessels.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Сводка окна мастерской (S2C SHOP): номер окна и заголовок (по ним мод узнаёт окно), корабль, рецепты — что
 * получится, сколько, хватает ли, и что нужно (сколько есть в грузе). Приходит перед открытием и при смене груза.
 */
public final class ShopInfo {

    /** Что нужно: название, сколько, сколько есть в грузе. */
    public record Need(String name, int need, int have) {
        public boolean ok() { return have >= need; }
    }

    /** Рецепт: название результата, сколько штук, хватает ли всего, что нужно. */
    public record Recipe(String name, int count, boolean ok, List<Need> needs) {}

    public int window = Integer.MIN_VALUE, id;
    public String title = "", kind = "";
    public final List<Recipe> recipes = new ArrayList<>();

    public static ShopInfo read(JsonObject o) {
        ShopInfo s = new ShopInfo();
        try { s.window = o.get("window").getAsInt(); } catch (RuntimeException ignored) { }
        try { s.id = o.get("id").getAsInt(); } catch (RuntimeException ignored) { }
        try { s.title = o.get("title").getAsString(); } catch (RuntimeException ignored) { }
        try { s.kind = o.get("kind").getAsString(); } catch (RuntimeException ignored) { }
        if (o.has("recipes") && o.get("recipes").isJsonArray()) for (JsonElement e : o.getAsJsonArray("recipes")) {
            try {
                JsonObject r = e.getAsJsonObject();
                List<Need> needs = new ArrayList<>();
                if (r.has("needs")) for (JsonElement ne : r.getAsJsonArray("needs")) {
                    JsonObject n = ne.getAsJsonObject();
                    needs.add(new Need(n.get("name").getAsString(), n.get("need").getAsInt(), n.get("have").getAsInt()));
                }
                s.recipes.add(new Recipe(r.get("name").getAsString(), r.has("count") ? r.get("count").getAsInt() : 1,
                        r.has("ok") && r.get("ok").getAsBoolean(), List.copyOf(needs)));
            } catch (RuntimeException ignored) { }
        }
        return s;
    }

    /** То же окно: номер окна игры (байт Minestom) и заголовок совпадают. */
    public boolean matches(int containerId, String screenTitle) {
        return window != Integer.MIN_VALUE && (byte) containerId == (byte) window && (title.isEmpty() || title.equals(screenTitle));
    }
}
