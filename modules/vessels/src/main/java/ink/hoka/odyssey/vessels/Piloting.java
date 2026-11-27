package ink.hoka.odyssey.vessels;

import net.minestom.server.entity.Player;

/** Пилот корабля: как было у игрока до посадки (вернуть при выходе) и счётчики проверки полёта. */
final class Piloting {

    final Player player;
    final Vessel vessel;
    final boolean allowFly, flying, invisible;
    final float flySpeed;
    /** Тик модуля, когда пришла последняя принятая поза. */
    long lastPose;
    /** Отклонено подряд (с затуханием) и за период — для журнала. */
    int strikes, rejected;
    /** Скорость корабля (блоков/тик) по последним принятым позам — для загрузки столбцов по курсу. */
    double vx, vy, vz;
    /** Множитель скорости для проверки полёта: улучшение сняли — старый предел держится ещё 5 с (мод тормозит не сразу). */
    double checkMul = 1;
    long checkMulAt;

    Piloting(Player player, Vessel vessel, long now) {
        this.player = player;
        this.vessel = vessel;
        this.allowFly = player.isAllowFlying();
        this.flying = player.isFlying();
        this.invisible = player.isInvisible();
        this.flySpeed = player.getFlyingSpeed();
        this.lastPose = now;
    }

    /** Вернуть игроку то, что было до посадки. */
    void restore() {
        player.setInvisible(invisible);
        player.setFlyingSpeed(flySpeed);
        player.setAllowFlying(allowFly || player.getGameMode() == net.minestom.server.entity.GameMode.CREATIVE
                || player.getGameMode() == net.minestom.server.entity.GameMode.SPECTATOR);
        player.setFlying(flying && player.isAllowFlying());
    }
}
