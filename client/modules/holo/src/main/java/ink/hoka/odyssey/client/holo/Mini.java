package ink.hoka.odyssey.client.holo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MiniMessage (подмножество) → буквы со стилем: цвета по имени и {@code <#RRGGBB>} / {@code <color:…>},
 * {@code <b> <i> <u> <st> <obf>} (и длинные имена), {@code <gradient:#a:#b[:#c…]>}, {@code <rainbow>}, закрывающие
 * теги, {@code <reset>}, {@code <br>} и «\n» — перенос. Неизвестный тег выводится как текст. Без классов игры — тесты и
 * превью идут без неё. Тот же разбор подсвечивает текст в редакторе ({@link #tokens}).
 */
public final class Mini {

    public static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, STRIKE = 8, OBF = 16;

    /** Буква: код символа, цвет RGB, флаги. Код '\n' — перенос строки. */
    public record Glyph(int cp, int rgb, int flags) {}

    /** Кусок строки одного стиля (для рисования). */
    public record Run(String text, int rgb, int flags) {}

    static final Map<String, Integer> NAMES = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA), Map.entry("dark_green", 0x00AA00),
            Map.entry("dark_aqua", 0x00AAAA), Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
            Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA), Map.entry("grey", 0xAAAAAA),
            Map.entry("dark_gray", 0x555555), Map.entry("dark_grey", 0x555555), Map.entry("blue", 0x5555FF),
            Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF), Map.entry("red", 0xFF5555),
            Map.entry("light_purple", 0xFF55FF), Map.entry("yellow", 0xFFFF55), Map.entry("white", 0xFFFFFF));

    private Mini() {}

    /** Стиль на стеке: цвет, флаги, градиент (цвета) и где он начался в выходе. */
    private record St(int rgb, int flags, int[] grad, boolean rainbow, int gradStart) {}

    public static List<Glyph> parse(String s, int baseRgb) {
        List<Glyph> out = new ArrayList<>();
        Deque<St> stack = new ArrayDeque<>();
        St cur = new St(baseRgb, 0, null, false, 0);
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && s.charAt(i + 1) == '<') { out.add(new Glyph('<', cur.rgb, cur.flags)); i += 2; continue; }
            if (c == '<') {
                int end = s.indexOf('>', i);
                if (end > i + 1) {
                    String tag = s.substring(i + 1, end).toLowerCase(Locale.ROOT);
                    if (tag.equals("br") || tag.equals("newline")) { out.add(new Glyph('\n', cur.rgb, cur.flags)); i = end + 1; continue; }
                    if (tag.equals("reset")) {
                        finish(cur, out);
                        stack.clear();
                        cur = new St(baseRgb, 0, null, false, out.size());
                        i = end + 1;
                        continue;
                    }
                    if (tag.startsWith("/")) {
                        finish(cur, out);
                        if (!stack.isEmpty()) cur = stack.pop();
                        else cur = new St(baseRgb, 0, null, false, out.size());
                        i = end + 1;
                        continue;
                    }
                    St next = open(cur, tag, out.size());
                    if (next != null) {
                        stack.push(cur);
                        cur = next;
                        i = end + 1;
                        continue;
                    }
                }
            }
            int cp = s.codePointAt(i);
            out.add(new Glyph(cp, cur.rgb, cur.flags));
            i += Character.charCount(cp);
        }
        finish(cur, out);
        return out;
    }

    private static St open(St cur, String tag, int at) {
        switch (tag) {
            case "b", "bold" -> { return new St(cur.rgb, cur.flags | BOLD, null, false, at); }
            case "i", "em", "italic" -> { return new St(cur.rgb, cur.flags | ITALIC, null, false, at); }
            case "u", "underlined" -> { return new St(cur.rgb, cur.flags | UNDERLINE, null, false, at); }
            case "st", "strikethrough" -> { return new St(cur.rgb, cur.flags | STRIKE, null, false, at); }
            case "obf", "obfuscated" -> { return new St(cur.rgb, cur.flags | OBF, null, false, at); }
            case "rainbow" -> { return new St(cur.rgb, cur.flags, null, true, at); }
            default -> { }
        }
        if (tag.startsWith("gradient")) {
            String[] p = tag.split(":");
            List<Integer> cols = new ArrayList<>();
            for (int k = 1; k < p.length; k++) {
                Integer col = color(p[k]);
                if (col != null) cols.add(col);
            }
            if (cols.isEmpty()) { cols.add(0xFFFFFF); cols.add(0x000000); }
            if (cols.size() == 1) cols.add(cols.get(0));
            int[] g = cols.stream().mapToInt(Integer::intValue).toArray();
            return new St(g[0], cur.flags, g, false, at);
        }
        Integer col = color(tag.startsWith("color:") ? tag.substring(6) : tag);
        return col == null ? null : new St(col, cur.flags, null, false, at);
    }

    /** Закрыли градиент/радугу — раскрасить буквы, выведенные под ним. */
    private static void finish(St st, List<Glyph> out) {
        if (st.grad == null && !st.rainbow) return;
        int from = st.gradStart, to = out.size(), len = 0;
        for (int k = from; k < to; k++) if (out.get(k).cp != '\n') len++;
        int idx = 0;
        for (int k = from; k < to; k++) {
            Glyph g = out.get(k);
            if (g.cp == '\n') continue;
            float t = len <= 1 ? 0 : idx / (float) (len - 1);
            int rgb = st.rainbow ? hsv(t * 0.85f) : gradient(st.grad, t);
            out.set(k, new Glyph(g.cp, rgb, g.flags));
            idx++;
        }
    }

    static int gradient(int[] g, float t) {
        float f = t * (g.length - 1);
        int a = Math.min((int) f, g.length - 2);
        return lerp(g[a], g[a + 1], f - a);
    }

    public static int lerp(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int gg = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bb = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (gg << 8) | bb;
    }

    static int hsv(float h) {
        float r = Math.abs(h * 6 - 3) - 1, g = 2 - Math.abs(h * 6 - 2), b = 2 - Math.abs(h * 6 - 4);
        return (Math.round(clamp(r) * 255) << 16) | (Math.round(clamp(g) * 255) << 8) | Math.round(clamp(b) * 255);
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    public static Integer color(String t) {
        if (t.startsWith("#") && t.length() == 7) {
            try { return Integer.parseInt(t.substring(1), 16); } catch (NumberFormatException e) { return null; }
        }
        return NAMES.get(t);
    }

    /** Буквы → куски одного стиля. */
    public static List<Run> runs(List<Glyph> gs, int from, int to) {
        List<Run> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        int rgb = -1, fl = -1;
        for (int k = from; k < to; k++) {
            Glyph g = gs.get(k);
            if (g.cp == '\n') continue;
            if (b.length() > 0 && (g.rgb != rgb || g.flags != fl)) { out.add(new Run(b.toString(), rgb, fl)); b.setLength(0); }
            rgb = g.rgb;
            fl = g.flags;
            b.appendCodePoint(g.cp);
        }
        if (b.length() > 0) out.add(new Run(b.toString(), rgb, fl));
        return out;
    }

    /** Убрать теги (для подсказок и списков). */
    public static String plain(String s) {
        StringBuilder b = new StringBuilder();
        for (Glyph g : parse(s, 0)) if (g.cp != '\n') b.appendCodePoint(g.cp); else b.append(' ');
        return b.toString();
    }

    /** Подсветка в редакторе: куски сырого текста и их вид (0 — текст, 1 — тег, 2 — плейсхолдер). */
    public record Token(String text, int kind) {}

    public static List<Token> tokens(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = s.length(), start = 0;
        while (i < n) {
            char c = s.charAt(i);
            int close = c == '<' ? s.indexOf('>', i) : c == '{' ? s.indexOf('}', i) : -1;
            if (close > i) {
                if (i > start) out.add(new Token(s.substring(start, i), 0));
                out.add(new Token(s.substring(i, close + 1), c == '<' ? 1 : 2));
                i = start = close + 1;
                continue;
            }
            i++;
        }
        if (start < n) out.add(new Token(s.substring(start), 0));
        return out;
    }
}
