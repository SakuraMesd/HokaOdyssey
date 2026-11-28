package ink.hoka.odyssey.holo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Пакеты клиентскому модулю holo (канал clientmods «holo»), DataOutputStream; «STR» — int длина + UTF-8 (JSON может
 * быть больше 64 КБ, writeUTF не годится). Сервер → мод:
 * <pre>
 * 1 HELLO   byte flags (бит 0 — можно редактор: OP); UTF карта игрока; STR темы (JSON {имя: {bg, frame, glow, accent, text}})
 * 2 SET     STR голограмма (JSON, {@link HoloCodec}); int страница игрока; int n; n × (UTF id предмета, int len, len байт
 *           ItemStack в сетевом виде игры) — полная голограмма (новая рядом или изменилась)
 * 3 REMOVE  UTF id; byte анимация (1 — рассыпаться, 0 — тихо: ушла из дальности)
 * 4 CLEAR   — смена карты: забыть все
 * 5 VALUES  int n; n × (UTF имя, UTF значение) — плейсхолдеры игрока (только изменившиеся)
 * 6 PAGE    UTF id; int страница
 * 7 INFO    STR JSON для редактора: images, items, placeholders, maps, templates, holograms (все, кроме временных)
 * 8 EDIT    UTF id ("" — список) — открыть редактор
 * 9 RESULT  byte ok; UTF текст — ответ на правку редактора
 * </pre>
 * Мод → сервер:
 * <pre>
 * 1 CLICK      UTF id; int страница; int строка; int элемент (−1 — панель); byte кнопка (0 ЛКМ, 1 ПКМ)
 * 2 SAVE       STR голограмма (JSON) — создать/заменить (OP)
 * 3 DELETE     UTF id (OP)
 * 4 TP         UTF id (OP)
 * 5 TPL_SAVE   UTF имя; STR голограмма (OP)
 * 6 TPL_DELETE UTF имя (OP)
 * 7 INFO_REQ   — прислать INFO (OP)
 * </pre>
 */
final class Net {

    static final int HELLO = 1, SET = 2, REMOVE = 3, CLEAR = 4, VALUES = 5, PAGE = 6, INFO = 7, EDIT = 8, RESULT = 9;
    static final int C_CLICK = 1, C_SAVE = 2, C_DELETE = 3, C_TP = 4, C_TPL_SAVE = 5, C_TPL_DELETE = 6, C_INFO_REQ = 7;

    private Net() {}

    interface Body { void write(DataOutputStream out) throws IOException; }

    static byte[] pack(int op, Body body) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(op);
            body.write(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toByteArray();
    }

    static void str(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    static String str(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new IOException("строка длиной " + n);
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }

    static DataInputStream in(byte[] data) { return new DataInputStream(new ByteArrayInputStream(data)); }

    static byte[] hello(boolean editor, String map, String themes) {
        return pack(HELLO, o -> { o.writeByte(editor ? 1 : 0); o.writeUTF(map); str(o, themes); });
    }

    static byte[] set(String json, int page, Map<String, byte[]> items) {
        return pack(SET, o -> {
            str(o, json);
            o.writeInt(page);
            o.writeInt(items.size());
            for (var e : items.entrySet()) {
                o.writeUTF(e.getKey());
                o.writeInt(e.getValue().length);
                o.write(e.getValue());
            }
        });
    }

    static byte[] remove(String id, boolean animate) { return pack(REMOVE, o -> { o.writeUTF(id); o.writeByte(animate ? 1 : 0); }); }

    static byte[] clear() { return pack(CLEAR, o -> { }); }

    static byte[] values(Map<String, String> v) {
        return pack(VALUES, o -> {
            o.writeInt(v.size());
            for (var e : v.entrySet()) { o.writeUTF(e.getKey()); o.writeUTF(e.getValue()); }
        });
    }

    static byte[] page(String id, int page) { return pack(PAGE, o -> { o.writeUTF(id); o.writeInt(page); }); }

    static byte[] info(String json) { return pack(INFO, o -> str(o, json)); }

    static byte[] edit(String id) { return pack(EDIT, o -> o.writeUTF(id == null ? "" : id)); }

    static byte[] result(boolean ok, String msg) { return pack(RESULT, o -> { o.writeByte(ok ? 1 : 0); o.writeUTF(msg); }); }
}
