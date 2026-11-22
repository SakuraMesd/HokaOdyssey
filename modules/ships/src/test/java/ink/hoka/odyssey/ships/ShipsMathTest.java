package ink.hoka.odyssey.ships;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Повороты, координаты корабля, чертёж. */
class ShipsMathTest {

    @BeforeAll
    static void init() { MinecraftServer.init(); }

    static ShipTemplate sample() {
        int sx = 5, sy = 3, sz = 3;
        Block[] b = new Block[sx * sy * sz];
        java.util.Arrays.fill(b, Block.AIR);
        ShipTemplate t0 = new ShipTemplate();
        t0.sx = sx; t0.sy = sy; t0.sz = sz;
        b[t0.index(0, 0, 0)] = Block.OAK_PLANKS;
        b[t0.index(4, 0, 2)] = Block.OAK_STAIRS.withProperty("facing", "north");
        b[t0.index(2, 1, 1)] = Block.OAK_LOG.withProperty("axis", "x");
        b[t0.index(1, 2, 0)] = Block.OAK_FENCE.withProperty("north", "true").withProperty("east", "true");
        ShipTemplate t = ShipTemplate.of("test", "junit", sx, sy, sz, b);
        t.seat = new int[]{2, 0, 1};
        return t;
    }

    @Test
    void worldLocalRoundTripAllRotations() {
        ShipTemplate t = sample();
        for (int r = 0; r < 4; r++) {
            Ship s = new Ship();
            s.t = t; s.rot = r; s.ax = 100; s.ay = 64; s.az = -50; s.state = Ship.State.PARKED;
            Set<Vec> seen = new HashSet<>();
            int[] size = s.size();
            for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++) {
                Vec w = s.world(x, y, z);
                assertTrue(w.x() >= 100 && w.x() < 100 + size[0] && w.z() >= -50 && w.z() < -50 + size[2], "внутри коробки r=" + r);
                assertTrue(seen.add(w), "клетки не совпадают r=" + r);
                assertArrayEquals(new int[]{x, y, z}, s.local(w), "обратно r=" + r);
            }
        }
    }

    @Test
    void rotationIsClockwise() {
        // чертёж 5 (x) × 3 (z); клетка в «северо-восточном» углу (x=4, z=0) после поворота на 90° по часовой
        // должна оказаться в «юго-восточном»: x = max, z = max
        int[] r = Rot.xz(4, 0, 5, 3, 1);
        int[] size = Rot.size(5, 3, 1);
        assertArrayEquals(new int[]{size[0] - 1, size[1] - 1}, r);
        assertEquals("east", Rot.dir("north", 1));
        assertEquals("north", Rot.dir("west", 1));
    }

    @Test
    void blockStatesRotate() {
        assertEquals("east", Rot.block(Block.OAK_STAIRS.withProperty("facing", "north"), 1).getProperty("facing"));
        assertEquals("z", Rot.block(Block.OAK_LOG.withProperty("axis", "x"), 1).getProperty("axis"));
        assertEquals("y", Rot.block(Block.OAK_LOG.withProperty("axis", "y"), 3).getProperty("axis"));
        Block f = Rot.block(Block.OAK_FENCE.withProperty("north", "true").withProperty("east", "false"), 1);
        assertEquals("true", f.getProperty("east"));
        assertEquals("false", f.getProperty("south"));
        assertEquals("4", Rot.block(Block.OAK_SIGN.withProperty("rotation", "0"), 1).getProperty("rotation"));
        Block back = Block.OAK_STAIRS.withProperty("facing", "west");
        for (int i = 0; i < 4; i++) back = Rot.block(back, 1);
        assertEquals("west", back.getProperty("facing"), "4 поворота — исходное");
    }

    @Test
    void templateSaveLoad(@TempDir Path tmp) throws Exception {
        ShipTemplate t = sample();
        t.chests.add(new int[]{0, 0, 0});
        t.zones.add(new int[]{0, 1, 0, 4, 2, 2});
        t.save(tmp.resolve("test.json"));
        ShipTemplate l = ShipTemplate.load(tmp.resolve("test.json"));
        assertEquals(t.sx, l.sx); assertEquals(t.sy, l.sy); assertEquals(t.sz, l.sz);
        for (int y = 0; y < t.sy; y++) for (int z = 0; z < t.sz; z++) for (int x = 0; x < t.sx; x++)
            assertEquals(t.block(x, y, z).state(), l.block(x, y, z).state(), x + " " + y + " " + z);
        assertEquals(4, l.solidCount());
        assertTrue(l.isChest(0, 0, 0));
        assertTrue(l.inZone(3, 2, 1));
        assertFalse(l.inZone(3, 0, 1));
    }

    @Test
    void yawMatchesQuaternion() {
        // поворот вектора Rot.yaw и кватернион Rot.quat должны крутить в одну сторону
        double deg = 90;
        double[] v = Rot.yaw(0, 1, deg);                                  // юг → запад
        assertEquals(-1, v[0], 1e-9); assertEquals(0, v[1], 1e-9);
        float[] q = Rot.quat(deg);
        // повернуть (0,0,1) кватернионом: v' = q v q*
        double qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        double x = 0, y = 0, z = 1;
        double ix = qw * x + qy * z - qz * y, iy = qw * y + qz * x - qx * z, iz = qw * z + qx * y - qy * x, iw = -qx * x - qy * y - qz * z;
        double rx = ix * qw + iw * -qx + iy * -qz - iz * -qy;
        double rz = iz * qw + iw * -qz + ix * -qy - iy * -qx;
        assertEquals(v[0], rx, 1e-6);
        assertEquals(v[1], rz, 1e-6);
    }

    /** Повернуть вектор кватернионом {x,y,z,w}. */
    private static double[] apply(float[] q, double[] v) {
        float[] p = {(float) v[0], (float) v[1], (float) v[2], 0f};
        float[] c = {-q[0], -q[1], -q[2], q[3]};
        float[] r = Rot.mul(Rot.mul(q, p), c);
        return new double[]{r[0], r[1], r[2]};
    }

    @Test
    void yawPitchOrientation() {
        for (double base : new double[]{0, 90, 180, -90})
            for (double yaw : new double[]{0, 37, 180, -135})
                for (double pitch : new double[]{0, 30, -60, 85}) {
                    double[] fwd = Rot.orient(0, 0, 1, base, 0);                 // перед модели
                    double[] got = apply(Rot.quat(yaw, pitch, base), fwd);
                    double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                    double[] look = {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};  // взгляд игрока
                    for (int k = 0; k < 3; k++) assertEquals(look[k], got[k], 1e-4, "base " + base + " yaw " + yaw + " pitch " + pitch);
                    double[] o = Rot.orient(0, 0, 1, yaw, pitch);
                    for (int k = 0; k < 3; k++) assertEquals(look[k], o[k], 1e-9);
                }
        // без наклона — как прежний поворот только по курсу
        float[] a = Rot.quat(70, 0, 20), b = Rot.quat(50);
        for (int k = 0; k < 4; k++) assertEquals(b[k], a[k], 1e-6);
    }

    @Test
    void turnIsSmoothAndConverges() {
        double ang = 0, rate = 0;
        for (int t = 0; t < 200; t++) {
            double prev = rate;
            rate = Flight.approach(rate, 170 - ang, 0.15, 3, 0.6);
            assertTrue(Math.abs(rate) <= 3 + 1e-9);
            assertTrue(Math.abs(rate - prev) <= 0.6 + 1e-9 || Math.abs(170 - ang) < 0.05);
            ang += rate;
        }
        assertEquals(170, ang, 0.05);
    }

    @org.junit.jupiter.api.Test
    void accelRateReaches90PercentInTime() {
        double v = 0, k = Flight.rate(3);
        for (int t = 0; t < 60; t++) v += (1 - v) * k;
        org.junit.jupiter.api.Assertions.assertEquals(0.9, v, 1e-6);
    }

    @org.junit.jupiter.api.Test
    void rollLowersRightWing() {
        float[] q = Rot.quatRoll(30);
        // повернуть (-1,0,0) кватернионом: v' = q v q*
        float[] v = {-1, 0, 0, 0};
        float[] qc = {-q[0], -q[1], -q[2], q[3]};
        float[] r = Rot.mul(Rot.mul(q, v), qc);
        org.junit.jupiter.api.Assertions.assertTrue(r[1] < -0.4, "правый борт должен опуститься: " + r[1]);
    }

    @org.junit.jupiter.api.Test
    void shipBasisIsOrthonormal() {
        for (double yaw = -180; yaw < 180; yaw += 17)
            for (double pitch = -85; pitch <= 85; pitch += 13) {
                double[] b = Flight.basis(yaw, pitch);
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++) {
                        double d = b[i * 3] * b[j * 3] + b[i * 3 + 1] * b[j * 3 + 1] + b[i * 3 + 2] * b[j * 3 + 2];
                        org.junit.jupiter.api.Assertions.assertEquals(i == j ? 1 : 0, d, 1e-9, "yaw " + yaw + " pitch " + pitch);
                    }
                org.junit.jupiter.api.Assertions.assertTrue(b[7] > 0, "верх смотрит вверх");
                double[] r = Rot.yaw(-1, 0, yaw);                          // правый борт совпадает с поворотом модели
                org.junit.jupiter.api.Assertions.assertEquals(r[0], b[3], 1e-9);
                org.junit.jupiter.api.Assertions.assertEquals(r[1], b[5], 1e-9);
            }
    }

    @org.junit.jupiter.api.Test
    void quatRotationMatchesOrient() {
        for (double yaw = -180; yaw < 180; yaw += 23)
            for (double pitch = -80; pitch <= 80; pitch += 20) {
                double[] a = Rot.orient(1.5, -2, 3.25, yaw, pitch);
                double[] b = Rot.rotate(Rot.mul(Rot.quat(yaw), Rot.quatPitch(pitch)), 1.5, -2, 3.25);
                for (int k = 0; k < 3; k++) org.junit.jupiter.api.Assertions.assertEquals(a[k], b[k], 1e-4, "yaw " + yaw + " pitch " + pitch);
            }
    }
}
