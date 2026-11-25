package ink.hoka.odyssey.axiom;

import java.util.ArrayList;
import java.util.List;

/** Состояние Axiom у одного игрока. Трогается из тика сервера (события пакетов и задачи модуля). */
final class Session {

    /** minecraft:register с нашими каналами отправлен (после первого появления в мире). */
    boolean registered;
    /** hello отправлен, ждём ответ с этим id. */
    boolean pending;
    long handshakeId;
    /** Axiom включён (enable=true ушёл). */
    boolean active;
    /** Без OP: goodbye уже сказан. */
    boolean goodbyeSent;

    static final int UNSET = Integer.MIN_VALUE;
    /** Бюджет секций ×20 (как у плагина: пополняется каждый тик на «в секунду»). UNSET — ещё не задан. */
    int sends20 = UNSET;

    /** Сборка частей туннеля. */
    final List<byte[]> parts = new ArrayList<>();
    int partsSize;
    boolean skipTunnel;

    boolean noPhysicalTrigger;

    /** Метка конца списков, которую прислал клиент (0 — ещё не видели). */
    long clientTerm;

    /** Последние отправленные restrictions (чтобы не слать одно и то же). */
    String restrictionsKey;

    void resetTunnel() {
        parts.clear();
        partsSize = 0;
        skipTunnel = false;
    }

    void deactivate() {
        active = false;
        pending = false;
        sends20 = UNSET;
        restrictionsKey = null;
        noPhysicalTrigger = false;
        resetTunnel();
    }
}
