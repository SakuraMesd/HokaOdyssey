package ink.hoka.odyssey.client.holo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Сессия редактора (без классов игры): черновики голограмм (JSON как у сервера), какая выбрана, отмена/повтор в
 * пределах сессии (Ctrl+Z / Ctrl+Y), какие черновики не сохранены. Правки идут через {@link #change}: снимок «до»
 * уходит в историю; ввод текста в одно поле подряд склеивается в одну отмену. Мир рисует черновики сразу
 * (живой предпросмотр), на сервер — по «Сохранить» (или сразу, если включено «авто»).
 */
public final class Session {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final int HISTORY = 300;

    /** Шаг истории: голограмма id была такой (null — её не было). */
    record Step(String id, String json, String selected) {}

    /** Голограммы с сервера (INFO) и черновики поверх них. */
    public final Map<String, JsonObject> server = new LinkedHashMap<>();
    public final Map<String, JsonObject> drafts = new LinkedHashMap<>();
    public final java.util.Set<String> dirty = new java.util.LinkedHashSet<>();
    /** Отправлены на сервер, ответа ещё нет: отказ — снова «не сохранено». */
    final Map<String, JsonObject> pending = new LinkedHashMap<>();
    private final Deque<Step> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    public String selected;
    public boolean auto;
    /** Растёт при каждой правке — рисование пересобирает черновик. */
    public int version;
    private String group;
    private long groupAt;
    /** Отправить сохранение (JSON) — модуль шлёт на сервер. */
    public Consumer<JsonObject> saver = o -> { };

    public JsonObject current() { return selected == null ? null : get(selected); }

    /** Черновик или серверная версия. */
    public JsonObject get(String id) {
        JsonObject d = drafts.get(id);
        return d != null ? d : server.get(id);
    }

    public void select(String id) {
        selected = id;
        group = null;
    }

    /**
     * Правка выбранной голограммы. group ≠ null — правки одного поля подряд (за 1.5 с) — одна отмена.
     * Возвращает false, если выбирать нечего.
     */
    public boolean change(String grp, Consumer<JsonObject> edit) {
        if (selected == null) return false;
        JsonObject base = get(selected);
        if (base == null) return false;
        long now = System.currentTimeMillis();
        if (grp != null) grp = selected + "|" + grp;                             // склейка — только того же поля той же голограммы
        boolean merge = grp != null && grp.equals(group) && now - groupAt < 1500 && !undo.isEmpty();
        JsonObject d = drafts.computeIfAbsent(selected, k -> base.deepCopy());
        String before = GSON.toJson(d);
        edit.accept(d);
        String after = GSON.toJson(d);
        if (before.equals(after)) return true;
        if (!merge) push(new Step(selected, before, selected));
        group = grp;
        groupAt = now;
        touched(selected);
        return true;
    }

    /** Новая голограмма (или замена целиком): в историю — что было. */
    public void put(JsonObject o) {
        String id = o.get("id").getAsString();
        JsonObject was = get(id);
        push(new Step(id, was == null ? null : GSON.toJson(was), selected));
        drafts.put(id, o);
        selected = id;
        group = null;
        touched(id);
    }

    private void push(Step s) {
        undo.push(s);
        while (undo.size() > HISTORY) undo.removeLast();
        redo.clear();
    }

    private void touched(String id) {
        dirty.add(id);
        version++;
        if (auto) autoAt = System.currentTimeMillis() + 600;
    }

    private long autoAt;

    /** «Авто»: сохранить черновики через 0.6 с после последней правки (не на каждый кадр перетаскивания). */
    public void tickAuto(long now) {
        if (autoAt != 0 && now >= autoAt) {
            autoAt = 0;
            if (auto) saveAll();
        }
    }

    public boolean canUndo() { return !undo.isEmpty(); }

    public boolean canRedo() { return !redo.isEmpty(); }

    public boolean undo() { return swap(undo, redo); }

    public boolean redo() { return swap(redo, undo); }

    private boolean swap(Deque<Step> from, Deque<Step> to) {
        Step s = from.poll();
        if (s == null) return false;
        JsonObject now = get(s.id());
        to.push(new Step(s.id(), now == null ? null : GSON.toJson(now), selected));
        if (s.json() == null) {
            drafts.remove(s.id());
            dirty.remove(s.id());
            if (s.id().equals(selected)) selected = server.containsKey(s.id()) ? s.id() : null;
        } else {
            drafts.put(s.id(), JsonParser.parseString(s.json()).getAsJsonObject());
            selected = s.id();
            dirty.add(s.id());
            if (auto) autoAt = System.currentTimeMillis() + 600;
        }
        group = null;
        version++;
        return true;
    }

    /** Сохранить черновик на сервер (ответ — RESULT, новая версия — SET). */
    public boolean save(String id) {
        JsonObject d = drafts.get(id);
        if (d == null) return false;
        saver.accept(d);
        server.put(id, d.deepCopy());
        dirty.remove(id);
        drafts.remove(id);                                                      // дальше — серверная версия (SET)
        pending.put(id, d);
        version++;
        return true;
    }

    public int saveAll() {
        int n = 0;
        for (String id : java.util.List.copyOf(dirty)) if (save(id)) n++;
        return n;
    }

    /** Ответ сервера на сохранение: отказ — черновики снова «не сохранены» (правки не теряются). */
    public void result(String id, boolean ok) {
        JsonObject d = id == null ? null : pending.remove(id);
        if (!ok && d != null && !drafts.containsKey(id)) {
            drafts.put(id, d);
            dirty.add(id);
        }
        version++;
    }

    /** С сервера пришла новая версия / голограмма удалена: черновик без правок больше не нужен. */
    public void serverChanged(String id) {
        if (!dirty.contains(id) && drafts.remove(id) != null) version++;
    }

    /** Удалили на сервере — убрать отовсюду (истории это не касается: удаление не отменяется). */
    public void deleted(String id) {
        server.remove(id);
        drafts.remove(id);
        dirty.remove(id);
        if (id.equals(selected)) selected = null;
        version++;
    }

    /** Отбросить несохранённое (выход без сохранения). */
    public void discard() {
        for (String id : dirty) drafts.remove(id);
        dirty.clear();
        version++;
    }

    public static JsonObject parse(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    public static String json(JsonElement e) { return GSON.toJson(e); }
}
