package ink.hoka.odyssey.blocks;

import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.trait.CancellableEvent;
import net.minestom.server.event.trait.PlayerInstanceEvent;
import net.minestom.server.instance.Instance;

/**
 * ПКМ по своему блоку с окном ({@code window} в config.yml), например «верстак открыт». Позиция — главная клетка
 * (у многоклеточного блока — какую часть ни нажми). Слушать: {@code events().addListener(HoBlockUseEvent.class, …)}
 * (в module.yml — {@code softdepends: [blocks]}). Отменили — окно не откроется.
 */
public final class HoBlockUseEvent implements PlayerInstanceEvent, CancellableEvent {

    private final Player player;
    private final Instance instance;
    private final String id;
    private final BlockVec main;
    private final int rot;
    private boolean cancelled;

    public HoBlockUseEvent(Player player, Instance instance, String id, BlockVec main, int rot) {
        this.player = player;
        this.instance = instance;
        this.id = id;
        this.main = main;
        this.rot = rot;
    }

    @Override public Player getPlayer() { return player; }
    @Override public Instance getInstance() { return instance; }
    /** id своего блока (space_workbench). */
    public String id() { return id; }
    /** Главная клетка блока. */
    public BlockVec main() { return main; }
    /** Поворот блока: 0, 90, 180, 270. */
    public int rot() { return rot; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean c) { cancelled = c; }
}
