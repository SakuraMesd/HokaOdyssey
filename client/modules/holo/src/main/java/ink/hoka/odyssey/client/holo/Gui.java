package ink.hoka.odyssey.client.holo;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * Куда рисует редактор ({@link EditorUi}): в игре — GuiGraphics ({@link EditorScreen}), в превью PNG — Java2D.
 * Координаты — пиксели GUI, цвета — ARGB.
 */
public interface Gui {

    int width(String s);

    void fill(float x0, float y0, float x1, float y1, int argb);

    void text(String s, float x, float y, int argb);

    /** Строка голограммы (куски по стилю), масштаб s, прозрачность. */
    void runs(List<Mini.Run> runs, float x, float y, float s, float alpha);

    /** Иконка предмета по id (размер — сторона в пикселях). */
    void item(String key, float x, float y, float size);

    void image(String name, float x, float y, float w, float h, float alpha);

    void push(float tx, float ty, float s);

    void pop();

    void clip(int x0, int y0, int x1, int y1);

    void unclip();

    /** Что редактору нужно от игры (в тестах — заглушка). */
    interface Host {
        Session session();

        JsonObject info();

        Map<String, Holo.Theme> themes();

        Layout.Metrics metrics();

        Map<String, String> values();

        /** Карта игрока. */
        String map();

        /** Глаза игрока и взгляд: x, y, z, курс, наклон. */
        double[] player();

        /** Блок под прицелом: точка и нормаль грани (6 чисел) или null. */
        double[] blockTarget();

        /** Предмет в руке («hoka:id» или «minecraft:id»), null — пусто. */
        String handItem();

        /** Подсказки id предметов (ванильные и свои). */
        List<String> itemSuggest(String prefix);

        void delete(String id);

        void tp(String id);

        void saveTemplate(String name, JsonObject holo);

        void deleteTemplate(String name);

        void move(String id);

        void close();

        String clipboard();

        void setClipboard(String s);

        /** Последний ответ сервера на правку и успех. */
        String status();

        boolean statusOk();
    }
}
