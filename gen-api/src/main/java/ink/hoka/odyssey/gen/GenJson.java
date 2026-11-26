package ink.hoka.odyssey.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Маленький JSON без зависимостей: hoka-gen.json и generator_options в op 20. Числа: целые — Long (в int-диапазоне —
 * Integer), иначе Double; объекты — LinkedHashMap, массивы — ArrayList. Ключи объектов пишутся по алфавиту.
 */
public final class GenJson {

    private GenJson() {}

    public static Object parse(String s) {
        int[] at = {0};
        Object v = value(s, at);
        ws(s, at);
        if (at[0] != s.length()) throw new IllegalArgumentException("JSON: лишнее в позиции " + at[0]);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object v = parse(s);
        if (!(v instanceof Map)) throw new IllegalArgumentException("JSON: нужен объект");
        return (Map<String, Object>) v;
    }

    private static void ws(String s, int[] at) { while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) at[0]++; }

    private static Object value(String s, int[] at) {
        ws(s, at);
        if (at[0] >= s.length()) throw new IllegalArgumentException("JSON: конец");
        char c = s.charAt(at[0]);
        if (c == '{') {
            at[0]++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws(s, at);
            if (s.charAt(at[0]) == '}') { at[0]++; return m; }
            while (true) {
                ws(s, at);
                String k = string(s, at);
                ws(s, at);
                if (s.charAt(at[0]++) != ':') throw new IllegalArgumentException("JSON: нужно ':'");
                m.put(k, value(s, at));
                ws(s, at);
                char d = s.charAt(at[0]++);
                if (d == '}') return m;
                if (d != ',') throw new IllegalArgumentException("JSON: нужно ',' или '}'");
            }
        }
        if (c == '[') {
            at[0]++;
            List<Object> l = new ArrayList<>();
            ws(s, at);
            if (s.charAt(at[0]) == ']') { at[0]++; return l; }
            while (true) {
                l.add(value(s, at));
                ws(s, at);
                char d = s.charAt(at[0]++);
                if (d == ']') return l;
                if (d != ',') throw new IllegalArgumentException("JSON: нужно ',' или ']'");
            }
        }
        if (c == '"') return string(s, at);
        if (s.startsWith("true", at[0])) { at[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", at[0])) { at[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", at[0])) { at[0] += 4; return null; }
        int b = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) at[0]++;
        String num = s.substring(b, at[0]);
        if (num.isEmpty()) throw new IllegalArgumentException("JSON: неожиданный символ '" + c + "'");
        if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
            long l = Long.parseLong(num);
            return l == (int) l ? (Object) (int) l : (Object) l;
        }
        return Double.parseDouble(num);
    }

    private static String string(String s, int[] at) {
        if (s.charAt(at[0]) != '"') throw new IllegalArgumentException("JSON: нужна строка");
        at[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(at[0]++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(at[0]++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> { sb.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16)); at[0] += 4; }
                default -> sb.append(e);
            }
        }
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) sb.append("null");
        else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : new java.util.TreeMap<>(stringKeys(m)).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) { if (!first) sb.append(','); first = false; write(o, sb); }
            sb.append(']');
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) sb.append(v);
        else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) sb.append((long) d).append(".0");
            else sb.append(d);
        } else str(v.toString(), sb);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (var e : m.entrySet()) r.put(String.valueOf(e.getKey()), e.getValue());
        return r;
    }

    private static void str(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }
}
