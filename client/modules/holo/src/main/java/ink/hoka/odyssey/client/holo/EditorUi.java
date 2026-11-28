package ink.hoka.odyssey.client.holo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Редактор голограмм (без классов игры — рисует через {@link Gui}, тот же код в игре и в превью PNG). Слева — список
 * (поиск, фильтр по карте, расстояние, создать, копия, удалить с подтверждением, телепорт); в середине — вкладки
 * «Свойства», «Строки» (элементы, ряды, перетаскивание мышью, поля по типу, конструктор действий ЛКМ/ПКМ), «Шаблоны»;
 * справа — живое превью (клик по элементу — выбрать его). Внизу — Сохранить / Отмена / Повтор / Авто.
 * Правки — в черновик {@link Session} (мир показывает их сразу), на сервер — «Сохранить» (Ctrl+S) или «Авто».
 * Отмена/повтор — Ctrl+Z / Ctrl+Y. Интерфейс «немедленный»: каждый кадр заново, клики — по областям прошлого кадра.
 */
public final class EditorUi {

    // цвета
    static final int BG = 0xE605080F, FIELD = 0xFF0A1120, BTN = 0xFF0F1828, BTN_HOT = 0xFF17304C, TEXT = 0xFFE6EEF8,
            DIM = 0xFF7F8DA0, GLOW = 0xFF8FD8FF, LIGHT = 0xFFD9E2EE, AMBER = 0xFFFFC46B, RED = 0xFFFF6B7A, GREEN = 0xFF6BFFAE;
    static final int RH = 14;
    static final String[] TYPES = {"text", "item", "image", "bar", "button", "separator", "spacer"};
    static final String[] TYPE_NAMES = {"Текст", "Предмет", "Картинка", "Шкала", "Кнопка", "Линия", "Отступ"};
    static final String[] ACTS = {"cmd", "console", "msg", "sound", "tp", "ui", "page"};
    static final String[] ACT_NAMES = {"команда игрока", "команда консоли (OP)", "сообщение", "звук", "телепорт", "окно houi", "страница"};
    static final String[] ACT_HINTS = {"spawn", "say {player} нажал", "<#8FD8FF>Привет!", "minecraft:ui.button.click 1 1",
            "x y z [курс наклон] или карта x y z", "id окна", "next | prev | номер"};
    static final String[] TAGS = {"<b>", "<i>", "<u>", "<#FFC46B>", "<#8FD8FF>", "<gray>", "<gradient:#8FD8FF:#FFC46B>", "<rainbow>", "<br>", "<reset>"};

    private final Gui.Host host;
    private Gui g;
    private int W, H;
    private float mx, my;

    /** Область клика прошлого кадра. */
    private record Hit(float x0, float y0, float x1, float y1, Runnable left, Runnable right) {}

    private final List<Hit> hits = new ArrayList<>();
    private int cx0, cy0, cx1 = Integer.MAX_VALUE, cy1 = Integer.MAX_VALUE;

    // поле ввода
    private String focus;
    private final StringBuilder buf = new StringBuilder();
    private int cur;
    private Consumer<String> onEdit;
    private boolean invalid, focusTouched;

    // всплывающий список
    private record Popup(float x, float y, float w, List<String> labels, List<Runnable> picks) {}

    private Popup popup;
    private float popupScroll;

    // состояние
    int tab = 1;
    int page, selLine = -1, selElem = -1, actSide;
    boolean inRow;
    private String search = "", mapFilter, newId = "", tplName = "", tplId = "";
    private float listScroll, midScroll;
    private float midX0, midY0, midX1, midY1, listY0, listY1;
    private long confirmDelAt;
    private String confirmTpl;
    private int dragLine = -1;
    private float linesY0;
    private int linesCount;
    private boolean warnedDirty;
    private String note;
    private long noteAt;
    // превью: где нарисована панель (для клика по элементу)
    private Layout.Panel prevPanel;
    private float prevX, prevY, prevS;
    private int prevVersion = -1, prevPage = -1;
    private String prevId;

    public EditorUi(Gui.Host host) { this.host = host; }

    private Session s() { return host.session(); }

    // ═════════════════════════════ кадр ═════════════════════════════

    public void render(Gui g, int w, int h, float mx, float my) {
        this.g = g;
        W = w;
        H = h;
        this.mx = mx;
        this.my = my;
        hits.clear();
        Session s = s();
        JsonObject cur = s.current();
        if (cur != null) {
            int n = pages(cur).size();
            if (page >= n) page = n - 1;
            if (page < 0) page = 0;
            JsonArray ls = lines(cur, page);
            if (selLine >= ls.size()) { selLine = ls.size() - 1; selElem = selLine < 0 ? -1 : 0; }
            if (selLine >= 0 && selElem >= row(ls, selLine).size()) selElem = row(ls, selLine).size() - 1;
        }
        int leftW = clamp(Math.round(w * 0.2f), 118, 160), rightW = clamp(Math.round(w * 0.28f), 130, 250);
        int top = 16, bottom = h - 22;

        // заголовок
        g.fill(0, 0, w, top - 2, 0xC005080F);
        g.text("ГОЛОГРАММЫ", 6, 4, AMBER);
        String title = cur == null ? "· ничего не выбрано" : "· " + s.selected + (s.dirty.contains(s.selected) ? "  ● не сохранено" : "  ✓ сохранено");
        g.text(title, 6 + g.width("ГОЛОГРАММЫ") + 6, 4, cur != null && s.dirty.contains(s.selected) ? AMBER : DIM);
        String keys = "Ctrl+Z / Ctrl+Y — отмена/повтор · Ctrl+S — сохранить · Esc — выход";
        g.text(keys, w - g.width(keys) - 6, 4, DIM);

        listPane(4, top, 4 + leftW, bottom);
        midPane(8 + leftW, top, w - rightW - 8, bottom);
        previewPane(w - rightW - 4, top, w - 4, bottom);
        bottomBar(bottom + 3, w);
        if (popup != null) drawPopup();
    }

    // ═════════════════════════════ список ═════════════════════════════

    private void listPane(int x0, int y0, int x1, int y1) {
        frame(x0, y0, x1, y1);
        int x = x0 + 5, w = x1 - x0 - 10, y = y0 + 5;
        g.text("Список", x, y, LIGHT);
        y += 11;
        field("search", x, y, w, search, 0, v -> search = v, "поиск…");
        y += RH + 2;
        String mf = mapFilter == null ? "все карты" : mapFilter;
        int fy = (int) y;
        button(x, y, w, 12, "Карта: " + mf + " ▾", true, () -> {
            List<String> l = new ArrayList<>(), v = new ArrayList<>();
            l.add("все карты");
            v.add(null);
            for (JsonElement e : arr(host.info(), "maps")) { l.add(e.getAsString()); v.add(e.getAsString()); }
            if (!l.contains(host.map())) { l.add(host.map()); v.add(host.map()); }
            List<Runnable> r = new ArrayList<>();
            for (String m : v) r.add(() -> mapFilter = m);
            open(x, fy + 12, w, l, r);
        });
        y += 15;
        listY0 = y;
        listY1 = y1 - 50;
        clip(x0 + 2, (int) listY0, x1 - 2, (int) listY1);
        double[] pl = host.player();
        float ry = listY0 - listScroll;
        int count = 0;
        for (String id : ids()) {
            JsonObject o = s().get(id);
            String map = o == null ? "?" : str(o, "map", "world");
            if (mapFilter != null && !mapFilter.equals(map)) continue;
            if (!search.isEmpty() && !id.contains(search.toLowerCase(Locale.ROOT))) continue;
            count++;
            boolean sel = id.equals(s().selected);
            if (ry + 12 > listY0 && ry < listY1) {
                boolean hot = in(x0 + 2, ry, x1 - 2, ry + 12);
                if (sel || hot) g.fill(x0 + 2, ry, x1 - 2, ry + 12, sel ? 0xFF1A3350 : 0xFF111C2C);
                if (sel) g.fill(x0 + 2, ry, x0 + 4, ry + 12, AMBER);
                g.text(trim(id + (s().dirty.contains(id) ? " ●" : ""), w - 34), x + 1, ry + 2, sel ? AMBER : TEXT);
                String right = map;
                if (o != null && map.equals(host.map()) && pl != null) {
                    JsonArray p = o.getAsJsonArray("pos");
                    double d = Math.sqrt(sq(p.get(0).getAsDouble() - pl[0]) + sq(p.get(1).getAsDouble() - pl[1]) + sq(p.get(2).getAsDouble() - pl[2]));
                    right = d < 1000 ? Math.round(d) + " м" : String.format(Locale.ROOT, "%.1f км", d / 1000);
                }
                g.text(right, x1 - 6 - g.width(right), ry + 2, DIM);
                String fid = id;
                hit(x0 + 2, ry, x1 - 2, ry + 12, () -> select(fid), () -> host.tp(fid));
            }
            ry += 12;
        }
        if (count == 0) g.text(ids().isEmpty() ? "пусто" : "не найдено", x, listY0 + 2, DIM);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, count * 12 - (listY1 - listY0))));
        unclip();
        int by = y1 - 47;
        field("newid", x, by, w, newId, 0, v -> newId = v.toLowerCase(Locale.ROOT), "новый id");
        by += RH + 1;
        int hw = (w - 3) / 2;
        button(x, by, hw, 13, "Создать", true, this::create);
        button(x + hw + 3, by, hw, 13, "Копия", s().current() != null, this::copy);
        by += 15;
        boolean confirm = System.currentTimeMillis() - confirmDelAt < 3000;
        button(x, by, hw, 13, confirm ? "Точно?" : "Удалить", s().current() != null, this::delete);
        if (confirm) g.fill(x, by + 12, x + hw, by + 13, RED);
        button(x + hw + 3, by, hw, 13, "ТП", s().current() != null, () -> host.tp(s().selected));
    }

    List<String> ids() {
        TreeSet<String> t = new TreeSet<>(s().server.keySet());
        t.addAll(s().drafts.keySet());
        return new ArrayList<>(t);
    }

    void select(String id) {
        s().select(id);
        page = 0;
        selLine = -1;
        selElem = -1;
        unfocus();
    }

    private void create() {
        String id = newId.trim();
        if (!id.matches("[a-z0-9_\\-]{1,48}")) { note("id: латиница a-z, цифры, _ и - (до 48)"); return; }
        if (s().get(id) != null) { note("«" + id + "» уже есть"); return; }
        JsonObject o = Session.parse("{\"pages\":[{\"lines\":[[{\"type\":\"text\",\"text\":\"<b><#8FD8FF>" + id.toUpperCase(Locale.ROOT)
                + "</#8FD8FF></b>\",\"size\":1.4}],[{\"type\":\"separator\"}],[{\"type\":\"text\",\"text\":\"Новая голограмма\"}]]}]}");
        place(o, id);
        s().put(o);
        select(id);
        newId = "";
        note("Создана «" + id + "» (черновик) — «Сохранить», чтобы увидели все");
    }

    /** id, карта, позиция перед игроком (2.5 блока), курс к игроку. */
    private void place(JsonObject o, String id) {
        o.addProperty("id", id);
        o.addProperty("map", host.map());
        double[] p = host.player();
        if (p != null) {
            double yaw = Math.toRadians(p[3]);
            double dx = -Math.sin(yaw), dz = Math.cos(yaw);
            JsonArray pos = new JsonArray();
            pos.add(round(p[0] + dx * 2.5, 0.01));
            pos.add(round(p[1], 0.01));
            pos.add(round(p[2] + dz * 2.5, 0.01));
            o.add("pos", pos);
            o.addProperty("yaw", Math.round(p[3]));
        } else if (!o.has("pos")) {
            JsonArray pos = new JsonArray();
            pos.add(0); pos.add(64); pos.add(0);
            o.add("pos", pos);
        }
    }

    private void copy() {
        JsonObject c = s().current();
        if (c == null) return;
        String id = newId.trim();
        if (id.isEmpty()) {
            int n = 2;
            while (s().get(s().selected + "-" + n) != null) n++;
            id = s().selected + "-" + n;
        }
        if (!id.matches("[a-z0-9_\\-]{1,48}") || s().get(id) != null) { note("Копия: нужен свободный id"); return; }
        JsonObject o = c.deepCopy();
        place(o, id);
        s().put(o);
        select(id);
        newId = "";
        note("Копия «" + id + "» перед вами (черновик)");
    }

    private void delete() {
        String id = s().selected;
        if (id == null) return;
        if (System.currentTimeMillis() - confirmDelAt > 3000) { confirmDelAt = System.currentTimeMillis(); return; }
        confirmDelAt = 0;
        if (s().server.containsKey(id)) host.delete(id);
        s().deleted(id);
        note("«" + id + "» удалена");
    }

    // ═════════════════════════════ середина ═════════════════════════════

    private void midPane(int x0, int y0, int x1, int y1) {
        frame(x0, y0, x1, y1);
        String[] tabs = {"Свойства", "Строки", "Шаблоны"};
        int tx = x0 + 5;
        for (int i = 0; i < tabs.length; i++) {
            int tw = g.width(tabs[i]) + 14, fi = i;
            boolean on = tab == i;
            g.fill(tx, y0 + 4, tx + tw, y0 + 17, on ? 0xFF173250 : BTN);
            if (on) g.fill(tx, y0 + 16, tx + tw, y0 + 17, AMBER);
            g.text(tabs[i], tx + 7, y0 + 7, on ? AMBER : TEXT);
            hit(tx, y0 + 4, tx + tw, y0 + 17, () -> { tab = fi; midScroll = 0; unfocus(); }, null);
            tx += tw + 3;
        }
        midX0 = x0 + 2;
        midY0 = y0 + 20;
        midX1 = x1 - 2;
        midY1 = y1 - 2;
        clip((int) midX0, (int) midY0, (int) midX1, (int) midY1);
        int x = x0 + 6, w = x1 - x0 - 12;
        float y = midY0 + 3 - midScroll;
        JsonObject cur = s().current();
        float end;
        if (tab == 2) end = templates(x, y, w);
        else if (cur == null) {
            g.text("Выберите голограмму слева", x, y + 4, DIM);
            g.text("или создайте новую (id → «Создать», или из шаблона).", x, y + 16, DIM);
            end = y + 30;
        } else if (tab == 0) end = props(cur, x, y, w);
        else end = linesTab(cur, x, y, w);
        float content = end + midScroll - midY0;
        midScroll = Math.max(0, Math.min(midScroll, Math.max(0, content - (midY1 - midY0) + 10)));
        unclip();
        if (content > midY1 - midY0) {                                           // полоса прокрутки
            float vis = (midY1 - midY0) / content, at = midScroll / content;
            g.fill(x1 - 4, midY0 + at * (midY1 - midY0), x1 - 2, midY0 + (at + vis) * (midY1 - midY0), 0x808FD8FF);
        }
    }

    // ───────────────────────────── свойства ─────────────────────────────

    private float props(JsonObject c, int x, float y, int w) {
        int lw = 62;
        y = section("Положение", x, y, w);
        JsonArray pos = c.getAsJsonArray("pos");
        String[] ax = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            int fi = i;
            g.text(ax[i], x, y + 3, LIGHT);
            numField("pos" + i, x + 10, y, 64, pos.get(i).getAsDouble(), -3e7, 3e7, v -> edit("pos" + fi, d -> setPos(d, fi, v)));
            stepper(x + 78, y, pos.get(i).getAsDouble(), new double[]{-1, -0.1, 0.1, 1}, v -> edit("pos" + fi, d -> setPos(d, fi, v)));
            y += RH + 1;
        }
        int bw = (w - 6) / 3;
        button(x, y, bw, 13, "Сюда", true, () -> edit(null, d -> {
            double[] p = host.player();
            if (p == null) return;
            double yaw = Math.toRadians(p[3]);
            for (int i = 0; i < 3; i++) setPos(d, i, round(new double[]{p[0] - Math.sin(yaw) * 2.5, p[1], p[2] + Math.cos(yaw) * 2.5}[i], 0.01));
            d.addProperty("yaw", Math.round(p[3]));
        }));
        button(x + bw + 3, y, bw, 13, "К блоку", host.blockTarget() != null, () -> edit(null, d -> {
            double[] b = host.blockTarget();
            if (b == null) return;
            setPos(d, 0, round(b[0] + b[3] * 0.06, 0.01));
            setPos(d, 1, round(b[1] + b[4] * 0.06 + (b[4] > 0.5 ? 0.6 : 0), 0.01));
            setPos(d, 2, round(b[2] + b[5] * 0.06, 0.01));
            if (Math.abs(b[4]) < 0.5) {                                          // на стене — лицом от стены, фиксированно
                d.addProperty("yaw", Math.round(Math.toDegrees(Math.atan2(b[3], -b[5]))));
                d.addProperty("pitch", 0);
                d.addProperty("facing", "fixed");
            }
        }));
        button(x + 2 * (bw + 3), y, bw, 13, "Двигать ✥", true, () -> host.move(s().selected));
        y += RH + 4;

        y = section("Вид", x, y, w);
        g.text("Поворот", x, y + 3, LIGHT);
        String fc = str(c, "facing", "billboard_y");
        choice(x + lw, y, w - lw, fc, new String[]{"fixed", "billboard_y", "billboard"},
                new String[]{"фиксированный", "к игроку по Y", "к игроку полностью"}, v -> edit(null, d -> d.addProperty("facing", v)));
        y += RH + 1;
        g.text("Курс", x, y + 3, LIGHT);
        numField("yaw", x + lw, y, 46, num(c, "yaw", 0), -360, 360, v -> edit("yaw", d -> d.addProperty("yaw", v)));
        stepper(x + lw + 50, y, num(c, "yaw", 0), new double[]{-15, -1, 1, 15}, v -> edit("yaw", d -> d.addProperty("yaw", wrapDeg(v))));
        y += RH + 1;
        g.text("Наклон", x, y + 3, LIGHT);
        numField("pitch", x + lw, y, 46, num(c, "pitch", 0), -90, 90, v -> edit("pitch", d -> d.addProperty("pitch", v)));
        stepper(x + lw + 50, y, num(c, "pitch", 0), new double[]{-15, -1, 1, 15}, v -> edit("pitch", d -> d.addProperty("pitch", Math.max(-90, Math.min(90, v)))));
        y += RH + 1;
        y = numRow(c, "Масштаб", "scale", 1, 0.1, 10, new double[]{-0.1, 0.1}, x, y, lw);
        g.text("Тема", x, y + 3, LIGHT);
        choice(x + lw, y, w - lw, str(c, "theme", "space"), new String[]{"space", "amber", "danger", "green"},
                new String[]{"space — космос", "amber — предупреждение", "danger — опасность", "green — зелёная"}, v -> edit(null, d -> d.addProperty("theme", v)));
        y += RH + 1;
        y = numRow(c, "Ширина", "width", 0, 0, 32, new double[]{-0.25, 0.25}, x, y, lw);
        g.text("0 — авто", x + lw + 126, y - RH + 3, DIM);
        y = numRow(c, "Отступ", "padding", 0.12, 0, 2, new double[]{-0.02, 0.02}, x, y, lw);
        y = numRow(c, "Прозрачн.", "opacity", 0.78, 0, 1, new double[]{-0.05, 0.05}, x, y, lw);
        int tw = (w - 6) / 3;
        toggle(x, y, tw, "Фон", bool(c, "background", true), v -> edit(null, d -> d.addProperty("background", v)));
        toggle(x + tw + 3, y, tw, "Рамка", bool(c, "frame", true), v -> edit(null, d -> d.addProperty("frame", v)));
        toggle(x + 2 * (tw + 3), y, tw, "Парение", bool(c, "hover", true), v -> edit(null, d -> d.addProperty("hover", v)));
        y += RH + 4;

        y = section("Кому и откуда видно", x, y, w);
        y = numRow(c, "Дальность", "range", 32, 2, 256, new double[]{-8, 8}, x, y, lw);
        g.text("Видимость", x, y + 3, LIGHT);
        choice(x + lw, y, w - lw, str(c, "view", "all"), new String[]{"all", "op", "perm"},
                new String[]{"все", "только OP", "право holo.view." + s().selected}, v -> edit(null, d -> d.addProperty("view", v)));
        y += RH + 1;
        g.text("Карта", x, y + 3, LIGHT);
        List<String> maps = new ArrayList<>();
        for (JsonElement e : arr(host.info(), "maps")) maps.add(e.getAsString());
        if (!maps.contains(host.map())) maps.add(host.map());
        choice(x + lw, y, w - lw, str(c, "map", "world"), maps.toArray(String[]::new), maps.toArray(String[]::new),
                v -> edit(null, d -> d.addProperty("map", v)));
        y += RH + 4;

        y = section("Страницы", x, y, w);
        y = pagesRow(c, x, y, w, true);
        y += 3;
        y = section("Клик по панели (мимо элементов)", x, y, w);
        y = actions(c, null, x, y, w);
        return y + 6;
    }

    private float numRow(JsonObject c, String label, String key, double def, double min, double max, double[] steps, int x, float y, int lw) {
        g.text(label, x, y + 3, LIGHT);
        numField(key, x + lw, y, 46, num(c, key, def), min, max, v -> edit(key, d -> d.addProperty(key, v)));
        stepper(x + lw + 50, y, num(c, key, def), steps, v -> edit(key, d -> d.addProperty(key, Math.max(min, Math.min(max, round(v, 0.001))))));
        return y + RH + 1;
    }

    private float pagesRow(JsonObject c, int x, float y, int w, boolean full) {
        int n = pages(c).size();
        g.text("Стр. " + (page + 1) + " / " + n, x, y + 3, AMBER);
        int bx = x + 52;
        button(bx, y, 14, 13, "‹", page > 0, () -> { page--; selLine = selElem = -1; });
        button(bx + 16, y, 14, 13, "›", page < n - 1, () -> { page++; selLine = selElem = -1; });
        bx += 34;
        button(bx, y, 40, 13, "+ стр.", n < 16, () -> edit(null, d -> {
            JsonObject p = new JsonObject();
            p.add("lines", new JsonArray());
            pages(d).add(p);
            page = pages(d).size() - 1;
            selLine = selElem = -1;
        }));
        bx += 42;
        if (full) {
            button(bx, y, 40, 13, "− стр.", n > 1, () -> edit(null, d -> {
                pages(d).remove(page);
                page = Math.max(0, page - 1);
                selLine = selElem = -1;
            }));
            bx += 42;
            button(bx, y, 22, 13, "←", page > 0, () -> edit(null, d -> { swap(pages(d), page, page - 1); page--; }));
            button(bx + 24, y, 22, 13, "→", page < n - 1, () -> edit(null, d -> { swap(pages(d), page, page + 1); page++; }));
        }
        return y + RH + 1;
    }

    // ───────────────────────────── строки ─────────────────────────────

    private float linesTab(JsonObject c, int x, float y, int w) {
        y = pagesRow(c, x, y, w, false);
        // добавить элемент
        float bx = x;
        g.text("Добавить:", x, y + 3, DIM);
        bx += g.width("Добавить:") + 4;
        for (int i = 0; i < TYPES.length; i++) {
            int bw = g.width(TYPE_NAMES[i]) + 10;
            if (bx + bw > x + w) { bx = x; y += 15; }
            String t = TYPES[i];
            button(bx, y, bw, 13, TYPE_NAMES[i], true, () -> addElement(t));
            bx += bw + 2;
        }
        y += 15;
        toggle(x, y, 92, "В ряд", inRow, v -> inRow = v);
        g.text(inRow ? "— в выбранную строку" : "— новой строкой после выбранной", x + 96, y + 3, DIM);
        y += RH + 3;

        JsonArray ls = lines(c, page);
        linesY0 = y;
        linesCount = ls.size();
        if (ls.isEmpty()) { g.text("Строк нет — добавьте элемент кнопками выше", x, y + 2, DIM); y += RH; }
        for (int i = 0; i < ls.size(); i++) {
            JsonArray row = row(ls, i);
            boolean selRow = i == selLine;
            g.fill(x, y, x + w, y + 13, selRow ? 0xFF14243A : 0xFF0C1522);
            int fi = i;
            // ручка перетаскивания
            boolean hotGrip = in(x, y, x + 10, y + 13);
            g.text("≡", x + 2, y + 2, hotGrip || dragLine == i ? AMBER : DIM);
            hit(x, y, x + 10, y + 13, () -> { dragLine = fi; selLine = fi; selElem = Math.min(Math.max(0, selElem), row(lines(s().current(), page), fi).size() - 1); }, null);
            g.text(String.valueOf(i + 1), x + 11, y + 2, DIM);
            float cx = x + 22;
            for (int j = 0; j < row.size(); j++) {
                JsonObject e = row.get(j).getAsJsonObject();
                String label = chip(e);
                int cw = Math.min(g.width(label) + 8, (int) (x + w - cx - 2));
                if (cw < 12) break;
                boolean sel = selRow && j == selElem;
                g.fill(cx, y + 1, cx + cw, y + 12, sel ? 0xFF2A4A70 : BTN);
                if (sel) g.fill(cx, y + 11, cx + cw, y + 12, AMBER);
                g.text(trim(label, cw - 6), cx + 4, y + 3, sel ? AMBER : TEXT);
                int fj = j;
                hit(cx, y + 1, cx + cw, y + 12, () -> { selLine = fi; selElem = fj; unfocus(); }, null);
                cx += cw + 2;
            }
            y += 14;
        }
        if (dragLine >= 0) {                                                    // куда упадёт строка
            int to = dropIndex();
            float ly = linesY0 + to * 14 - 1;
            g.fill(x, ly, x + w, ly + 1.5f, AMBER);
        }
        y += 2;
        JsonObject sel = selLine >= 0 && selElem >= 0 ? row(ls, selLine).get(selElem).getAsJsonObject() : null;
        if (sel == null) return y + 4;
        int bw = 30;
        button(x, y, bw, 13, "↑", selLine > 0, () -> edit(null, d -> { swap(lines(d, page), selLine, selLine - 1); selLine--; }));
        button(x + 32, y, bw, 13, "↓", selLine < ls.size() - 1, () -> edit(null, d -> { swap(lines(d, page), selLine, selLine + 1); selLine++; }));
        button(x + 64, y, bw, 13, "◂", selElem > 0, () -> edit(null, d -> { swap(row(lines(d, page), selLine), selElem, selElem - 1); selElem--; }));
        button(x + 96, y, bw, 13, "▸", selElem < row(ls, selLine).size() - 1, () -> edit(null, d -> { swap(row(lines(d, page), selLine), selElem, selElem + 1); selElem++; }));
        button(x + 128, y, 44, 13, "Дубль", true, () -> edit(null, d -> {
            JsonArray r = row(lines(d, page), selLine);
            if (r.size() >= 8) return;
            insert(r, selElem + 1, r.get(selElem).deepCopy());
            selElem++;
        }));
        button(x + 174, y, 50, 13, "Удалить", true, this::deleteElement);
        y += RH + 4;
        return element(sel, x, y, w);
    }

    private int dropIndex() {
        return clamp(Math.round((my - linesY0) / 14f), 0, linesCount);
    }

    static String chip(JsonObject e) {
        String t = str(e, "type", "text");
        return switch (t) {
            case "text" -> "T " + Mini.plain(str(e, "text", ""));
            case "item" -> "◆ " + str(e, "item", "?");
            case "image" -> "▣ " + str(e, "image", "?");
            case "bar" -> "▬ " + Mini.plain(str(e, "label", str(e, "value", "")));
            case "button" -> "[" + Mini.plain(str(e, "label", "")) + "]";
            case "separator" -> "───";
            case "spacer" -> "␣ отступ";
            default -> t;
        };
    }

    void addElement(String type) {
        JsonObject e = switch (type) {
            case "text" -> Session.parse("{\"type\":\"text\",\"text\":\"Текст\"}");
            case "item" -> Session.parse("{\"type\":\"item\",\"item\":\"" + (host.handItem() == null ? "minecraft:diamond" : host.handItem()) + "\"}");
            case "image" -> {
                JsonArray im = arr(host.info(), "images");
                yield Session.parse("{\"type\":\"image\",\"image\":\"" + (im.isEmpty() ? "" : im.get(0).getAsString()) + "\",\"w\":1}");
            }
            case "bar" -> Session.parse("{\"type\":\"bar\",\"value\":\"0.5\",\"label\":\"Шкала\"}");
            case "button" -> Session.parse("{\"type\":\"button\",\"label\":\"Кнопка\",\"left\":[{\"type\":\"msg\",\"value\":\"<#8FD8FF>Нажато!\"}]}");
            case "spacer" -> Session.parse("{\"type\":\"spacer\",\"h\":0.15}");
            default -> Session.parse("{\"type\":\"" + type + "\"}");
        };
        edit(null, d -> {
            JsonArray ls = lines(d, page);
            if (inRow && selLine >= 0 && selLine < ls.size() && row(ls, selLine).size() < 8) {
                JsonArray r = row(ls, selLine);
                insert(r, selElem + 1, e);
                selElem++;
            } else {
                if (ls.size() >= 64) return;
                JsonArray r = new JsonArray();
                r.add(e);
                int at = selLine < 0 ? ls.size() : selLine + 1;
                insert(ls, at, r);
                selLine = at;
                selElem = 0;
            }
        });
    }

    void deleteElement() {
        if (selLine < 0 || selElem < 0) return;
        edit(null, d -> {
            JsonArray ls = lines(d, page);
            JsonArray r = row(ls, selLine);
            r.remove(selElem);
            if (r.isEmpty()) {
                ls.remove(selLine);
                selLine = Math.min(selLine, ls.size() - 1);
                selElem = selLine < 0 ? -1 : 0;
            } else selElem = Math.min(selElem, r.size() - 1);
        });
    }

    /** Поля выбранного элемента по типу + общее (подсказка, условие, задержка, действия). */
    private float element(JsonObject e, int x, float y, int w) {
        String t = str(e, "type", "text");
        int lw = 62;
        y = section("Элемент: " + TYPE_NAMES[Math.max(0, List.of(TYPES).indexOf(t))], x, y, w);
        switch (t) {
            case "text" -> {
                g.text("Текст", x, y + 3, LIGHT);
                miniRow("el.text", "text", e, x + lw, y, w - lw);
                y += RH + 1;
                y = elNum(e, "Размер", "size", 1, 0.5, 4, new double[]{-0.1, 0.1}, x, y, lw);
                g.text("Выравн.", x, y + 3, LIGHT);
                choice(x + lw, y, 100, str(e, "align", "center"), new String[]{"left", "center", "right"},
                        new String[]{"влево", "по центру", "вправо"}, v -> elEdit(null, o -> o.addProperty("align", v)));
                y += RH + 1;
            }
            case "item" -> {
                g.text("Предмет", x, y + 3, LIGHT);
                int fw = w - lw - 52;
                field("el.item", x + lw, y, fw, str(e, "item", ""), 0, v -> elEdit("el.item", o -> o.addProperty("item", v.trim())), "minecraft:diamond / hoka:ruby");
                button(x + lw + fw + 2, y, 50, 13, "Из руки", host.handItem() != null, () -> elEdit(null, o -> o.addProperty("item", host.handItem())));
                if ("el.item".equals(focus)) {                                  // автодополнение
                    float sy = y + RH;
                    for (String sug : host.itemSuggest(buf.toString())) {
                        boolean hot = in(x + lw, sy, x + lw + fw, sy + 11);
                        g.fill(x + lw, sy, x + lw + fw, sy + 11, hot ? BTN_HOT : 0xF0101A2A);
                        g.text(sug, x + lw + 4, sy + 2, sug.startsWith("hoka:") ? AMBER : TEXT);
                        hit(x + lw, sy, x + lw + fw, sy + 11, () -> { elEdit(null, o -> o.addProperty("item", sug)); unfocus(); }, null);
                        sy += 11;
                    }
                    y = sy - RH;
                }
                y += RH + 1;
                toggle(x + lw, y, 90, "Вращение", bool(e, "spin", true), v -> elEdit(null, o -> o.addProperty("spin", v)));
                y += RH + 1;
                y = elNum(e, "Размер", "size", 1, 0.5, 4, new double[]{-0.25, 0.25}, x, y, lw);
            }
            case "image" -> {
                g.text("Картинка", x, y + 3, LIGHT);
                List<String> im = new ArrayList<>();
                for (JsonElement je : arr(host.info(), "images")) im.add(je.getAsString());
                String cur = str(e, "image", "");
                if (im.isEmpty()) g.text("нет файлов в module/holo/images", x + lw, y + 3, DIM);
                else choice(x + lw, y, w - lw, cur, im.toArray(String[]::new), im.toArray(String[]::new), v -> elEdit(null, o -> o.addProperty("image", v)));
                y += RH + 1;
                y = elNum(e, "Ширина", "w", 1, 0, 32, new double[]{-0.25, 0.25}, x, y, lw);
                y = elNum(e, "Высота", "h", 0, 0, 32, new double[]{-0.25, 0.25}, x, y, lw);
                g.text("высота 0 — по пропорциям картинки", x + lw, y, DIM);
                y += 11;
            }
            case "bar" -> {
                g.text("Значение", x, y + 3, LIGHT);
                miniRow("el.value", "value", e, x + lw, y, w - lw);
                y += RH + 1;
                g.text("0..1, число до «Максимум» или {плейсхолдер}", x + lw, y, DIM);
                y += 11;
                y = elNum(e, "Максимум", "max", 1, 1e-6, 1e12, new double[]{-1, 1}, x, y, lw);
                g.text("Подпись", x, y + 3, LIGHT);
                miniRow("el.label", "label", e, x + lw, y, w - lw);
                y += RH + 1;
                y = colorRow(e, x, y, w, lw);
                y = elNum(e, "Ширина", "w", 2, 0, 32, new double[]{-0.25, 0.25}, x, y, lw);
            }
            case "button" -> {
                g.text("Подпись", x, y + 3, LIGHT);
                miniRow("el.label", "label", e, x + lw, y, w - lw);
                y += RH + 1;
                y = colorRow(e, x, y, w, lw);
                y = elNum(e, "Размер", "size", 1, 0.5, 4, new double[]{-0.1, 0.1}, x, y, lw);
                y = elNum(e, "Ширина", "w", 0, 0, 32, new double[]{-0.25, 0.25}, x, y, lw);
            }
            case "spacer" -> y = elNum(e, "Высота", "h", 0.15, 0, 32, new double[]{-0.05, 0.05}, x, y, lw);
            default -> { }
        }
        if (t.equals("separator") || t.equals("spacer")) return y + 6;
        y += 3;
        y = section("Нажатие", x, y, w);
        g.text("Подсказка", x, y + 3, LIGHT);
        field("el.hint", x + lw, y, w - lw, str(e, "hint", ""), 1, v -> elEdit("el.hint", o -> { if (v.isEmpty()) o.remove("hint"); else o.addProperty("hint", v); }), "при наведении");
        y += RH + 1;
        g.text("Условие", x, y + 3, LIGHT);
        String cond = str(e, "cond", "all");
        String cmode = cond.startsWith("perm:") ? "perm" : cond;
        choice(x + lw, y, 80, cmode, new String[]{"all", "op", "perm"}, new String[]{"все", "только OP", "право…"},
                v -> elEdit(null, o -> o.addProperty("cond", v.equals("perm") ? "perm:holo.use" : v)));
        if (cmode.equals("perm"))
            field("el.perm", x + lw + 84, y, w - lw - 84, cond.substring(5), 0, v -> elEdit("el.perm", o -> o.addProperty("cond", "perm:" + v.trim().toLowerCase(Locale.ROOT))), "узел права");
        y += RH + 1;
        g.text("Задержка", x, y + 3, LIGHT);
        numField("el.cd", x + lw, y, 46, num(e, "cooldown", -1), -1, 72000, v -> elEdit("el.cd", o -> o.addProperty("cooldown", Math.round(v))));
        g.text("тиков (−1 — по умолчанию)", x + lw + 50, y + 3, DIM);
        y += RH + 3;
        return actions(c(), e, x, y, w) + 6;
    }

    private JsonObject c() { return s().current(); }

    private float elNum(JsonObject e, String label, String key, double def, double min, double max, double[] steps, int x, float y, int lw) {
        g.text(label, x, y + 3, LIGHT);
        numField("el." + key, x + lw, y, 46, num(e, key, def), min, max, v -> elEdit("el." + key, o -> o.addProperty(key, v)));
        stepper(x + lw + 50, y, num(e, key, def), steps, v -> elEdit("el." + key, o -> o.addProperty(key, Math.max(min, Math.min(max, round(v, 0.001))))));
        return y + RH + 1;
    }

    private float colorRow(JsonObject e, int x, float y, int w, int lw) {
        g.text("Цвет", x, y + 3, LIGHT);
        String col = str(e, "color", "");
        int rgb = Holo.rgb(col, 0x8FD8FF);
        g.fill(x + lw, y + 1, x + lw + 11, y + 12, 0xFF000000 | rgb);
        field("el.color", x + lw + 14, y, 60, col, 0, v -> elEdit("el.color", o -> {
            if (v.isBlank()) o.remove("color");
            else if (v.trim().matches("#?[0-9a-fA-F]{6}")) o.addProperty("color", v.trim().startsWith("#") ? v.trim() : "#" + v.trim());
        }), "из темы");
        int[] pal = {0x8FD8FF, 0xFFC46B, 0xD9E2EE, 0xFF5A6E, 0x6BFFAE, 0xB98CFF};
        float px = x + lw + 78;
        for (int c : pal) {
            g.fill(px, y + 2, px + 10, y + 11, 0xFF000000 | c);
            hit(px, y + 2, px + 10, y + 11, () -> elEdit(null, o -> o.addProperty("color", String.format("#%06X", c))), null);
            px += 12;
        }
        return y + RH + 1;
    }

    /** Поле MiniMessage с подсветкой + «{…}» (плейсхолдер) и «<>» (тег) — вставка в курсор. */
    private void miniRow(String key, String prop, JsonObject e, int x, float y, int w) {
        int fw = w - 38;
        field(key, x, y, fw, str(e, prop, ""), 1, v -> elEdit(key, o -> o.addProperty(prop, v)), "");
        button(x + fw + 2, y, 16, 13, "{}", true, () -> {
            focusTouched = true;
            List<String> l = new ArrayList<>();
            List<Runnable> r = new ArrayList<>();
            for (JsonElement p : arr(host.info(), "placeholders")) {
                JsonObject po = p.getAsJsonObject();
                String n = po.get("name").getAsString();
                l.add("{" + n + "}  · " + po.get("period").getAsInt() + " т");
                r.add(() -> insertInto(key, prop, "{" + n + "}"));
            }
            if (l.isEmpty()) for (String n : new String[]{"player", "online", "time", "map", "x", "y", "z"}) {
                l.add("{" + n + "}");
                r.add(() -> insertInto(key, prop, "{" + n + "}"));
            }
            open(x + fw - 100, y + 13, 118, l, r);
        });
        button(x + fw + 19, y, 19, 13, "<>", true, () -> {
            focusTouched = true;
            List<Runnable> r = new ArrayList<>();
            for (String t : TAGS) r.add(() -> insertInto(key, prop, t));
            open(x + fw - 140, y + 13, 158, List.of(TAGS), r);
        });
    }

    /** Вставить в поле: в курсор, если поле в фокусе, иначе — в конец значения. */
    private void insertInto(String key, String prop, String s) {
        if (key.equals(focus)) {
            buf.insert(cur, s);
            cur += s.length();
            if (onEdit != null) onEdit.accept(buf.toString());
        } else elEdit(null, o -> o.addProperty(prop, str(o, prop, "") + s));
    }

    // ───────────────────────────── действия ─────────────────────────────

    /** Конструктор действий: ЛКМ/ПКМ, список (тип из списка, значение, порядок, удалить), «+ действие». e = null — панель. */
    private float actions(JsonObject holo, JsonObject e, int x, float y, int w) {
        String side = actSide == 0 ? "left" : "right";
        String[] sides = {"ЛКМ", "ПКМ"};
        for (int i = 0; i < 2; i++) {
            int fi = i;
            JsonObject src = e != null ? e : holo;
            int n = arr(src, i == 0 ? "left" : "right").size();
            boolean on = actSide == i;
            String lab = sides[i] + (n > 0 ? " (" + n + ")" : "");
            g.fill(x + i * 62, y, x + i * 62 + 60, y + 13, on ? 0xFF173250 : BTN);
            if (on) g.fill(x + i * 62, y + 12, x + i * 62 + 60, y + 13, AMBER);
            g.text(lab, x + i * 62 + 6, y + 3, on ? AMBER : TEXT);
            hit(x + i * 62, y, x + i * 62 + 60, y + 13, () -> { actSide = fi; unfocus(); }, null);
        }
        y += RH + 2;
        JsonObject src = e != null ? e : holo;
        JsonArray as = arr(src, side);
        for (int i = 0; i < as.size(); i++) {
            JsonObject a = as.get(i).getAsJsonObject();
            String type = str(a, "type", "cmd");
            int ti = Math.max(0, List.of(ACTS).indexOf(type)), fi = i;
            choice(x, y, 96, type, ACTS, ACT_NAMES, v -> actEdit(e, side, null, arr -> arr.get(fi).getAsJsonObject().addProperty("type", v)));
            if (type.equals("console")) g.fill(x, y + 12, x + 96, y + 13, AMBER);
            int fw = w - 96 - 4 - 50;
            field("act." + side + "." + i, x + 98, y, fw, str(a, "value", ""), type.equals("msg") ? 1 : 0,
                    v -> actEdit(e, side, "act." + side + "." + fi, arr -> arr.get(fi).getAsJsonObject().addProperty("value", v)), ACT_HINTS[ti]);
            float bx = x + 98 + fw + 2;
            button(bx, y, 15, 13, "↑", i > 0, () -> actEdit(e, side, null, arr -> swap(arr, fi, fi - 1)));
            button(bx + 16, y, 15, 13, "↓", i < as.size() - 1, () -> actEdit(e, side, null, arr -> swap(arr, fi, fi + 1)));
            button(bx + 32, y, 15, 13, "✕", true, () -> actEdit(e, side, null, arr -> arr.remove(fi)));
            y += RH + 1;
        }
        if (as.isEmpty()) { g.text("Нет действий — нажатие ничего не делает", x, y + 2, DIM); y += 12; }
        button(x, y, 90, 13, "+ действие", as.size() < 16, () -> actEdit(e, side, null, arr -> {
            JsonObject a = new JsonObject();
            a.addProperty("type", "msg");
            a.addProperty("value", "");
            arr.add(a);
        }));
        return y + RH + 1;
    }

    /** Правка списка действий: элемента (e ≠ null — выбранный) или панели. */
    private void actEdit(JsonObject e, String side, String group, Consumer<JsonArray> f) {
        if (e != null) elEdit(group, o -> f.accept(arrMut(o, side)));
        else edit(group, d -> f.accept(arrMut(d, side)));
    }

    // ───────────────────────────── шаблоны ─────────────────────────────

    private float templates(int x, float y, int w) {
        y = section("Создать из шаблона", x, y, w);
        g.text("id новой", x, y + 3, LIGHT);
        field("tplid", x + 62, y, w - 62, tplId, 0, v -> tplId = v.toLowerCase(Locale.ROOT), "например, menu-1");
        y += RH + 3;
        JsonObject t = host.info().has("templates") ? host.info().getAsJsonObject("templates") : new JsonObject();
        if (t.isEmpty()) { g.text("Шаблонов нет (придут с сервера)", x, y, DIM); y += 12; }
        for (var en : t.entrySet()) {
            String name = en.getKey();
            g.fill(x, y, x + w, y + 13, 0xFF0C1522);
            g.text(trim(name, w - 120), x + 4, y + 3, TEXT);
            button(x + w - 112, y, 60, 13, "Создать", true, () -> fromTemplate(en.getValue().getAsJsonObject()));
            boolean conf = name.equals(confirmTpl);
            button(x + w - 50, y, 50, 13, conf ? "Точно?" : "Удалить", true, () -> {
                if (name.equals(confirmTpl)) { host.deleteTemplate(name); confirmTpl = null; }
                else confirmTpl = name;
            });
            y += 15;
        }
        y += 4;
        y = section("Сохранить выбранную как шаблон", x, y, w);
        field("tplname", x, y, w - 84, tplName, 0, v -> tplName = v, "имя шаблона");
        button(x + w - 82, y, 82, 13, "Сохранить", s().current() != null && !tplName.isBlank(), () -> {
            host.saveTemplate(tplName.trim(), s().current());
            note("Шаблон «" + tplName.trim() + "» отправлен");
            tplName = "";
        });
        return y + RH + 6;
    }

    private void fromTemplate(JsonObject tpl) {
        String id = tplId.trim();
        if (!id.matches("[a-z0-9_\\-]{1,48}")) { note("Введите id новой голограммы (a-z 0-9 _ -)"); return; }
        if (s().get(id) != null) { note("«" + id + "» уже есть"); return; }
        JsonObject o = tpl.deepCopy();
        place(o, id);
        o.remove("creator_op");
        s().put(o);
        select(id);
        tab = 1;
        tplId = "";
        note("«" + id + "» из шаблона (черновик)");
    }

    // ═════════════════════════════ превью ═════════════════════════════

    private void previewPane(int x0, int y0, int x1, int y1) {
        frame(x0, y0, x1, y1);
        g.text("Предпросмотр", x0 + 6, y0 + 5, LIGHT);
        JsonObject c = s().current();
        if (c == null) { g.text("—", x0 + 6, y0 + 20, DIM); return; }
        g.text("стр. " + (page + 1), x1 - 6 - g.width("стр. " + (page + 1)), y0 + 5, DIM);
        Holo h;
        try { h = Holo.parse(c); } catch (RuntimeException e) { g.text("ошибка: " + e.getMessage(), x0 + 6, y0 + 20, RED); return; }
        if (prevPanel == null || prevVersion != s().version || prevPage != page || !s().selected.equals(prevId)) {
            prevPanel = Layout.layout(h, page, host.values(), host.metrics(), host.themes().getOrDefault(h.theme, Holo.Theme.SPACE).text());
            prevVersion = s().version;
            prevPage = page;
            prevId = s().selected;
        }
        Layout.Panel p = prevPanel;
        float aw = x1 - x0 - 16, ah = y1 - y0 - 64;
        float sc = Math.min(1.6f, Math.min(aw / Math.max(1, p.w()), ah / Math.max(1, p.h())));
        prevS = sc;
        prevX = x0 + 8 + (aw - p.w() * sc) / 2;
        prevY = y0 + 18 + (ah - p.h() * sc) / 2;
        g.fill(x0 + 3, y0 + 16, x1 - 3, y1 - 44, 0xFF02040A);                   // «космос» за панелью
        for (int i = 0; i < 24; i++) {
            float sx = x0 + 6 + Paint.hash(5, i) * (x1 - x0 - 12), sy = y0 + 18 + Paint.hash(9, i) * (y1 - y0 - 64);
            g.fill(sx, sy, sx + 1, sy + 1, 0x60FFFFFF);
        }
        Paint.State st = new Paint.State();
        st.t = (System.currentTimeMillis() % 100000) / 1000f;
        st.seed = h.id.hashCode();
        for (Layout.Box b : p.boxes()) if (b.line == selLine && b.elem == selElem && tab == 1) st.hovered = b;
        g.push(prevX, prevY, sc);
        Paint.draw(h, p, host.themes().getOrDefault(h.theme, Holo.Theme.SPACE), st, new GuiPainter(g));
        g.pop();
        hit(prevX, prevY, prevX + p.w() * sc, prevY + p.h() * sc, () -> {
            Layout.Box b = p.at((mx - prevX) / prevS, (my - prevY) / prevS);
            if (b != null) { tab = 1; selLine = b.line; selElem = b.elem; unfocus(); }
        }, null);
        float iy = y1 - 40;
        JsonArray pos = c.getAsJsonArray("pos");
        g.text(String.format(Locale.ROOT, "%s · %.2f %.2f %.2f", str(c, "map", "world"),
                pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble()), x0 + 6, iy, DIM);
        g.text(String.format(Locale.ROOT, "%.1f × %.1f блока · стр. %d/%d", p.w() * h.scale / Layout.PX_PER_BLOCK,
                p.h() * h.scale / Layout.PX_PER_BLOCK, page + 1, h.pages.size()), x0 + 6, iy + 11, DIM);
        boolean dirty = s().dirty.contains(s().selected);
        g.text(dirty ? "● черновик — виден только вам" : "✓ как на сервере", x0 + 6, iy + 22, dirty ? AMBER : GREEN);
    }

    /** Голограмма в окне: заливки — прямоугольниками, кубики — квадрат с тенью, сканлинии — тонкими полосами. */
    static final class GuiPainter implements Paint.Painter {
        private final Gui g;

        GuiPainter(Gui g) { this.g = g; }

        public void rect(float x0, float y0, float x1, float y1, float z, int argb) { g.fill(x0, y0, x1, y1, argb); }

        public void glow(float x0, float y0, float x1, float y1, float z, int argb) { g.fill(x0, y0, x1, y1, argb); }

        public void cube(float cx, float cy, float cz, float s, int argb) {
            float h = s / 2;
            int a = argb >>> 24;
            g.fill(cx - h + s * 0.18f, cy - h + s * 0.18f, cx + h + s * 0.18f, cy + h + s * 0.18f, ((a / 2) << 24) | Paint.mul(argb & 0xFFFFFF, 0.45f));
            g.fill(cx - h, cy - h, cx + h, cy + h, argb);
        }

        public void scan(float x0, float y0, float x1, float y1, float z, int argb, float phase) {
            for (float y = y0 + (phase * 4) % 4; y < y1; y += 4) g.fill(x0, y, x1, Math.min(y1, y + 0.6f), argb);
        }

        public void text(Layout.Box b, Layout.TLine line, float x, float y, float z, float scale, float alpha) { g.runs(line.runs(), x, y, scale, alpha); }

        public void item(Layout.Box b, float cx, float cy, float z, float size, float spinDeg, float alpha) {
            g.item(b.el.item, cx - size / 2, cy - size / 2, size);
        }

        public void image(String name, float x0, float y0, float x1, float y1, float z, float alpha) { g.image(name, x0, y0, x1 - x0, y1 - y0, alpha); }
    }

    // ═════════════════════════════ низ ═════════════════════════════

    private void bottomBar(int y, int w) {
        Session s = s();
        int x = 4;
        boolean dirty = !s.dirty.isEmpty();
        button(x, y, 96, 15, "Сохранить" + (s.dirty.size() > 1 ? " (" + s.dirty.size() + ")" : ""), dirty, this::save);
        if (dirty) g.fill(x, y + 14, x + 96, y + 15, AMBER);
        x += 99;
        button(x, y, 64, 15, "↶ Отмена", s.canUndo(), () -> { s.undo(); unfocus(); });
        x += 67;
        button(x, y, 64, 15, "↷ Повтор", s.canRedo(), () -> { s.redo(); unfocus(); });
        x += 67;
        toggle(x, y, 74, "Авто", s.auto, v -> { s.auto = v; if (v) s.saveAll(); });
        x += 77;
        button(x, y, 60, 15, "Закрыть", true, host::close);
        x += 66;
        String msg;
        int col;
        if (note != null && System.currentTimeMillis() - noteAt < 5000) { msg = note; col = AMBER; }
        else { msg = host.status(); col = host.statusOk() ? GREEN : RED; }
        if (msg != null && !msg.isEmpty()) g.text(trim(msg, w - x - 6), x, y + 4, col);
    }

    void save() {
        int n = s().saveAll();
        note(n == 0 ? "Нечего сохранять" : "Отправлено на сервер: " + n);
        warnedDirty = false;
    }

    void note(String s) {
        note = s;
        noteAt = System.currentTimeMillis();
    }

    // ═════════════════════════════ правки ═════════════════════════════

    private void edit(String group, Consumer<JsonObject> f) {
        s().change(group, f);
        warnedDirty = false;
    }

    /** Правка выбранного элемента. */
    private void elEdit(String group, Consumer<JsonObject> f) {
        int pg = page, ln = selLine, el = selElem;
        if (ln < 0 || el < 0) return;
        edit(group, d -> {
            JsonArray ls = lines(d, pg);
            if (ln >= ls.size()) return;
            JsonArray r = row(ls, ln);
            if (el >= r.size()) return;
            f.accept(r.get(el).getAsJsonObject());
        });
    }

    private static void setPos(JsonObject d, int i, double v) {
        JsonArray p = d.getAsJsonArray("pos");
        p.set(i, new JsonPrimitive(round(v, 0.001)));
    }

    static JsonArray pages(JsonObject o) {
        if (!o.has("pages") || !o.get("pages").isJsonArray()) o.add("pages", new JsonArray());
        JsonArray p = o.getAsJsonArray("pages");
        if (p.isEmpty()) {
            JsonObject pg = new JsonObject();
            pg.add("lines", new JsonArray());
            p.add(pg);
        }
        return p;
    }

    static JsonArray lines(JsonObject o, int page) {
        JsonArray ps = pages(o);
        JsonObject p = ps.get(Math.max(0, Math.min(page, ps.size() - 1))).getAsJsonObject();
        if (!p.has("lines")) p.add("lines", new JsonArray());
        return p.getAsJsonArray("lines");
    }

    static JsonArray row(JsonArray lines, int i) {
        JsonElement e = lines.get(i);
        if (e.isJsonArray()) return e.getAsJsonArray();
        JsonArray a = new JsonArray();
        a.add(e);
        lines.set(i, a);
        return a;
    }

    static void swap(JsonArray a, int i, int j) {
        if (i < 0 || j < 0 || i >= a.size() || j >= a.size()) return;
        JsonElement t = a.get(i);
        a.set(i, a.get(j));
        a.set(j, t);
    }

    static void insert(JsonArray a, int at, JsonElement e) {
        List<JsonElement> l = new ArrayList<>(a.asList());
        l.add(Math.max(0, Math.min(at, l.size())), e);
        while (!a.isEmpty()) a.remove(a.size() - 1);
        for (JsonElement x : l) a.add(x);
    }

    /** Перенести строку from в позицию to (между строками; to — индекс «до перемещения»). */
    static void moveLine(JsonArray ls, int from, int to) {
        if (from < 0 || from >= ls.size()) return;
        List<JsonElement> l = new ArrayList<>(ls.asList());
        JsonElement e = l.remove(from);
        l.add(Math.max(0, Math.min(to > from ? to - 1 : to, l.size())), e);
        while (!ls.isEmpty()) ls.remove(ls.size() - 1);
        for (JsonElement x : l) ls.add(x);
    }

    static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    static JsonArray arrMut(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) o.add(k, new JsonArray());
        return o.getAsJsonArray(k);
    }

    static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    static double num(JsonObject o, String k, double def) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : def; } catch (RuntimeException e) { return def; }
    }

    static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
    }

    static double round(double v, double step) { return Math.round(v / step) * step; }

    static double wrapDeg(double v) { return ((v % 360) + 540) % 360 - 180; }

    static double sq(double v) { return v * v; }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.ROOT, "%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ═════════════════════════════ виджеты ═════════════════════════════

    private boolean in(float x0, float y0, float x1, float y1) {
        return popup == null && mx >= x0 && mx < x1 && my >= y0 && my < y1 && mx >= cx0 && mx < cx1 && my >= cy0 && my < cy1;
    }

    private void hit(float x0, float y0, float x1, float y1, Runnable left, Runnable right) {
        float a = Math.max(x0, cx0), b = Math.max(y0, cy0), c = Math.min(x1, cx1), d = Math.min(y1, cy1);
        if (c > a && d > b) hits.add(new Hit(a, b, c, d, left, right));
    }

    private void clip(int x0, int y0, int x1, int y1) {
        cx0 = x0; cy0 = y0; cx1 = x1; cy1 = y1;
        g.clip(x0, y0, x1, y1);
    }

    private void unclip() {
        cx0 = cy0 = 0;
        cx1 = cy1 = Integer.MAX_VALUE;
        g.unclip();
    }

    /** Панель в стиле голограмм: тёмная, по краю — кубики (уголки крупнее, у уголков — янтарь). */
    private void frame(int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, BG);
        for (int x = x0 + 6; x < x1 - 4; x += 5) {
            int c = x < x0 + 8 || x > x1 - 10 ? AMBER : ((x / 5) % 4 == 0 ? LIGHT : 0xB08FD8FF);
            g.fill(x, y0 - 1, x + 2, y0 + 1, c);
            g.fill(x, y1 - 1, x + 2, y1 + 1, c);
        }
        for (int y = y0 + 6; y < y1 - 4; y += 5) {
            int c = y < y0 + 8 || y > y1 - 10 ? AMBER : 0x908FD8FF;
            g.fill(x0 - 1, y, x0 + 1, y + 2, c);
            g.fill(x1 - 1, y, x1 + 1, y + 2, c);
        }
        for (int[] p : new int[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) g.fill(p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, LIGHT);
    }

    private float section(String title, int x, float y, int w) {
        g.text(title, x, y + 1, AMBER);
        int tw = g.width(title) + 4;
        g.fill(x + tw, y + 5, x + w, y + 6, 0x508FD8FF);
        g.fill(x + w - 2, y + 4, x + w, y + 7, AMBER);
        return y + 13;
    }

    private void button(float x, float y, float w, float h, String label, boolean enabled, Runnable click) {
        boolean hot = enabled && in(x, y, x + w, y + h);
        g.fill(x, y, x + w, y + h, hot ? BTN_HOT : BTN);
        int edge = hot ? AMBER : 0x608FD8FF;
        g.fill(x, y, x + 2, y + 1, edge);
        g.fill(x + w - 2, y, x + w, y + 1, edge);
        g.fill(x, y + h - 1, x + 2, y + h, edge);
        g.fill(x + w - 2, y + h - 1, x + w, y + h, edge);
        if (hot) g.fill(x + 2, y + h - 1, x + w - 2, y + h, 0x60FFC46B);
        String l = trim(label, (int) w - 4);
        g.text(l, x + (w - g.width(l)) / 2, y + (h - 8) / 2, enabled ? (hot ? 0xFFFFFFFF : TEXT) : 0xFF4A5566);
        if (enabled) hit(x, y, x + w, y + h, click, null);
    }

    private void toggle(float x, float y, float w, String label, boolean on, Consumer<Boolean> set) {
        button(x, y, w, 13, label + ": " + (on ? "вкл" : "выкл"), true, () -> set.accept(!on));
        g.fill(x + 3, y + 4, x + 7, y + 8, on ? GREEN : 0xFF3A4556);
    }

    /** Выбор из списка: кнопка «значение ▾» → всплывающий список. */
    private void choice(float x, float y, float w, String cur, String[] values, String[] labels, Consumer<String> set) {
        int i = List.of(values).indexOf(cur);
        String lab = i >= 0 ? labels[i] : cur;
        button(x, y, w, 13, lab + " ▾", true, () -> {
            List<Runnable> r = new ArrayList<>();
            for (String v : values) r.add(() -> set.accept(v));
            open(x, y + 13, Math.max(w, 90), List.of(labels), r);
        });
    }

    private void stepper(float x, float y, double v, double[] steps, Consumer<Double> set) {
        float bx = x;
        for (double st : steps) {
            String l = (st > 0 ? "+" : "−") + fmt(Math.abs(st)).replaceFirst("^0\\.", ".");
            int bw = g.width(l) + 6;
            button(bx, y, bw, 13, l, true, () -> set.accept(v + st));
            bx += bw + 1;
        }
    }

    private void numField(String key, float x, float y, float w, double v, double min, double max, Consumer<Double> set) {
        field(key, x, y, w, fmt(v), 2, s -> {
            try {
                double d = Double.parseDouble(s.trim().replace(',', '.'));
                if (!(d >= min && d <= max)) throw new NumberFormatException();
                invalid = false;
                set.accept(d);
            } catch (NumberFormatException e) {
                invalid = true;
            }
        }, "");
    }

    /** Поле ввода. kind: 0 — текст, 1 — MiniMessage (подсветка тегов и плейсхолдеров), 2 — число. */
    private void field(String key, float x, float y, float w, String value, int kind, Consumer<String> apply, String placeholder) {
        boolean f = key.equals(focus);
        String text = f ? buf.toString() : value == null ? "" : value;
        boolean hot = in(x, y, x + w, y + 13);
        g.fill(x, y, x + w, y + 13, FIELD);
        int border = f ? (invalid ? RED : AMBER) : hot ? 0xA08FD8FF : 0x408FD8FF;
        g.fill(x, y + 12, x + w, y + 13, border);
        g.fill(x, y, x + 1, y + 13, border);
        // прокрутка: курсор всегда виден
        int start = 0;
        if (f) while (start < cur && g.width(text.substring(start, cur)) > w - 6) start++;
        String vis = text.substring(start);
        int clipR = (int) (x + w - 2);
        g.clip(Math.max((int) x + 1, cx0), Math.max((int) y, cy0), Math.min(clipR, cx1), Math.min((int) y + 13, cy1));
        if (text.isEmpty() && !f) g.text(placeholder, x + 3, y + 3, 0xFF3F4B5C);
        else if (kind == 1) {
            float tx = x + 3;
            for (Mini.Token t : Mini.tokens(vis)) {
                g.text(t.text(), tx, y + 3, t.kind() == 1 ? 0xFF6FA8C8 : t.kind() == 2 ? AMBER : TEXT);
                tx += g.width(t.text());
            }
        } else g.text(vis, x + 3, y + 3, kind == 2 && f && invalid ? RED : TEXT);
        if (f && (System.currentTimeMillis() / 500) % 2 == 0) {
            float cxp = x + 3 + g.width(text.substring(start, cur));
            g.fill(cxp, y + 2, cxp + 1, y + 11, AMBER);
        }
        if (cx1 == Integer.MAX_VALUE) g.unclip();
        else g.clip(cx0, cy0, cx1, cy1);
        int fs = start;
        hit(x, y, x + w, y + 13, () -> {
            focusTouched = true;
            if (!key.equals(focus)) {
                focus = key;
                buf.setLength(0);
                buf.append(text);
                invalid = false;
            }
            onEdit = apply;
            // курсор — по месту клика
            String t = buf.toString();
            int c = fs;
            while (c < t.length() && x + 3 + g.width(t.substring(fs, c + 1)) <= mx + 2) c++;
            cur = Math.max(0, Math.min(c, t.length()));
        }, null);
        if (f) onEdit = apply;
    }

    private void unfocus() {
        focus = null;
        onEdit = null;
        invalid = false;
    }

    private void open(float x, float y, float w, List<String> labels, List<Runnable> picks) {
        float h = Math.min(labels.size(), 12) * 11 + 2;
        if (y + h > H - 2) y = Math.max(2, H - 2 - h);
        if (x + w > W - 2) x = W - 2 - w;
        popup = new Popup(x, y, w, labels, picks);
        popupScroll = 0;
    }

    private void drawPopup() {
        Popup p = popup;
        int n = p.labels().size(), vis = Math.min(n, 12);
        float h = vis * 11 + 2;
        g.fill(p.x(), p.y(), p.x() + p.w(), p.y() + h, 0xF8070C16);
        g.fill(p.x(), p.y(), p.x() + p.w(), p.y() + 1, AMBER);
        g.fill(p.x(), p.y() + h - 1, p.x() + p.w(), p.y() + h, 0x808FD8FF);
        int first = (int) popupScroll;
        for (int i = 0; i < vis && first + i < n; i++) {
            float yy = p.y() + 1 + i * 11;
            boolean hot = mx >= p.x() && mx < p.x() + p.w() && my >= yy && my < yy + 11;
            if (hot) g.fill(p.x() + 1, yy, p.x() + p.w() - 1, yy + 11, BTN_HOT);
            g.text(trim(p.labels().get(first + i), (int) p.w() - 8), p.x() + 4, yy + 2, hot ? AMBER : TEXT);
        }
        if (n > vis) g.fill(p.x() + p.w() - 2, p.y() + 1 + first * h / n, p.x() + p.w(), p.y() + 1 + (first + vis) * h / n, GLOW);
    }

    private String trim(String s, int w) {
        if (s == null) return "";
        if (g.width(s) <= w) return s;
        while (s.length() > 1 && g.width(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ═════════════════════════════ ввод ═════════════════════════════

    public boolean mouseDown(double x, double y, int button) {
        mx = (float) x;
        my = (float) y;
        if (popup != null) {
            Popup p = popup;
            popup = null;
            int vis = Math.min(p.labels().size(), 12);
            if (x >= p.x() && x < p.x() + p.w() && y >= p.y() && y < p.y() + vis * 11 + 2) {
                int i = (int) ((y - p.y() - 1) / 11) + (int) popupScroll;
                if (i >= 0 && i < p.picks().size()) p.picks().get(i).run();
            }
            return true;
        }
        String was = focus;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (x >= h.x0() && x < h.x1() && y >= h.y0() && y < h.y1()) {
                Runnable r = button == 1 ? h.right() : h.left();
                if (r == null) continue;
                focusTouched = false;
                r.run();
                if (!focusTouched && was != null && was.equals(focus)) unfocus();   // клик мимо поля — снять фокус
                return true;
            }
        }
        unfocus();
        return false;
    }

    public void mouseUp(double x, double y, int button) {
        if (dragLine >= 0) {
            int from = dragLine, to = dropIndex();
            dragLine = -1;
            if (to != from && to != from + 1) {
                edit(null, d -> moveLine(lines(d, page), from, to));
                selLine = to > from ? to - 1 : to;
            }
        }
    }

    public void mouseMove(double x, double y) {
        mx = (float) x;
        my = (float) y;
    }

    public boolean scroll(double x, double y, double dy) {
        if (popup != null) {
            popupScroll = (float) Math.max(0, Math.min(popup.labels().size() - 12, popupScroll - dy));
            return true;
        }
        if (x >= midX0 && x < midX1 && y >= midY0 && y < midY1) { midScroll = (float) Math.max(0, midScroll - dy * 14); return true; }
        if (y >= listY0 && y < listY1 && x < midX0) { listScroll = (float) Math.max(0, listScroll - dy * 12); return true; }
        return false;
    }

    static final int K_ESC = 256, K_ENTER = 257, K_TAB = 258, K_BACK = 259, K_DEL = 261, K_RIGHT = 262, K_LEFT = 263,
            K_HOME = 268, K_END = 269, K_KP_ENTER = 335, M_SHIFT = 1, M_CTRL = 2;

    /** Клавиша (коды GLFW). true — забрано редактором. */
    public boolean key(int key, int mods) {
        boolean ctrl = (mods & M_CTRL) != 0;
        if (ctrl && key == 'Z') { if ((mods & M_SHIFT) != 0) s().redo(); else s().undo(); unfocus(); return true; }
        if (ctrl && key == 'Y') { s().redo(); unfocus(); return true; }
        if (ctrl && key == 'S') { save(); return true; }
        if (focus != null) {
            switch (key) {
                case K_BACK -> { if (cur > 0) { buf.deleteCharAt(cur - 1); cur--; changed(); } }
                case K_DEL -> { if (cur < buf.length()) { buf.deleteCharAt(cur); changed(); } }
                case K_LEFT -> cur = Math.max(0, cur - 1);
                case K_RIGHT -> cur = Math.min(buf.length(), cur + 1);
                case K_HOME -> cur = 0;
                case K_END -> cur = buf.length();
                case K_ENTER, K_KP_ENTER, K_TAB -> unfocus();
                default -> {
                    if (ctrl && key == 'V') { typed(host.clipboard()); return true; }
                    if (ctrl && key == 'C') { host.setClipboard(buf.toString()); return true; }
                    if (ctrl && key == 'A') { cur = buf.length(); return true; }
                }
            }
            return true;
        }
        if (key == K_DEL && tab == 1 && selLine >= 0) { deleteElement(); return true; }
        return false;
    }

    public void typed(String s) {
        if (focus == null || s == null || s.isEmpty()) return;
        s = s.replace("\n", "").replace("\r", "");
        buf.insert(cur, s);
        cur += s.length();
        changed();
    }

    private void changed() {
        if (onEdit != null) onEdit.accept(buf.toString());
    }

    /** Esc: снять фокус / закрыть список / предупредить о несохранённом. true — забрано (окно не закрывать). */
    public boolean escape() {
        if (popup != null) { popup = null; return true; }
        if (focus != null) { unfocus(); return true; }
        if (!s().dirty.isEmpty() && !warnedDirty) {
            warnedDirty = true;
            note("Есть несохранённые правки: Esc ещё раз — выйти без сохранения, Ctrl+S — сохранить");
            return true;
        }
        return false;
    }

    public String focus() { return focus; }
}
