package ink.hoka.odyssey.client.vessels.hud;

/**
 * Что показать в HUD редактора камер (vessels-5): область (вид / корабль), список камер с состоянием, текущая камера
 * и её числа (система корабля), строка состояния.
 *
 * @param states  по камере: биты {@link #SAVED} — сохранена в этой области, {@link #INHERITED} — у корабля от вида,
 *                {@link #DIRTY} — есть несохранённая правка, {@link #LOCKED} — пока нельзя (виды F5 — по порядку)
 * @param fov     FOV камеры (0 — как у игрока: fovNow)
 * @param smooth  плавность вида F5 (NaN — первое лицо, у него плавности нет)
 * @param exists  текущая камера уже сохранена (правим существующую)
 */
public record EditorView(String title, boolean shipScope, String[] names, int[] states, int which,
                         double x, double y, double z, double yaw, double pitch, double fov, double fovNow, double speed,
                         double smooth, boolean look, boolean exists, String status, boolean statusWarn, float statusAlpha) {

    public static final int SAVED = 1, INHERITED = 2, DIRTY = 4, LOCKED = 8;
}
