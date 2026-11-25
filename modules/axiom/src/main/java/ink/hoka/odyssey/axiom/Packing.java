package ink.hoka.odyssey.axiom;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Координаты блоков в long и связь с высоким миром ядра.
 * <ul>
 *   <li>Ваниль: X 26 бит (сдвиг 38), Z 26 (сдвиг 12), Y 12.</li>
 *   <li>Высокий мир Hoka (TallWorld.enabled): X 24 бита (сдвиг 40), Z 24 (сдвиг 16), Y 16 — так пакует и мод клиента
 *       (BlockPosPackingMixin меняет BlockPos.asLong, BlockPosNetMixin — writeBlockPos на нашем сервере).</li>
 * </ul>
 * «net» — позиции, которые Axiom пишет writeBlockPos (set_block, рамки restrictions): формат как у ядра в пакетах.
 * «raw» — голые BlockPos.asLong (ключи секций, позиции блок-сущностей): по raw-pos из конфига.
 * Классы ядра (TallWorld, CubeStreamer) модулю видны в рантайме (родитель ModuleClassLoader — загрузчик ядра), но не
 * при компиляции (модуль собирается только против api) — поэтому отражением; нет класса — считаем мир ванильным.
 */
final class Packing {

    private Packing() {}

    /** BlockPos.asLong(-33554432, -2048, -33554432) ванильной упаковкой — метка конца списков у плагина. */
    static final long VANILLA_TERM = 0x8000002000000800L;
    /** То же нашей упаковкой: X и Z обнуляются по модулю 2^24, остаётся Y = −2048. */
    static final long TALL_TERM = 0x000000000000F800L;

    private static Field enabled;
    private static Method isCubes, inWindow;

    static void init(Logger log) {
        ClassLoader cl = Packing.class.getClassLoader();
        try {
            Class<?> tw = Class.forName("ink.hoka.odyssey.server.tall.TallWorld", true, cl);
            enabled = tw.getField("enabled");
            isCubes = tw.getMethod("isCubes", Instance.class);
        } catch (Throwable t) {
            enabled = null; isCubes = null;
            log.warn("TallWorld ядра не найден ({}) — координаты по-ванильному", t.toString());
        }
        try {
            Class<?> cs = Class.forName("ink.hoka.odyssey.server.tall.CubeStreamer", true, cl);
            inWindow = cs.getMethod("inWindow", Player.class, int.class);
        } catch (Throwable t) {
            inWindow = null;
        }
    }

    /** Высокий мир ядра включён (формат позиций в пакетах — 24/24/16). */
    static boolean tall() {
        try { return enabled != null && enabled.getBoolean(null); } catch (IllegalAccessException e) { return false; }
    }

    /** Кубовая карта (блоки у игрока — только в окне по высоте). */
    static boolean isCubes(Instance i) {
        try { return isCubes != null && (boolean) isCubes.invoke(null, i); } catch (Exception e) { return false; }
    }

    /** Блок по высоте в окне кубов игрока (на кубовой карте). Нет CubeStreamer — считаем, что да. */
    static boolean inWindow(Player p, int blockY) {
        try { return inWindow == null || (boolean) inWindow.invoke(null, p, blockY); } catch (Exception e) { return true; }
    }

    static boolean isTerm(long l) { return l == VANILLA_TERM || l == TALL_TERM; }

    static int x(long l, boolean tall) { return tall ? (int) (l >> 40) : (int) (l >> 38); }

    static int y(long l, boolean tall) { return tall ? (int) (l << 48 >> 48) : (int) (l << 52 >> 52); }

    static int z(long l, boolean tall) { return tall ? (int) (l << 24 >> 40) : (int) (l << 26 >> 38); }

    static long pack(int x, int y, int z, boolean tall) {
        return tall ? ((long) x & 0xFFFFFFL) << 40 | ((long) z & 0xFFFFFFL) << 16 | ((long) y & 0xFFFFL)
                : ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    /** SectionPos.asLong (X 22 | Z 22 | Y 20) — позиция секции в MultiBlockChangePacket; миксин мода её не трогает. */
    static long sectionPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFF);
    }
}
