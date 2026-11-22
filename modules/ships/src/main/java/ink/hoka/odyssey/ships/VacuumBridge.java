package ink.hoka.odyssey.ships;

import net.minestom.server.entity.Player;

import java.lang.reflect.Method;

/**
 * Мост к модулю «Невесомость» (gravity, автор tearsmilk) через его открытый сервис VacuumService — без жёсткой
 * зависимости: если модуля нет, ничего не делаем. Пилоту на время полёта ставим режим NORMAL (вакуум не толкает
 * и не тянет к чёрным дырам — корабль сам себе двигатель), после посадки возвращаем прежний.
 */
final class VacuumBridge {

    private static final String SERVICE = "ink.hoka.odyssey.gravity.VacuumService";

    private final ShipsModule mod;

    VacuumBridge(ShipsModule mod) { this.mod = mod; }

    private Object service() {
        try {
            Class<?> c = Class.forName(SERVICE, true, VacuumBridge.class.getClassLoader());
            @SuppressWarnings({"unchecked", "rawtypes"})
            java.util.Optional<?> o = mod.services().get((Class) c);
            return o.orElse(null);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;                                                    // модуля невесомости нет
        } catch (Throwable e) {
            mod.logger().warn("невесомость: сервис недоступен: {}", e.toString());
            return null;
        }
    }

    /** Текущий режим пилота (имя enum: MAP / VACUUM / NORMAL) или null, если модуля нет. */
    String get(Player p) {
        Object s = service();
        if (s == null) return null;
        try {
            Method m = s.getClass().getMethod("override", Player.class);
            m.setAccessible(true);
            Object r = m.invoke(s, p);
            return r == null ? null : ((Enum<?>) r).name();
        } catch (Throwable e) {
            mod.logger().warn("невесомость: не прочитать режим {}: {}", p.getUsername(), e.toString());
            return null;
        }
    }

    /** Поставить режим по имени (null — ничего не делать). */
    void set(Player p, String mode) {
        if (mode == null) return;
        Object s = service();
        if (s == null) return;
        try {
            Class<?> modeCls = Class.forName(SERVICE + "$Mode", true, s.getClass().getClassLoader());
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object v = Enum.valueOf((Class) modeCls, mode);
            Method m = s.getClass().getMethod("override", Player.class, modeCls);
            m.setAccessible(true);
            m.invoke(s, p, v);
        } catch (Throwable e) {
            mod.logger().warn("невесомость: не поставить режим {} игроку {}: {}", mode, p.getUsername(), e.toString());
        }
    }
}
