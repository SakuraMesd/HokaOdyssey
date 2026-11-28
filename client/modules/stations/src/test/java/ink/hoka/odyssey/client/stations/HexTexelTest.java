package ink.hoka.odyssey.client.stations;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** Текстура сетки: шов повтора без разрывов (края периода совпадают), линии есть, середина — пусто. */
class HexTexelTest {

    @Test void tilesSeamless() throws Exception {
        var st = new BorderMesh.Style();
        double half = 0.03;
        int W = 128, H = (int) Math.round(W * BorderMesh.texPeriodV(st) / BorderMesh.texPeriodU(st));
        for (int y = 0; y < H; y++)
            assertEquals(BorderMesh.hexTexel(0.0001, (y + 0.5) / H, st, half), BorderMesh.hexTexel(0.9999, (y + 0.5) / H, st, half), 0.05);
        for (int x = 0; x < W; x++)
            assertEquals(BorderMesh.hexTexel((x + 0.5) / W, 0.0001, st, half), BorderMesh.hexTexel((x + 0.5) / W, 0.9999, st, half), 0.05);
        assertEquals(0, BorderMesh.hexTexel(0, 0, st, half), 1e-6, "центр шестигранника — пусто");
        var img = new BufferedImage(W * 3, H * 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H * 3; y++) for (int x = 0; x < W * 3; x++) {
            int g = Math.round(255 * BorderMesh.hexTexel(((x % W) + 0.5) / W, ((y % H) + 0.5) / H, st, half));
            img.setRGB(x, y, (g << 16) | (g << 8) | g);
        }
        File out = new File(System.getProperty("java.io.tmpdir"), "hexgrid-preview.png");
        javax.imageio.ImageIO.write(img, "png", out);
        System.out.println("превью: " + out);
    }
}
