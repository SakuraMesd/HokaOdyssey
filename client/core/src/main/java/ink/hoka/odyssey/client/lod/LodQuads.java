package ink.hoka.odyssey.client.lod;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Вершины дальней прорисовки (28 байт): Position (3 float, от угла плитки), Color (RGBA — оттенок × свет грани × доля
 * смешивания, всё ÷ 2: шейдер умножает на 2, чтобы цвет можно было и осветлить), UV0 (начало спрайта в атласе),
 * UV2 (ширина спрайта в пикселях + грань × 1024, высота спрайта). Текстура повторяется по блокам в шейдере: большой
 * прямоугольник из одинаковых блоков выглядит как блоки, а не как растянутая картинка. Память вне кучи.
 */
final class LodQuads {

    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV2", VertexFormatElement.UV2)
            .build();
    static final int STRIDE = 28, QUAD = STRIDE * 4;

    /** Свет граней как у игры (cardinal lighting): низ 0.5, верх 1, север/юг 0.8, запад/восток 0.6. */
    static final float[] SHADE = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};

    ByteBuffer b;
    int quads;
    float yMin = Float.MAX_VALUE, yMax = -Float.MAX_VALUE;

    LodQuads(int hint) { b = MemoryUtil.memAlloc(Math.max(16, hint) * QUAD); }

    /**
     * Четырёхугольник грани face (Direction.ordinal) с текстурой f. r, g, b — множители цвета 0…2 (оттенок × свет ×
     * смешивание), a — непрозрачность 0…1.
     */
    void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
              LodBlocks.Face f, int face, float r, float g, float bl, float a) {
        if (b.remaining() < QUAD) b = MemoryUtil.memRealloc(b, b.capacity() * 2);
        int rr = c255(r * 0.5f), gg = c255(g * 0.5f), bb = c255(bl * 0.5f), aa = c255(a);
        int rgba = aa << 24 | bb << 16 | gg << 8 | rr;                        // в памяти: R, G, B, A
        int w = Math.min(1023, f.w()) | face << 10, h = Math.min(32767, f.h());
        v(ax, ay, az, rgba, f, w, h);
        v(bx, by, bz, rgba, f, w, h);
        v(cx, cy, cz, rgba, f, w, h);
        v(dx, dy, dz, rgba, f, w, h);
        quads++;
        float lo = Math.min(Math.min(ay, by), Math.min(cy, dy)), hi = Math.max(Math.max(ay, by), Math.max(cy, dy));
        if (lo < yMin) yMin = lo;
        if (hi > yMax) yMax = hi;
    }

    private void v(float x, float y, float z, int rgba, LodBlocks.Face f, int w, int h) {
        b.putFloat(x).putFloat(y).putFloat(z).putInt(rgba).putFloat(f.u0()).putFloat(f.v0()).putShort((short) w).putShort((short) h);
    }

    private static int c255(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    /** Готовый буфер (или null, если пусто — память освобождена). */
    ByteBuffer finish() {
        if (quads == 0) { MemoryUtil.memFree(b); b = null; return null; }
        b.flip();
        return b;
    }

    void free() { if (b != null) { MemoryUtil.memFree(b); b = null; } }

    /** Цвет оттенка tint (RGB) × свет shade × смешивание (mr, mg, mb) → множители 0…2. */
    static float[] mul(int tint, float shade, float mr, float mg, float mb) {
        return new float[]{(tint >> 16 & 255) / 255f * shade * mr, (tint >> 8 & 255) / 255f * shade * mg, (tint & 255) / 255f * shade * mb};
    }
}
