package ink.hoka.odyssey.client.holo;

/**
 * Где и как повёрнута панель в мире: курс/наклон по режиму поворота, базис (вправо, вверх, нормаль к зрителю) и
 * попадание луча в панель → точки панели. Поворот — как у камеры игры: {@code rotationYXZ(π − курс, −наклон, 0)},
 * тогда «лицо» панели (+Z) смотрит на того, кто стоит с этим курсом. Без классов игры — проверяется тестами.
 */
public final class Orient {

    /** Курс и наклон (градусы) панели. */
    public final float yaw, pitch;
    /** Базис в мире: вправо, вверх, к зрителю. */
    public final double rx, ry, rz, ux, uy, uz, nx, ny, nz;

    public Orient(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        double a = Math.PI - Math.toRadians(yaw), b = -Math.toRadians(pitch);
        double sa = Math.sin(a), ca = Math.cos(a), sb = Math.sin(b), cb = Math.cos(b);
        rx = ca; ry = 0; rz = -sa;
        ux = sb * sa; uy = cb; uz = sb * ca;
        nx = cb * sa; ny = -sb; nz = cb * ca;
    }

    /** Поворот голограммы h для зрителя в (ex, ey, ez); cy — центр панели по высоте. */
    public static Orient of(Holo h, double cy, double ex, double ey, double ez) {
        if (h.facing == Holo.FIXED) return new Orient(h.yaw, h.pitch);
        double dx = h.x - ex, dy = cy - ey, dz = h.z - ez;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (h.facing == Holo.BILLBOARD_Y) return new Orient(yaw, 0);
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
        return new Orient(yaw, pitch);
    }

    /**
     * Луч (глаз e, направление d — единичное) → точка панели {u, v, расстояние} или null. Центр панели (cx, cy, cz),
     * размер w×h точек, k — блоков в точке. Сзади панель не ловится (как кнопки на одной стороне).
     */
    public double[] hit(double ex, double ey, double ez, double dx, double dy, double dz,
                        double cx, double cy, double cz, float w, float h, double k) {
        double den = dx * nx + dy * ny + dz * nz;
        if (den > -1e-6) return null;                                          // смотрим не на лицо панели
        double t = ((cx - ex) * nx + (cy - ey) * ny + (cz - ez) * nz) / den;
        if (t <= 0) return null;
        double px = ex + dx * t - cx, py = ey + dy * t - cy, pz = ez + dz * t - cz;
        double u = (px * rx + py * ry + pz * rz) / k + w / 2.0;
        double v = h / 2.0 - (px * ux + py * uy + pz * uz) / k;
        if (u < -2 || v < -2 || u > w + 2 || v > h + 2) return null;
        return new double[]{u, v, t};
    }

    /** Точка панели (u, v, z точек) → мир. */
    public double[] toWorld(double u, double v, double z, double cx, double cy, double cz, float w, float h, double k) {
        double lx = (u - w / 2.0) * k, ly = (h / 2.0 - v) * k, lz = z * k;
        return new double[]{cx + rx * lx + ux * ly + nx * lz, cy + ry * lx + uy * ly + ny * lz, cz + rz * lx + uz * ly + nz * lz};
    }
}
