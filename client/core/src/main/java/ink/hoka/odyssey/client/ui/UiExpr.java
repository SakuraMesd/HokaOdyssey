package ink.hoka.odyssey.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Значения и условия меню. Подстановки в строках: {@code {имя}} — данные меню с сервера (иначе {@link #VARS});
 * {@code {.поле}} — поле текущего элемента списка ({@code repeat}); {@code {#}} — номер элемента с 1.
 * Условия ({@code if}, {@code disabled}): сравнения {@code == != > < >= <=}, {@code && || !}, скобки, числа и строки
 * ('текст' или без кавычек), например {@code {lvl} >= 10 && {.owned} == 0}.
 */
public final class UiExpr {

    /** Где искать значения: данные меню + текущий элемент списка (может быть null) и его номер. */
    record Ctx(JsonObject data, JsonObject item, int index) {}

    /** Значения не из данных меню (HUD и т.п.); по умолчанию — пусто. */
    public static java.util.function.Function<String, String> VARS = n -> "";

    private static final Pattern VAR = Pattern.compile("\\{(#|\\.?[a-zA-Z0-9_.]+)}");

    private UiExpr() {}

    static String fill(String s, Ctx c) {
        if (s == null || s.indexOf('{') < 0) return s;
        Matcher m = VAR.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(value(m.group(1), c)));
        m.appendTail(sb);
        return sb.toString();
    }

    static String value(String name, Ctx c) {
        if (name.equals("#")) return String.valueOf(c.index + 1);
        if (name.startsWith(".")) return str(c.item == null ? null : c.item.get(name.substring(1)));
        if (c.data != null && c.data.has(name)) return str(c.data.get(name));
        return VARS.apply(name);
    }

    private static String str(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    static boolean test(String expr, Ctx c) {
        if (expr == null || expr.isBlank()) return true;
        try {
            Parser p = new Parser(fill(expr, c));
            Object v = p.or();
            return truthy(v);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static float num(String s, float def) {
        if (s == null) return def;
        try {
            return Float.parseFloat(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0;
        String s = String.valueOf(v);
        return !s.isEmpty() && !s.equals("0") && !s.equalsIgnoreCase("false") && !s.equalsIgnoreCase("no");
    }

    /** Рекурсивный разбор: or -> and -> not -> cmp -> atom. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        Object or() {
            Object a = and();
            while (eat("||")) { Object b = and(); a = truthy(a) || truthy(b); }
            return a;
        }

        Object and() {
            Object a = not();
            while (eat("&&")) { Object b = not(); a = truthy(a) && truthy(b); }
            return a;
        }

        Object not() {
            if (eat("!") && !peek("=")) return !truthy(not());
            return cmp();
        }

        Object cmp() {
            Object a = atom();
            for (String op : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
                if (eat(op)) {
                    Object b = atom();
                    Double x = d(a), y = d(b);
                    if (x != null && y != null) {
                        return switch (op) {
                            case "==" -> x.equals(y); case "!=" -> !x.equals(y); case ">=" -> x >= y;
                            case "<=" -> x <= y; case ">" -> x > y; default -> x < y;
                        };
                    }
                    int k = String.valueOf(a).compareTo(String.valueOf(b));
                    return switch (op) {
                        case "==" -> k == 0; case "!=" -> k != 0; case ">=" -> k >= 0; case "<=" -> k <= 0; case ">" -> k > 0; default -> k < 0;
                    };
                }
            }
            return a;
        }

        Object atom() {
            skip();
            if (eat("(")) { Object v = or(); eat(")"); return v; }
            if (i < s.length() && s.charAt(i) == '\'') {
                int j = s.indexOf('\'', i + 1);
                String v = s.substring(i + 1, j < 0 ? s.length() : j);
                i = j < 0 ? s.length() : j + 1;
                return v;
            }
            int st = i;
            while (i < s.length() && "=!<>&|() ".indexOf(s.charAt(i)) < 0) i++;
            String w = s.substring(st, i);
            Double v = d(w);
            return v != null ? (Object) v : w;
        }

        private static Double d(Object o) {
            if (o instanceof Double x) return x;
            if (o instanceof Boolean b) return b ? 1.0 : 0.0;
            try {
                return Double.parseDouble(String.valueOf(o).trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private void skip() { while (i < s.length() && s.charAt(i) == ' ') i++; }

        private boolean peek(String t) { skip(); return s.startsWith(t, i); }

        private boolean eat(String t) {
            skip();
            if (s.startsWith(t, i)) { i += t.length(); return true; }
            return false;
        }
    }
}
