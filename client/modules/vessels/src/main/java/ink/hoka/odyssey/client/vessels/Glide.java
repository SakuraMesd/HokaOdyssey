package ink.hoka.odyssey.client.vessels;

/**
 * Плавный путь корабля без управления пилота (vessels-6; без Minestom и игры — один и тот же файл у сервера и
 * клиентского модуля, сверяется тестом): спуск с неба на карте планеты и выход на орбиту в космосе. Путь — отрезок
 * from → to, доля пройденного s(u) по доле времени u = t / ticks. Сервер двигает по нему носитель (видят все), мод
 * пилота — свой корабль (те же числа — без рывков у пилота).
 */
final class Glide {

    /** Спуск: входит быстро (2·L/T), в конце скорость и ускорение — ноль (плавное торможение, подушке нечего гасить). */
    static final int DESCENT = 0;
    /** Выход на орбиту: скорость растёт линейно от {@link #EXIT_START} конечной до конечной (ускорение). */
    static final int EXIT = 1;
    static final double EXIT_START = 0.3;

    final int curve;
    final double[] from, to;
    final int ticks;

    Glide(int curve, double[] from, double[] to, int ticks) {
        this.curve = curve;
        this.from = from.clone();
        this.to = to.clone();
        this.ticks = Math.max(1, ticks);
    }

    /** Доля пути при доле времени u (0…1, вне — до края). */
    static double share(int curve, double u) {
        double t = Math.clamp(u, 0, 1);
        if (curve == EXIT) return (EXIT_START * t + (1 - EXIT_START) * t * t / 2) / ((1 + EXIT_START) / 2);
        return 2 * t - 2 * t * t * t + t * t * t * t;                           // 2u − 2u³ + u⁴ = 1 − (1 − u)³(1 + u)
    }

    /** Скорость: доля пути за долю времени (ds/du). */
    static double rate(int curve, double u) {
        double t = Math.clamp(u, 0, 1);
        if (curve == EXIT) return (EXIT_START + (1 - EXIT_START) * t) / ((1 + EXIT_START) / 2);
        return 2 * (1 - t) * (1 - t) * (1 + 2 * t);
    }

    /** Точка пути на тик t (0 — from, ticks и дальше — to). */
    double[] at(double t) {
        double s = share(curve, t / ticks);
        return new double[]{from[0] + (to[0] - from[0]) * s, from[1] + (to[1] - from[1]) * s, from[2] + (to[2] - from[2]) * s};
    }

    /** Скорость на тик t (блоков за тик); после конца — скорость конца. */
    double[] velocity(double t) {
        double k = rate(curve, Math.min(t, ticks) / ticks) / ticks;
        return new double[]{(to[0] - from[0]) * k, (to[1] - from[1]) * k, (to[2] - from[2]) * k};
    }

    /** Длина пути выхода, чтобы за ticks тиков разогнаться до endSpeed (блоков за тик). */
    static double exitLength(double endSpeed, int ticks) { return endSpeed * Math.max(1, ticks) * (1 + EXIT_START) / 2; }
}
