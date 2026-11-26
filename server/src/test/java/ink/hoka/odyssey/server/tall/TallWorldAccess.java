package ink.hoka.odyssey.server.tall;

import net.minestom.server.world.DimensionType;

/** Доступ тестов других пакетов к пакетным методам TallWorld. */
public final class TallWorldAccess {
    private TallWorldAccess() {}
    public static DimensionType overworld() { return TallWorld.overworld(); }
    public static DimensionType tall(DimensionType base, int minY, int height, boolean sun, float ambient) throws Exception {
        return TallWorld.tall(base, minY, height, sun, ambient);
    }
}
