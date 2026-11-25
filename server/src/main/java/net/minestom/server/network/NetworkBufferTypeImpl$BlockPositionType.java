package net.minestom.server.network;

import ink.hoka.odyssey.server.tall.TallWorld;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Vec;

/**
 * ЗАМЕНА класса Minestom (тот же двоичный адрес: NetworkBufferTypeImpl$BlockPositionType) — координата блока в
 * пакете. Ваниль: X 26 бит, Z 26 бит, Y 12 бит (высота −2048…2047). Высокий мир Hoka (TallWorld.enabled): X/Z по 24
 * бита, Y 16 бит — как у мода Hoka Odyssey 0.27+. Класс из jar сервера грузится вместо класса Minestom (shadow берёт
 * наш первым) — после обновления Minestom сверить, что оригинал не изменился.
 */
public record NetworkBufferTypeImpl$BlockPositionType() implements NetworkBufferTypeImpl<Point> {

    @Override
    public void write(NetworkBuffer buffer, Point value) {
        int x = value.blockX(), y = value.blockY(), z = value.blockZ();
        long l = TallWorld.enabled
                ? ((long) x & 0xFFFFFFL) << 40 | ((long) z & 0xFFFFFFL) << 16 | ((long) y & 0xFFFFL)
                : ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
        buffer.write(NetworkBuffer.LONG, l);
    }

    @Override
    public Point read(NetworkBuffer buffer) {
        long l = buffer.read(NetworkBuffer.LONG);
        if (TallWorld.enabled) return new Vec((int) (l >> 40), (int) (l << 48 >> 48), (int) (l << 24 >> 40));
        return new Vec((int) (l >> 38), (int) (l << 52 >> 52), (int) (l << 26 >> 38));
    }
}
