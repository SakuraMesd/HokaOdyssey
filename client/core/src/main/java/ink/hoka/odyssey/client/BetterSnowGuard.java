package ink.hoka.odyssey.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.RandomAccess;

/**
 * 0.50: BetterGrassify (ставится сборкой Fabulously Optimized), функция «Better Snow». Его миксин в сборку сетки
 * Sodium на КАЖДЫЙ непустой блок зовёт {@code BetterSnowUtils.getLayerNeighbour}: по каждому «снежному слою» из
 * конфига — массив и 4 новых BlockPos, 4 чтения блока. По профилю f49 это 64% времени сборщиков сеток и ~95% всего
 * мусора клиента (сборка мусора 5–8 раз в секунду → кадры 6–12 мс). Режим OFF в его настройках не помогает: метод
 * проверяет режим уже после цикла.
 * <p>Без миксина на чужой код (и без CallbackInfoReturnable на горячем пути): кэш слоёв
 * {@code BetterGrassifyCacheUtils.BETTER_SNOW_CACHE} — публичное поле; подменяем его обёрткой, которая на нашем сервере
 * ({@link TallWorld#net}) для перебора пуста (цикл не выполняется, метод сразу отдаёт null — обработчик BetterGrassify
 * при null ничего не делает), а для проверки «кэш пуст?» отвечает «нет» (иначе initCaches перечитывал бы конфиг на
 * каждый блок). Вне нашего сервера — всё как у мода. Цена: на нашем сервере нет «призрачных» слоёв снега/мха у
 * соседних блоков (косметика). Мода нет / устроен иначе — ничего не делаем.</p>
 */
public final class BetterSnowGuard {

    private BetterSnowGuard() {}

    private static final Logger LOG = LoggerFactory.getLogger("HokaOdyssey");
    private static volatile boolean done;

    /** Поставить обёртку (идемпотентно, любой поток). */
    public static void install() {
        if (done) return;
        synchronized (BetterSnowGuard.class) {
            if (done) return;
            done = true;
            try {
                Class<?> k = Class.forName("dev.ultimatchamp.bettergrass.util.BetterGrassifyCacheUtils", true,
                        BetterSnowGuard.class.getClassLoader());
                Field f = k.getField("BETTER_SNOW_CACHE");
                Object cur = f.get(null);
                if (cur instanceof Guarded || !(cur instanceof List<?> l)) return;
                @SuppressWarnings("unchecked") List<Object> d = (List<Object>) l;
                f.set(null, new Guarded(d));
                LOG.info("BetterGrassify: «Better Snow» на сервере Hoka выключен (сборка сеток без лишних аллокаций)");
            } catch (ClassNotFoundException e) {
                // мода нет
            } catch (Throwable t) {
                LOG.warn("BetterGrassify: не вышло выключить «Better Snow» ({})", t.toString());
            }
        }
    }

    /** Кэш слоёв: на нашем сервере перебор пуст, но «не пуст» для initCaches. Остальное — как у оригинала. */
    static final class Guarded extends AbstractList<Object> implements RandomAccess {
        private final List<Object> d;

        Guarded(List<Object> d) { this.d = d; }

        @Override public Iterator<Object> iterator() { return TallWorld.net ? Collections.emptyIterator() : d.iterator(); }
        @Override public boolean isEmpty() { return !TallWorld.net && d.isEmpty(); }
        @Override public Object get(int i) { return d.get(i); }
        @Override public int size() { return d.size(); }
        @Override public Object set(int i, Object o) { return d.set(i, o); }
        @Override public void add(int i, Object o) { d.add(i, o); }
        @Override public boolean add(Object o) { return d.add(o); }
        @Override public Object remove(int i) { return d.remove(i); }
        @Override public void clear() { d.clear(); }
        @Override public boolean contains(Object o) { return d.contains(o); }
    }
}
