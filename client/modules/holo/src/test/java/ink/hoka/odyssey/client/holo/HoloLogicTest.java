package ink.hoka.odyssey.client.holo;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Мод без игры: MiniMessage, раскладка (ряды, перенос, ширина), луч в панель, сессия редактора (отмена/повтор). */
class HoloLogicTest {

    /** Шрифт-заглушка: буква 6 точек, пробел 4, жирная +1 (как у игры). */
    static final Layout.Metrics M = (cp, bold) -> (cp == ' ' ? 4 : 6) + (bold ? 1 : 0);

    static Holo holo(String json) { return Holo.parse(Session.parse(json)); }

    @Test
    void miniMessage() {
        List<Mini.Glyph> g = Mini.parse("<b><red>Ab</red></b>c<gradient:#000000:#FFFFFF>xyz</gradient>\\<q<br>n", 0x123456);
        String text = new StringBuilder().appendCodePoint(g.get(0).cp()).toString();
        assertEquals("A", text);
        assertEquals(0xFF5555, g.get(0).rgb());
        assertEquals(Mini.BOLD, g.get(0).flags());
        assertEquals(0x123456, g.get(2).rgb());                                  // после закрытия — базовый цвет
        assertEquals(0x000000, g.get(3).rgb());                                  // градиент: начало
        assertEquals(0xFFFFFF, g.get(5).rgb());                                  // и конец
        assertEquals('<', g.get(6).cp());                                         // \< — буква, а не тег
        assertEquals('\n', g.get(8).cp());
        assertEquals("Ab c", Mini.plain("<b>Ab</b><br>c"));
        assertEquals("a <nope> b", Mini.plain("a <nope> b"));                    // неизвестный тег — как текст
        assertEquals(List.of(new Mini.Token("x ", 0), new Mini.Token("<b>", 1), new Mini.Token("{player}", 2)), Mini.tokens("x <b>{player}"));
        // значение плейсхолдера не ломает разметку
        assertEquals("hi \\<red>", Holo.fill("hi {p}", Map.of("p", "<red>")));
        assertEquals("hi <red>", Mini.plain(Holo.fill("hi {p}", Map.of("p", "<red>"))));
    }

    @Test
    void layoutRowsAndWidth() {
        Holo h = holo("""
                {"id": "a", "pos": [0,0,0], "padding": 0.1, "pages": [{"lines": [
                  [{"type": "text", "text": "Hello", "size": 2}],
                  [{"type": "item", "item": "minecraft:diamond"}, {"type": "text", "text": "xx"}],
                  [{"type": "separator"}],
                  [{"type": "button", "label": "A"}, {"type": "button", "label": "B"}],
                  [{"type": "bar", "value": "{fuel}", "max": 100, "w": 1}]]}]}""");
        Layout.Panel p = Layout.layout(h, 0, Map.of("fuel", "25"), M, 0xFFFFFF);
        float pad = 4;
        // ширина по содержимому: самая широкая строка — «Hello» × 2 = 60 точек
        assertEquals(60 + 2 * pad, p.w(), 0.01);
        Layout.Box title = p.boxes().get(0);
        assertEquals(18, title.h, 0.01);                                         // 9 × 2
        Layout.Box item = p.boxes().get(1), xx = p.boxes().get(2);
        assertEquals(item.y + item.h / 2, xx.y + xx.h / 2, 0.01);                // ряд — по центру по высоте
        assertTrue(xx.x >= item.x + item.w);
        Layout.Box sep = p.boxes().get(3);
        assertEquals(60, sep.w, 0.01);                                           // линия — на всю ширину
        Layout.Box a = p.boxes().get(4), b = p.boxes().get(5);
        assertEquals(a.w, b.w, 0.01);                                            // меню — поровну на всю ширину
        assertEquals(60, a.w + b.w + Layout.GAP_X, 0.01);
        assertEquals(0.25f, p.boxes().get(6).fill, 0.001);                       // шкала: 25 из 100
        for (Layout.Box x : p.boxes()) {
            assertTrue(x.x >= pad - 0.01 && x.x + x.w <= p.w() - pad + 0.01, "не вылезает: " + x.el.type);
        }
        float bottom = 0;
        for (Layout.Box x : p.boxes()) bottom = Math.max(bottom, x.y + x.h);
        assertEquals(p.h() - pad, bottom, 0.01);
        assertSame(p.boxes().get(1), p.at(item.x + 1, item.y + 1));               // попадание по точке
    }

    @Test
    void longTextWrapsInsideFixedWidth() {
        Holo h = holo("""
                {"id": "a", "pos": [0,0,0], "width": 2, "padding": 0.1, "pages": [{"lines": [
                  [{"type": "text", "text": "один два три четыре пять шесть семь восемь девять десять", "align": "left"}],
                  [{"type": "item", "item": "x"}, {"type": "text", "text": "очень длинная подпись рядом с предметом"}, {"type": "button", "label": "Жми сюда скорее"}],
                  [{"type": "text", "text": "ОченьДлинноеСловоБезПробеловКотороеНеВлезает"}]]}]}""");
        Layout.Panel p = Layout.layout(h, 0, Map.of(), M, 0xFFFFFF);
        assertEquals(80, p.w(), 0.01);                                           // 2 блока
        for (Layout.Box x : p.boxes()) {
            assertTrue(x.x >= 4 - 0.01 && x.x + x.w <= 76 + 0.01, x.el.type + " в ширине: " + x.x + "+" + x.w);
            for (Layout.TLine l : x.text) assertTrue(l.width() * x.textScale <= x.w + 0.01, "строка текста в боксе");
        }
        assertTrue(p.boxes().get(0).text.size() > 3, "длинный текст перенесён");
        assertTrue(p.boxes().get(4).text.size() > 1, "длинное слово — по буквам");
    }

    @Test
    void pagesAndEmpty() {
        Holo h = holo("{\"id\": \"a\", \"pos\": [0,0,0], \"pages\": [{\"lines\": [[{\"type\": \"text\", \"text\": \"1\"}]]}, {\"lines\": []}]}");
        assertEquals(1, Layout.layout(h, 0, Map.of(), M, 0).boxes().size());
        Layout.Panel empty = Layout.layout(h, 1, Map.of(), M, 0);
        assertTrue(empty.boxes().isEmpty() && empty.w() > 0 && empty.h() > 0);
        assertEquals(1, Layout.layout(h, 2, Map.of(), M, 0).boxes().size());   // по кругу
        assertEquals(java.util.Set.of(), h.names);
        assertTrue(holo("{\"id\": \"a\", \"pos\": [0,0,0], \"pages\": [{\"lines\": [[{\"type\": \"bar\", \"value\": \"{f}\", \"label\": \"{g}\"}]]}]}").names.containsAll(List.of("f", "g")));
    }

    @Test
    void rayHitsPanelFace() {
        // фиксированная, курс 0: «лицо» смотрит на −Z (к тому, кто стоял с курсом 0 и смотрел на +Z)
        Orient o = new Orient(0, 0);
        assertEquals(-1, o.nz, 1e-9);
        assertEquals(-1, o.rx, 1e-9);                                             // вправо для зрителя — −X
        double k = 1 / 40.0;
        double[] hit = o.hit(0, 0, -3, 0, 0, 1, 0, 0, 0, 80, 40, k);           // смотрим в центр
        assertNotNull(hit);
        assertEquals(40, hit[0], 1e-6);
        assertEquals(20, hit[1], 1e-6);
        assertEquals(3, hit[2], 1e-6);
        double[] left = o.hit(0.5, 0.25, -3, 0, 0, 1, 0, 0, 0, 80, 40, k);    // правее и выше по миру
        assertEquals(40 - 20, left[0], 1e-6);                                     // +X мира — левее для зрителя
        assertEquals(20 - 10, left[1], 1e-6);
        assertNull(o.hit(0, 0, 3, 0, 0, -1, 0, 0, 0, 80, 40, k));               // сзади не ловится
        double[] w = o.toWorld(left[0], left[1], 0, 0, 0, 0, 80, 40, k);
        assertEquals(0.5, w[0], 1e-6);
        assertEquals(0.25, w[1], 1e-6);
        // к игроку: зритель с любой стороны видит лицо
        Holo h = holo("{\"id\": \"a\", \"pos\": [10, 5, 10], \"facing\": \"billboard\"}");
        Orient b = Orient.of(h, 5, 14, 8, 7);
        double dx = 14 - 10, dy = 8 - 5, dz = 7 - 10, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        assertEquals(dx / len, b.nx, 1e-6);
        assertEquals(dy / len, b.ny, 1e-6);
        assertEquals(dz / len, b.nz, 1e-6);
        Orient by = Orient.of(holo("{\"id\": \"a\", \"pos\": [10, 5, 10]}"), 5, 14, 30, 7);
        assertEquals(0, by.ny, 1e-9);                                             // по Y — без наклона
    }

    static Session session() {
        Session s = new Session();
        JsonObject o = Session.parse("{\"id\": \"a\", \"map\": \"world\", \"pos\": [0, 64, 0], \"pages\": [{\"lines\": []}]}");
        s.server.put("a", o);
        s.select("a");
        return s;
    }

    @Test
    void undoRedoSession() throws Exception {
        Session s = session();
        int[] saved = {0};
        s.saver = o -> saved[0]++;
        assertFalse(s.canUndo());
        s.change(null, d -> d.addProperty("scale", 2));
        s.change("f", d -> d.addProperty("theme", "a"));
        s.change("f", d -> d.addProperty("theme", "am"));                       // то же поле подряд — одна отмена
        s.change("f", d -> d.addProperty("theme", "amber"));
        assertEquals("amber", s.current().get("theme").getAsString());
        assertTrue(s.dirty.contains("a"));
        assertTrue(s.undo());
        assertFalse(s.current().has("theme"));
        assertEquals(2, s.current().get("scale").getAsInt());
        assertTrue(s.redo());
        assertEquals("amber", s.current().get("theme").getAsString());
        assertTrue(s.undo());
        assertTrue(s.undo());
        assertFalse(s.current().has("scale"));
        assertFalse(s.undo());
        assertTrue(s.redo());
        s.change(null, d -> d.addProperty("range", 10));                        // новая правка — повтор сброшен
        assertFalse(s.canRedo());
        assertEquals(1, s.saveAll());
        assertEquals(1, saved[0]);
        assertTrue(s.dirty.isEmpty());
        s.result(false);                                                          // сервер отказал — снова не сохранено
        assertTrue(s.dirty.contains("a"));
        s.saveAll();
        s.result(true);
        assertTrue(s.dirty.isEmpty());

        // создать → отменить — пропадает; повторить — возвращается
        s.put(Session.parse("{\"id\": \"b\", \"pos\": [0,0,0]}"));
        assertEquals("b", s.selected);
        s.undo();
        assertNull(s.get("b"));
        s.redo();
        assertNotNull(s.get("b"));
        s.discard();
        assertNull(s.get("b"));
        assertNotNull(s.get("a"));
    }

    @Test
    void editorOperationsUndo() {
        Session s = session();
        EditorUi ui = new EditorUi(PreviewTest.host(s));
        ui.tab = 1;
        ui.addElement("text");
        ui.addElement("button");
        ui.inRow = true;
        ui.addElement("item");                                                   // в ряд к кнопке
        JsonObject c = s.current();
        var lines = EditorUi.lines(c, 0);
        assertEquals(2, lines.size());
        assertEquals(2, EditorUi.row(lines, 1).size());
        assertEquals("item", EditorUi.row(lines, 1).get(1).getAsJsonObject().get("type").getAsString());
        EditorUi.moveLine(lines, 1, 0);
        assertEquals("button", EditorUi.row(lines, 0).get(0).getAsJsonObject().get("type").getAsString());
        ui.selLine = 0;
        ui.selElem = 1;
        ui.deleteElement();
        assertEquals(1, EditorUi.row(EditorUi.lines(s.current(), 0), 0).size());
        s.undo();                                                                 // удаление отменено (порядок — после переноса)
        assertEquals(2, EditorUi.row(EditorUi.lines(s.current(), 0), 0).size());
        s.undo();
        s.undo();
        s.undo();
        assertTrue(EditorUi.lines(s.current(), 0).isEmpty());                   // всё отменено
        s.redo();
        assertEquals(1, EditorUi.lines(s.current(), 0).size());
    }
}
