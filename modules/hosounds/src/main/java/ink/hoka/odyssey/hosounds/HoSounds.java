package ink.hoka.odyssey.hosounds;

import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

/**
 * Свои звуки для других модулей: {@code services().get(HoSounds.class)} (в module.yml — {@code softdepends: [hosounds]}).
 * id — ключ из config.yml модуля hosounds (в игре это звук {@code hoka:<id>}). Слышат игроки с модом Hoka Odyssey.
 * <pre>
 * sounds.play(player, "shot");                         // игроку, из его точки
 * sounds.playAt(instance, pos, "crate_open", 1, 1);     // всем рядом с точкой
 * </pre>
 */
public interface HoSounds {

    /** Игроку, из его места. */
    void play(Player p, String id);

    /** Игроку, из точки мира. */
    void play(Player p, String id, Point at, float volume, float pitch);

    /** Всем в мире, кто слышит эту точку. */
    void playAt(Instance instance, Point at, String id, float volume, float pitch);

    /** Остановить звук у игрока (id = null — все свои). */
    void stop(Player p, String id);

    /** Есть ли такой звук. */
    boolean exists(String id);
}
