package ink.hoka.odyssey.blocks;

import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.trait.PlayerInstanceEvent;
import net.minestom.server.instance.Instance;

/**
 * Игрок вошёл в свой блок или вышел из него (поле {@code inside} в config.yml, например колба телепорта). Ноги игрока
 * проверяются раз в 5 тиков. Позиция — главная клетка блока. Слушать: {@code events().addListener(HoBlockInsideEvent.class, …)}
 * (в module.yml — {@code softdepends: [blocks]}).
 */
public final class HoBlockInsideEvent implements PlayerInstanceEvent {

    private final Player player;
    private final Instance instance;
    private final String id;
    private final BlockVec main;
    private final int rot;
    private final boolean entered;

    public HoBlockInsideEvent(Player player, Instance instance, String id, BlockVec main, int rot, boolean entered) {
        this.player = player;
        this.instance = instance;
        this.id = id;
        this.main = main;
        this.rot = rot;
        this.entered = entered;
    }

    @Override public Player getPlayer() { return player; }
    @Override public Instance getInstance() { return instance; }
    /** id своего блока (teleporter). */
    public String id() { return id; }
    /** Главная клетка блока. */
    public BlockVec main() { return main; }
    /** Поворот блока: 0, 90, 180, 270. */
    public int rot() { return rot; }
    /** true — вошёл, false — вышел. */
    public boolean entered() { return entered; }
}
