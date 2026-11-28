package ink.hoka.odyssey.holo;

import ink.hoka.odyssey.holo.Hologram.ActionType;
import ink.hoka.odyssey.holo.Hologram.Element;
import ink.hoka.odyssey.holo.Hologram.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Голограммы: JSON (все типы, страницы), файл, плейсхолдеры (подстановка, частота, только изменения), нажатия. */
class HoloTest {

    static final String FULL = """
            {"id": "demo", "map": "cosmos", "pos": [10.5, 70, -3.25], "yaw": 90, "pitch": -10, "facing": "fixed",
             "scale": 1.5, "theme": "amber", "width": 3, "padding": 0.2, "background": false, "opacity": 0.5,
             "frame": false, "hover": false, "range": 48, "view": "op", "creator_op": true,
             "left": [{"type": "msg", "value": "панель"}],
             "pages": [
               {"lines": [
                 [{"type": "text", "text": "<b>Привет, {player}</b>", "size": 2, "align": "left"}],
                 [{"type": "item", "item": "hoka:ruby", "spin": false, "size": 1.5, "hint": "Рубин"},
                  {"type": "image", "image": "logo.png", "w": 1, "h": 0.5}],
                 [{"type": "bar", "value": "{fuel}", "max": 100, "color": "#FF0000", "label": "Топливо", "w": 2}],
                 [{"type": "button", "label": "Жми", "cond": "perm:holo.use", "cooldown": 20,
                   "left": [{"type": "cmd", "value": "spawn"}, {"type": "console", "value": "say {player}"}],
                   "right": [{"type": "page", "value": "next"}]}],
                 [{"type": "separator"}],
                 [{"type": "spacer", "h": 0.3}]]},
               {"lines": [[{"type": "text", "text": "вторая"}]]}]}
            """;

    @Test
    void parseAllTypesAndRoundTrip() {
        Hologram h = HoloCodec.parse(FULL);
        assertEquals("cosmos", h.map);
        assertEquals(-3.25, h.z);
        assertEquals(Hologram.Facing.FIXED, h.facing);
        assertEquals(Hologram.View.OP, h.view);
        assertFalse(h.background);
        assertTrue(h.creatorOp);
        assertEquals(2, h.pages.size());
        assertEquals(6, h.pages.get(0).lines.size());
        Element item = h.element(0, 1, 0);
        assertEquals(Type.ITEM, item.type);
        assertFalse(item.spin);
        assertEquals("Рубин", item.hint);
        assertEquals(Type.IMAGE, h.element(0, 1, 1).type);
        assertEquals(100, h.element(0, 2, 0).max);
        Element btn = h.element(0, 3, 0);
        assertEquals("perm:holo.use", btn.cond);
        assertEquals(20, btn.cooldown);
        assertEquals(ActionType.CONSOLE, btn.left.get(1).type());
        assertEquals(ActionType.PAGE, btn.right.get(0).type());
        assertEquals(Type.SEPARATOR, h.element(0, 4, 0).type);
        assertEquals(0.3, h.element(0, 5, 0).h);
        assertEquals(Set.of("player", "fuel"), h.placeholders());

        String again = HoloCodec.compact(HoloCodec.parse(HoloCodec.compact(h)));
        assertEquals(HoloCodec.compact(h), again);                               // запись → разбор → запись — то же
    }

    @Test
    void strictValidation() {
        assertThrows(IllegalArgumentException.class, () -> HoloCodec.parse("{\"id\": \"Bad Id\", \"pos\": [0,0,0]}"));
        assertThrows(IllegalArgumentException.class, () -> HoloCodec.parse("{\"id\": \"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> HoloCodec.parse("{\"id\": \"a\", \"pos\": [0,0,0], \"scale\": 99}"));
        assertThrows(IllegalArgumentException.class, () -> HoloCodec.parse(
                "{\"id\": \"a\", \"pos\": [0,0,0], \"pages\": [{\"lines\": [[{\"type\": \"video\"}]]}]}"));
        assertThrows(IllegalArgumentException.class, () -> HoloCodec.parse(
                "{\"id\": \"a\", \"pos\": [0,0,0], \"pages\": [{\"lines\": [[{\"type\": \"text\", \"size\": 9}]]}]}"));
        assertThrows(IllegalArgumentException.class, () -> HoloCodec.parse(
                "{\"id\": \"a\", \"pos\": [0,0,0], \"pages\": [{\"lines\": [[{\"type\": \"image\", \"image\": \"../x.png\"}]]}]}"));
        assertThrows(IllegalArgumentException.class, () -> HoloCodec.parse(
                "{\"id\": \"a\", \"pos\": [0,0,0], \"pages\": [{\"lines\": [[{\"type\": \"button\", \"left\": [{\"type\": \"rm\"}]}]]}]}"));
        Hologram empty = HoloCodec.parse("{\"id\": \"a\", \"pos\": [0,0,0]}");
        assertEquals(1, empty.pages.size());                                     // всегда хотя бы одна страница
        assertFalse(empty.creatorOp);
    }

    @Test
    void fileAtomicAndBrokenKept(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("holograms.json");
        HoloStore s = new HoloStore(f);
        assertTrue(s.load().isEmpty());
        Hologram a = HoloCodec.parse(FULL);
        Hologram t = HoloCodec.copy(a, "temp");
        t.temporary = true;
        s.write(HoloStore.serialize(List.of(a, t)));
        List<Hologram> back = s.load();
        assertEquals(1, back.size());                                            // временная не пишется
        assertEquals(HoloCodec.compact(a), HoloCodec.compact(back.get(0)));
        assertFalse(Files.exists(dir.resolve("holograms.json.tmp")));

        Files.writeString(f, "{ broken");
        assertThrows(HoloStore.Broken.class, s::load);
        assertEquals("{ broken", Files.readString(f));                           // битый файл не трогаем

        Files.writeString(f, "{\"holograms\": [" + HoloCodec.compact(a) + "," + HoloCodec.compact(a) + "]}");
        assertThrows(HoloStore.Broken.class, s::load);                           // два одинаковых id

        HoloStore ts = new HoloStore(dir.resolve("templates.json"));
        ts.write(HoloStore.serializeTemplates(Templates.builtIn()));
        assertEquals(Templates.builtIn().keySet(), ts.loadTemplates().keySet());
    }

    record P(String name) {}

    @Test
    void placeholdersPeriodAndOnlyChanges() {
        Placeholders<P, String> ph = new Placeholders<>(P::name);
        AtomicInteger calls = new AtomicInteger();
        int[] fuel = {5};
        ph.register("fuel", 10, "ships", p -> { calls.incrementAndGet(); return String.valueOf(fuel[0]); });
        ph.register("player", 100, "holo", P::name);
        P bob = new P("bob");
        Set<String> want = Set.of("fuel", "player");

        assertEquals(Map.of("fuel", "5", "player", "bob"), ph.due(bob, want, 1, true));
        assertEquals(Map.of(), ph.due(bob, want, 2, false));                     // период не подошёл — не считали
        assertEquals(1, calls.get());
        assertEquals(Map.of(), ph.due(bob, want, 10, false));                    // подошёл, но не изменилось — не шлём
        assertEquals(2, calls.get());
        fuel[0] = 7;
        assertEquals(Map.of(), ph.due(bob, want, 15, false));
        assertEquals(Map.of("fuel", "7"), ph.due(bob, want, 20, false));         // изменилось — только оно
        assertEquals(Map.of(), ph.due(bob, Set.of(), 30, false));                // ничего не нужно — ничего не считаем

        assertEquals("топливо 7 у bob, {nope}", ph.fill(bob, "топливо {fuel} у {player}, {nope}"));
        ph.forget("bob");
        assertEquals(Map.of("fuel", "7"), ph.due(bob, Set.of("fuel"), 21, false));   // забыли — пришлём снова
        ph.unregisterOwner("ships");
        assertFalse(ph.has("fuel"));
        assertThrows(IllegalArgumentException.class, () -> ph.register("bad name", 1, "x", p -> ""));
    }

    static ClickGuard.Who who(String map, double x, double y, double z, boolean op, String... perms) {
        Set<String> ps = Set.of(perms);
        return new ClickGuard.Who() {
            public String map() { return map; }
            public double x() { return x; }
            public double y() { return y; }
            public double z() { return z; }
            public boolean op() { return op; }
            public boolean perm(String node) { return ps.contains(node); }
        };
    }

    @Test
    void clickChecks() {
        Hologram h = HoloCodec.parse(FULL);                                      // view: op, кнопка: perm:holo.use, cd 20
        ClickGuard g = new ClickGuard(8, 4);
        var near = who("cosmos", 11, 70, -3, false, "holo.use");
        assertEquals(ClickGuard.Verdict.HIDDEN, g.check(h, "a", near, 0, 3, 0, false, 0).verdict());
        h.view = Hologram.View.ALL;
        assertEquals(ClickGuard.Verdict.NO_HOLO, g.check(null, "a", near, 0, 3, 0, false, 0).verdict());
        assertEquals(ClickGuard.Verdict.OTHER_MAP, g.check(h, "a", who("world", 11, 70, -3, true), 0, 3, 0, false, 0).verdict());
        assertEquals(ClickGuard.Verdict.TOO_FAR, g.check(h, "a", who("cosmos", 10.5 + 57, 70, -3.25, true), 0, 3, 0, false, 0).verdict());
        assertEquals(ClickGuard.Verdict.NO_ELEMENT, g.check(h, "a", near, 0, 99, 0, false, 0).verdict());
        assertEquals(ClickGuard.Verdict.NOT_CLICKABLE, g.check(h, "a", near, 0, 0, 0, false, 0).verdict());
        assertEquals(ClickGuard.Verdict.DENIED, g.check(h, "b", who("cosmos", 11, 70, -3, false), 0, 3, 0, false, 0).verdict());

        var ok = g.check(h, "a", near, 0, 3, 0, false, 100);
        assertEquals(ClickGuard.Verdict.OK, ok.verdict());
        assertEquals(2, ok.actions().size());                                    // создал OP — консоль можно
        assertEquals(ClickGuard.Verdict.COOLDOWN, g.check(h, "a", near, 0, 3, 0, false, 110).verdict());
        assertEquals(ClickGuard.Verdict.OK, g.check(h, "a", near, 0, 3, 0, true, 110).verdict());   // ПКМ — своя задержка
        assertEquals(ClickGuard.Verdict.OK, g.check(h, "a", near, 0, 3, 0, false, 120).verdict());

        h.creatorOp = false;                                                     // не OP — консольные отброшены
        var cut = g.check(h, "c", near, 0, 3, 0, false, 200);
        assertEquals(List.of(ActionType.CMD), cut.actions().stream().map(Hologram.Action::type).toList());

        assertEquals(ClickGuard.Verdict.OK, g.check(h, "a", near, 0, 0, -1, false, 300).verdict());  // клик по панели
        assertEquals(ClickGuard.Verdict.NOT_CLICKABLE, g.check(h, "a", near, 0, 0, -1, true, 300).verdict());
    }

    @Test
    void builtInTemplatesParse() {
        Map<String, Hologram> t = new HashMap<>(Templates.builtIn());
        assertEquals(3, t.size());
        assertEquals(2, t.get("меню с кнопками").pages.size());
        assertTrue(t.get("информация со шкалой").placeholders().containsAll(Set.of("x", "y", "z")));
    }

    @Test
    void miniLite() {
        var c = MiniLite.parse("<b><#FF0000>red</#FF0000></b> plain <gradient:#00FF00:#0000FF>g</gradient> a<b");
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        assertEquals("red plain g a<b", plain);
    }
}
