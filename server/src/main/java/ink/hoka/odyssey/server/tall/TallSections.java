package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.Section;

import java.util.AbstractList;
import java.util.RandomAccess;

/**
 * Разреженный список секций высокого столбца (CUBIC-CHUNKS.md §4.1, этап 4а): 1876 секций, почти все пустые —
 * вместо объекта Section (~212 байт) на каждую пустая отдаётся ОДНА общая {@link #EMPTY}. Своя секция создаётся
 * при записи ({@link #materialize}): TallChunk.setBlock/setBiome, TallLoader, своя генерация. Генератор Minestom
 * на высоких картах не используется — он пишет прямо в палитры всех секций (испортил бы общую).
 */
final class TallSections extends AbstractList<Section> implements RandomAccess {

    /** Общая пустая секция: менять нельзя. Если в ней появились блоки — кто-то пишет мимо materialize (/hocubes stats). */
    static final Section EMPTY = new Section();

    private final Section[] arr;

    TallSections(int size) { arr = new Section[size]; }

    @Override public Section get(int i) { Section s = arr[i]; return s == null ? EMPTY : s; }
    @Override public int size() { return arr.length; }
    @Override public Section set(int i, Section s) { Section old = get(i); arr[i] = s == EMPTY ? null : s; return old; }

    /** Своя секция по индексу (создать, если была общая пустая). */
    Section materialize(int i) {
        Section s = arr[i];
        if (s == null) arr[i] = s = new Section();
        return s;
    }

    /**
     * Секция снова пустая (сломали все блоки) и биомы как у общей — отдать память, вернуть общую пустую.
     * Свет у нас считается из палитр (TallChunk), в Section он не хранится — терять нечего.
     */
    boolean release(int i) {
        Section s = arr[i];
        if (s == null || s.blockPalette().count() != 0) return false;
        int bio = s.biomePalette().singleValue();
        if (bio < 0 || bio != EMPTY.biomePalette().singleValue()) return false;   // свои биомы — оставить
        arr[i] = null;
        return true;
    }

    boolean isReal(int i) { return arr[i] != null; }

    /** Сколько своих объектов секций (для отладки). */
    int real() { int n = 0; for (Section s : arr) if (s != null) n++; return n; }

    /** Общая пустая секция испорчена записью мимо materialize. */
    static boolean poisoned() { return EMPTY.blockPalette().count() != 0; }
}
