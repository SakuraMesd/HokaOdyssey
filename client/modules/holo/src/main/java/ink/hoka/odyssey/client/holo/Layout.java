package ink.hoka.odyssey.client.holo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Раскладка страницы голограммы в «точках» (пиксель шрифта игры; {@link #PX_PER_BLOCK} точек — блок при масштабе 1).
 * Считается только при изменении данных (новая голограмма, страница, значения плейсхолдеров), рисование берёт готовое.
 * Ряд — несколько элементов в строке; текст переносится по словам, чтобы панель не вылезала за ширину; ряд из одних
 * кнопок растягивается на всю ширину (меню).
 */
public final class Layout {

    public static final float PX_PER_BLOCK = 40f;
    public static final float LINE = 9f, GAP_X = 6f, GAP_Y = 3f, MAX_AUTO_BLOCKS = 12f, MIN_W = 48f, BTN_PAD = 10f;

    /** Ширина буквы шрифта (точки при размере 1) и пропорции картинки (высота/ширина; 1 — если неизвестно). */
    public interface Metrics {
        float width(int cp, boolean bold);

        default float imageAspect(String image) { return 1f; }
    }

    /** Строка текста после переноса: куски по стилю и ширина (точки при размере 1). */
    public record TLine(List<Mini.Run> runs, float width) {}

    /** Элемент на панели: адрес (строка, номер в ряду), прямоугольник (точки), строки текста, масштаб текста. */
    public static final class Box {
        public final int line, elem;
        public final Holo.El el;
        public float x, y, w, h;
        public List<TLine> text = List.of();
        public float textScale = 1;
        /** Шкала: заполнение 0..1. */
        public float fill;
        /** Кэш рисования (компоненты текста игры и т.п.). */
        public Object cache;

        Box(int line, int elem, Holo.El el) { this.line = line; this.elem = elem; this.el = el; }

        public boolean contains(float u, float v) { return u >= x && u < x + w && v >= y && v < y + h; }
    }

    /** Готовая страница: размер панели (точки) и элементы. */
    public record Panel(float w, float h, List<Box> boxes) {
        public Box at(float u, float v) {
            for (Box b : boxes) if (b.contains(u, v)) return b;
            return null;
        }
    }

    private Layout() {}

    public static Panel layout(Holo h, int page, Map<String, String> values, Metrics m, int textRgb) {
        List<List<Holo.El>> lines = h.page(page);
        float pad = (float) h.padding * PX_PER_BLOCK;
        boolean fixed = h.width > 0;
        float cw = fixed ? Math.max(MIN_W, (float) h.width * PX_PER_BLOCK - 2 * pad) : MAX_AUTO_BLOCKS * PX_PER_BLOCK - 2 * pad;

        // 1. естественные размеры
        List<List<Box>> rows = new ArrayList<>();
        float widest = 0;
        for (int li = 0; li < lines.size(); li++) {
            List<Box> row = new ArrayList<>();
            float rw = 0;
            List<Holo.El> els = lines.get(li);
            for (int ei = 0; ei < els.size(); ei++) {
                Box b = new Box(li, ei, els.get(ei));
                natural(b, values, m, textRgb);
                row.add(b);
                rw += b.w + (ei > 0 ? GAP_X : 0);
            }
            widest = Math.max(widest, rw);
            rows.add(row);
        }
        if (!fixed) cw = Math.max(MIN_W, Math.min(cw, widest));

        // 2. уложить ряды в ширину cw
        float y = pad;
        List<Box> all = new ArrayList<>();
        for (List<Box> row : rows) {
            if (row.isEmpty()) { y += LINE + GAP_Y; continue; }
            fit(row, cw, m);
            float total = GAP_X * (row.size() - 1);
            for (Box b : row) total += b.w;
            float rh = 0;
            for (Box b : row) rh = Math.max(rh, b.h);
            float x;
            if (row.size() == 1) {
                Box b = row.get(0);
                x = switch (b.el.align) { case "left" -> pad; case "right" -> pad + cw - b.w; default -> pad + (cw - b.w) / 2; };
            } else x = pad + Math.max(0, (cw - total) / 2);
            for (Box b : row) {
                b.x = x;
                b.y = y + (rh - b.h) / 2;
                x += b.w + GAP_X;
                all.add(b);
            }
            y += rh + GAP_Y;
        }
        float hgt = Math.max(y - (rows.isEmpty() ? 0 : GAP_Y) + pad, 2 * pad + LINE);
        return new Panel(cw + 2 * pad, hgt, all);
    }

    /** Естественный размер элемента (без ограничения ширины). */
    static void natural(Box b, Map<String, String> values, Metrics m, int textRgb) {
        Holo.El e = b.el;
        float s = (float) e.size;
        switch (e.type) {
            case "text" -> {
                b.textScale = s;
                b.text = wrap(Mini.parse(Holo.fill(e.text, values), textRgb), Float.MAX_VALUE, m);
                float w = 0;
                for (TLine t : b.text) w = Math.max(w, t.width());
                b.w = w * s;
                b.h = b.text.size() * LINE * s;
            }
            case "item" -> { b.w = b.h = 20 * s; }
            case "image" -> {
                float w = (float) (e.w > 0 ? e.w : 1) * PX_PER_BLOCK;
                b.w = w;
                b.h = e.h > 0 ? (float) e.h * PX_PER_BLOCK : w * m.imageAspect(e.image);
            }
            case "bar" -> {
                b.w = (float) (e.w > 0 ? e.w : 2) * PX_PER_BLOCK;
                b.h = 9;
                double v;
                try { v = Double.parseDouble(Holo.fill(e.value, values).trim().replace(',', '.')); }
                catch (NumberFormatException ex) { v = 0; }
                b.fill = (float) Math.max(0, Math.min(1, v > 1 && e.max == 1 ? v / 100 : v / e.max));
                if (e.label != null && !e.label.isEmpty()) {
                    b.textScale = 0.75f;
                    b.text = wrap(Mini.parse(Holo.fill(e.label, values), textRgb), Float.MAX_VALUE, m);
                    b.h = Math.max(b.h, LINE * 0.75f + 3);
                }
            }
            case "button" -> {
                b.textScale = s;
                b.text = wrap(Mini.parse(Holo.fill(e.label, values), textRgb), Float.MAX_VALUE, m);
                float w = 0;
                for (TLine t : b.text) w = Math.max(w, t.width());
                b.w = Math.max(w * s + BTN_PAD, e.w > 0 ? (float) e.w * PX_PER_BLOCK : 0);
                b.h = b.text.size() * LINE * s + 7;
            }
            case "separator" -> { b.w = 0; b.h = 5; }
            case "spacer" -> { b.w = 0; b.h = e.h > 0 ? (float) e.h * PX_PER_BLOCK : 6; }
            default -> { b.w = 0; b.h = LINE; }
        }
    }

    /** Уложить ряд в ширину cw: текст переносится, кнопки-меню растягиваются, лишнее — сжимается. */
    static void fit(List<Box> row, float cw, Metrics m) {
        if (row.size() == 1 && (row.get(0).el.type.equals("separator") || row.get(0).el.type.equals("spacer"))) {
            row.get(0).w = cw;
            return;
        }
        boolean menu = row.stream().allMatch(b -> b.el.type.equals("button"));
        float gaps = GAP_X * (row.size() - 1);
        if (menu) {
            float each = (cw - gaps) / row.size();
            for (Box b : row) shrinkButton(b, each);
            return;
        }
        float total = gaps;
        for (Box b : row) total += b.w;
        if (total <= cw + 0.01f) return;
        // сначала переносим текст: остальным — их ширина, тексту — поровну из остатка
        List<Box> texts = row.stream().filter(b -> b.el.type.equals("text") || b.el.type.equals("button")).toList();
        // кнопки не переносятся (слово по буквам некрасиво) — у них уменьшается шрифт
        float fixedW = gaps;
        for (Box b : row) if (!texts.contains(b)) fixedW += b.w;
        if (!texts.isEmpty()) {
            float share = Math.max(20, (cw - fixedW) / texts.size());
            for (Box b : texts) {
                if (b.w <= share) continue;
                if (b.el.type.equals("button")) { shrinkButton(b, share); continue; }
                rewrap(b, share, m);
                float w = 0;
                for (TLine t : b.text) w = Math.max(w, t.width());
                b.w = Math.min(share, w * b.textScale);
                b.h = b.text.size() * LINE * b.textScale;
            }
        }
        total = gaps;
        for (Box b : row) total += b.w;
        if (total > cw) {                                                       // всё ещё шире — сжать пропорционально
            float k = Math.max(0.05f, (cw - gaps) / (total - gaps));
            for (Box b : row) {
                b.w *= k;
                if (!b.el.type.equals("text") && !b.el.type.equals("button") && !b.el.type.equals("bar")) b.h *= k;
                if (b.el.type.equals("text") || b.el.type.equals("button")) {
                    b.textScale *= k;
                    b.h *= k;
                }
            }
        }
    }

    /** Кнопка шириной w: подпись в одну строку, не влезает — меньше шрифт. */
    static void shrinkButton(Box b, float w) {
        float lw = 0;
        for (TLine t : b.text) lw = Math.max(lw, t.width());
        float avail = Math.max(4, w - BTN_PAD);
        if (lw * b.textScale > avail) b.textScale = avail / Math.max(1, lw);
        b.w = w;
        b.h = b.text.size() * LINE * b.textScale + 7;
    }

    private static void rewrap(Box b, float maxW, Metrics m) {
        List<Mini.Glyph> gs = new ArrayList<>();
        for (int i = 0; i < b.text.size(); i++) {
            for (Mini.Run r : b.text.get(i).runs()) r.text().codePoints().forEach(cp -> gs.add(new Mini.Glyph(cp, r.rgb(), r.flags())));
            if (i < b.text.size() - 1) gs.add(new Mini.Glyph('\n', 0, 0));
        }
        b.text = wrap(gs, Math.max(8, maxW / b.textScale), m);
    }

    /** Перенос по словам (длинное слово — по буквам). maxW — точки при размере 1. */
    public static List<TLine> wrap(List<Mini.Glyph> gs, float maxW, Metrics m) {
        List<TLine> out = new ArrayList<>();
        int start = 0, lastSpace = -1;
        float w = 0, wAtSpace = 0;
        for (int i = 0; i < gs.size(); i++) {
            Mini.Glyph g = gs.get(i);
            if (g.cp() == '\n') {
                out.add(line(gs, start, i, w));
                start = i + 1;
                w = 0;
                lastSpace = -1;
                continue;
            }
            float cw = m.width(g.cp(), (g.flags() & Mini.BOLD) != 0);
            if (g.cp() == ' ') { lastSpace = i; wAtSpace = w; }
            if (w + cw > maxW && i > start) {
                if (lastSpace > start) {
                    out.add(line(gs, start, lastSpace, wAtSpace));
                    start = lastSpace + 1;
                    w = 0;
                    for (int k = start; k < i; k++) w += m.width(gs.get(k).cp(), (gs.get(k).flags() & Mini.BOLD) != 0);
                } else {
                    out.add(line(gs, start, i, w));
                    start = i;
                    w = 0;
                }
                lastSpace = -1;
            }
            w += cw;
        }
        out.add(line(gs, start, gs.size(), w));
        return out;
    }

    private static TLine line(List<Mini.Glyph> gs, int from, int to, float w) {
        return new TLine(Mini.runs(gs, from, to), w);
    }
}
