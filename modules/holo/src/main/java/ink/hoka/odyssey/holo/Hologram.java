package ink.hoka.odyssey.holo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Голограмма: панель в мире, страницы → строки → элементы (ряд). Формат файла и протокола — JSON ({@link HoloCodec}),
 * его же правит редактор мода. Объект изменяемый, но наружу (моду, в файл) уходит только через {@link HoloCodec}.
 */
public final class Hologram {

    /** Режим поворота: фиксированный, к игроку только по курсу, полностью к игроку. */
    public enum Facing {
        FIXED, BILLBOARD_Y, BILLBOARD;

        public String key() { return name().toLowerCase(Locale.ROOT); }

        static Facing parse(String s) {
            for (Facing f : values()) if (f.key().equals(s)) return f;
            throw new IllegalArgumentException("facing — fixed | billboard_y | billboard, а не «" + s + "»");
        }
    }

    /** Кому видна: всем, OP, по праву {@code holo.view.<id>}. */
    public enum View {
        ALL, OP, PERM;

        public String key() { return name().toLowerCase(Locale.ROOT); }

        static View parse(String s) {
            for (View v : values()) if (v.key().equals(s)) return v;
            throw new IllegalArgumentException("view — all | op | perm, а не «" + s + "»");
        }
    }

    public static final Set<String> THEMES = Set.of("space", "amber", "danger", "green");
    public static final Pattern ID = Pattern.compile("[a-z0-9_\\-]{1,48}");
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_.:\\-]{1,48})}");

    public String id;
    public String map = "world";
    public double x, y, z;
    public float yaw, pitch;
    public Facing facing = Facing.BILLBOARD_Y;
    public double scale = 1;
    public String theme = "space";
    /** Ширина панели в блоках, 0 — по содержимому. */
    public double width = 0;
    /** Отступ от края панели до содержимого (блоки). */
    public double padding = 0.12;
    public boolean background = true;
    public double opacity = 0.78;
    public boolean frame = true;
    public boolean hover = true;
    public double range = 32;
    public View view = View.ALL;
    /** Создана/правлена оператором — тогда разрешены действия «команда от консоли». */
    public boolean creatorOp;
    /** Временная (из кода другого модуля) — в файл не пишется. */
    public transient boolean temporary;
    public List<Page> pages = new ArrayList<>();
    /** Клик по панели мимо элементов. */
    public List<Action> left = new ArrayList<>(), right = new ArrayList<>();

    public Hologram(String id) { this.id = id; }

    public static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }

    public Page page(int i) { return pages.isEmpty() ? null : pages.get(Math.floorMod(i, pages.size())); }

    /** Элемент по адресу (страница, строка, номер в ряду) или null. */
    public Element element(int page, int line, int elem) {
        if (page < 0 || page >= pages.size()) return null;
        List<Line> ls = pages.get(page).lines;
        if (line < 0 || line >= ls.size()) return null;
        List<Element> es = ls.get(line).elements;
        return elem < 0 || elem >= es.size() ? null : es.get(elem);
    }

    /** Имена плейсхолдеров во всех текстах, подписях и значениях шкал (для подписки игрока). */
    public Set<String> placeholders() {
        Set<String> out = new LinkedHashSet<>();
        for (Page p : pages) for (Line l : p.lines) for (Element e : l.elements) {
            scan(e.text, out);
            scan(e.label, out);
            scan(e.value, out);
            scan(e.hint, out);
        }
        return out;
    }

    static void scan(String s, Set<String> out) {
        if (s == null || s.indexOf('{') < 0) return;
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) out.add(m.group(1));
    }

    public double distSq(double px, double py, double pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ───────────────────────────── части ─────────────────────────────

    public static final class Page {
        public List<Line> lines = new ArrayList<>();
    }

    public static final class Line {
        public List<Element> elements = new ArrayList<>();

        public Line() {}

        public Line(Element... es) { elements.addAll(List.of(es)); }
    }

    public enum Type {
        TEXT, ITEM, IMAGE, BAR, BUTTON, SEPARATOR, SPACER;

        public String key() { return name().toLowerCase(Locale.ROOT); }

        static Type parse(String s) {
            for (Type t : values()) if (t.key().equals(s)) return t;
            throw new IllegalArgumentException("тип элемента — text | item | image | bar | button | separator | spacer, а не «" + s + "»");
        }
    }

    /** Элемент строки. Поля общие для всех типов; в файл пишутся только нужные типу. */
    public static final class Element {
        public Type type;
        /** text: MiniMessage с плейсхолдерами. */
        public String text;
        /** Размер: шрифт текста (0.5–4), предмет/кнопка (множитель). */
        public double size = 1;
        /** left | center | right. */
        public String align = "center";
        /** item: «minecraft:diamond» или id из hoitems («hoka:ruby» или просто «ruby»). */
        public String item;
        public boolean spin = true;
        /** Подсказка при наведении (item и любой элемент). */
        public String hint;
        /** image: имя PNG в module/holo/images. */
        public String image;
        /** image, bar, spacer: размеры в блоках (0 — по умолчанию). */
        public double w, h;
        /** bar: значение «0.4» или «{плейсхолдер}»: доля value/max; при max = 1 значение больше 1 считается процентами. */
        public String value;
        public double max = 1;
        /** bar, button: цвет RGB «#8FD8FF» (пусто — из темы). */
        public String color;
        /** button: подпись; bar — подпись поверх шкалы. */
        public String label;
        /** Условие нажатия: «all», «op», «perm:узел». */
        public String cond = "all";
        /** Задержка нажатия (тики), -1 — по умолчанию модуля. */
        public int cooldown = -1;
        public List<Action> left = new ArrayList<>(), right = new ArrayList<>();

        public Element(Type type) { this.type = type; }

        public static Element text(String s) {
            Element e = new Element(Type.TEXT);
            e.text = s;
            return e;
        }

        public static Element button(String label, Action... onLeft) {
            Element e = new Element(Type.BUTTON);
            e.label = label;
            e.left.addAll(List.of(onLeft));
            return e;
        }

        public boolean clickable() { return !left.isEmpty() || !right.isEmpty(); }
    }

    public enum ActionType {
        CMD, CONSOLE, MSG, SOUND, TP, UI, PAGE;

        public String key() { return name().toLowerCase(Locale.ROOT); }

        static ActionType parse(String s) {
            for (ActionType t : values()) if (t.key().equals(s)) return t;
            throw new IllegalArgumentException("действие — cmd | console | msg | sound | tp | ui | page, а не «" + s + "»");
        }
    }

    /** Действие нажатия: тип и строка-значение (команда без «/», текст, звук, «[карта] x y z [курс наклон]», окно, next|prev|N). */
    public record Action(ActionType type, String value) {
        public static Action of(String type, String value) { return new Action(ActionType.parse(type), value == null ? "" : value); }
    }
}
