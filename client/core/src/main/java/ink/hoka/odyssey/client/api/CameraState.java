package ink.hoka.odyssey.client.api;

/**
 * Камера кадра — модуль может поменять любые поля в {@link ClientContext.CameraHook}. Углы — в градусах, как у
 * Minecraft: курс (yaw) — 0 юг, 90 запад; наклон (pitch) + — вниз; крен (roll) + — по часовой (правый борт вниз).
 * Крен — настоящий: видимость (отсечение) считается уже с ним.
 */
public final class CameraState {
    public double x, y, z;
    public float yaw, pitch, roll;
    /** Камера от 3-го лица (F5) — модуль может решить, что делать. */
    public boolean detached;
}
