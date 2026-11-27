package ink.hoka.odyssey.vessels;

/**
 * Кватернион поворота (double, без JOML — на сервере его нет). Поворот корабля — система корабля → мир: нос — +Z
 * корабля, верх — +Y, левый борт — +X (как у старых кораблей и JOML у клиента: q·v·q⁻¹).
 */
record Quat(double x, double y, double z, double w) {

    static final Quat IDENTITY = new Quat(0, 0, 0, 1);

    /** Курс как у Minecraft (0 — нос на юг, +Z; 90 — на запад): поворот вокруг Y на −курс. */
    static Quat yaw(double yawDeg) {
        double a = Math.toRadians(-yawDeg) / 2;
        return new Quat(0, Math.sin(a), 0, Math.cos(a));
    }

    /** Поворот вокруг оси Y на угол (градусы, против часовой при взгляде сверху — как JOML rotateY). */
    static Quat aroundY(double deg) {
        double a = Math.toRadians(deg) / 2;
        return new Quat(0, Math.sin(a), 0, Math.cos(a));
    }

    double length() { return Math.sqrt(x * x + y * y + z * z + w * w); }

    boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Double.isFinite(w); }

    Quat normalized() {
        double l = length();
        return l == 0 || !Double.isFinite(l) ? IDENTITY : new Quat(x / l, y / l, z / l, w / l);
    }

    Quat conjugate() { return new Quat(-x, -y, -z, w); }

    /** this · o (сначала o, потом this). */
    Quat mul(Quat o) {
        return new Quat(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }

    /** Повернуть вектор. */
    double[] rotate(double vx, double vy, double vz) {
        // v' = v + 2w(q×v) + 2 q×(q×v)
        double cx = y * vz - z * vy, cy = z * vx - x * vz, cz = x * vy - y * vx;
        double ccx = y * cz - z * cy, ccy = z * cx - x * cz, ccz = x * cy - y * cx;
        return new double[]{vx + 2 * (w * cx + ccx), vy + 2 * (w * cy + ccy), vz + 2 * (w * cz + ccz)};
    }

    /** Угол между поворотами (градусы, 0…180). */
    double angleTo(Quat o) {
        double d = Math.abs(x * o.x + y * o.y + z * o.z + w * o.w) / Math.max(1e-12, length() * o.length());
        return Math.toDegrees(2 * Math.acos(Math.min(1, d)));
    }

    /** Курс носа (как у Minecraft) — для поворота игрока при выходе. */
    double yawDeg() {
        double[] f = rotate(0, 0, 1);
        if (Math.abs(f[0]) < 1e-9 && Math.abs(f[2]) < 1e-9) return 0;
        return Math.toDegrees(Math.atan2(-f[0], f[2]));
    }

    float[] toFloats() { return new float[]{(float) x, (float) y, (float) z, (float) w}; }
}
