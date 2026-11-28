package ink.hoka.odyssey.holo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Подмножество MiniMessage для действия «сообщение» (в Minestom MiniMessage нет): цвета по имени и {@code <#RRGGBB>},
 * {@code <b> <i> <u> <st> <obf>} (и длинные имена), закрывающие теги, {@code <reset>}; градиент — первым цветом.
 * Тот же набор понимает мод (с настоящим градиентом).
 */
final class MiniLite {

    private MiniLite() {}

    static Component parse(String s) {
        TextComponent.Builder root = Component.text();
        Deque<Style> stack = new ArrayDeque<>();
        Style cur = Style.empty();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int end = c == '<' ? s.indexOf('>', i) : -1;
            if (end > i) {
                String tag = s.substring(i + 1, end).toLowerCase(Locale.ROOT);
                Style next = apply(cur, tag);
                boolean close = tag.startsWith("/");
                if (next != null || close || tag.equals("reset")) {
                    if (buf.length() > 0) { root.append(Component.text(buf.toString(), cur)); buf.setLength(0); }
                    if (tag.equals("reset")) { stack.clear(); cur = Style.empty(); }
                    else if (close) cur = stack.isEmpty() ? Style.empty() : stack.pop();
                    else { stack.push(cur); cur = next; }
                    i = end + 1;
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        if (buf.length() > 0) root.append(Component.text(buf.toString(), cur));
        return root.build();
    }

    /** Стиль после открывающего тега или null (не тег — выводится как текст). */
    static Style apply(Style cur, String tag) {
        switch (tag) {
            case "b", "bold" -> { return cur.decoration(TextDecoration.BOLD, true); }
            case "i", "em", "italic" -> { return cur.decoration(TextDecoration.ITALIC, true); }
            case "u", "underlined" -> { return cur.decoration(TextDecoration.UNDERLINED, true); }
            case "st", "strikethrough" -> { return cur.decoration(TextDecoration.STRIKETHROUGH, true); }
            case "obf", "obfuscated" -> { return cur.decoration(TextDecoration.OBFUSCATED, true); }
            default -> { }
        }
        String t = tag.startsWith("color:") ? tag.substring(6) : tag;
        if (t.startsWith("gradient:")) {
            String[] p = t.substring(9).split(":");
            TextColor col = color(p[0]);
            return col == null ? null : cur.color(col);
        }
        if (t.equals("rainbow")) return cur.color(NamedTextColor.RED);
        TextColor col = color(t);
        return col == null ? null : cur.color(col);
    }

    static TextColor color(String t) {
        if (t.startsWith("#") && t.length() == 7) return TextColor.fromHexString(t);
        if (t.equals("grey")) t = "gray";
        if (t.equals("dark_grey")) t = "dark_gray";
        return NamedTextColor.NAMES.value(t);
    }
}
