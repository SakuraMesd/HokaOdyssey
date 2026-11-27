package ink.hoka.odyssey.client.vessels.hud;

/**
 * Куда рисует HUD и окно корабля: в игре — {@code McGfx} (GuiGraphics), в тестах — картинка PNG. Координаты — пиксели
 * интерфейса (GUI); картинки холста ({@link Canvas}) — один к одному в пикселях экрана, без растягивания.
 */
public interface Gfx {

    /** Размер экрана в пикселях интерфейса. */
    int width();

    int height();

    /** Масштаб интерфейса: пикселей экрана на пиксель интерфейса. */
    float scale();

    /** Прямоугольник (края — по пикселям экрана). */
    void fill(float x0, float y0, float x1, float y1, int argb);

    /** Холст key (левый верх — x, y) один к одному в пикселях экрана; alpha — общая прозрачность. */
    void image(String key, Canvas c, float x, float y, float alpha);

    /** Текст шрифтом игры; size — множитель (лучше через {@link Hud#crisp}); align −1 влево, 0 по центру, 1 вправо от x. */
    void text(String s, float x, float y, float size, int argb, int align, boolean shadow);

    /** Ширина текста в пикселях интерфейса при size 1. */
    int textWidth(String s);
}
