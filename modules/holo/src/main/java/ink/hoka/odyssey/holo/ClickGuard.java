package ink.hoka.odyssey.holo;

import ink.hoka.odyssey.holo.Hologram.Action;
import ink.hoka.odyssey.holo.Hologram.ActionType;
import ink.hoka.odyssey.holo.Hologram.Element;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Проверка нажатия на сервере (мод может прислать что угодно): голограмма на карте игрока, видна ему, игрок
 * близко, элемент существует и кликабелен, условие (OP/право) выполнено, задержка прошла. Действия «от консоли»
 * отбрасываются, если голограмму создал не OP. Без Minestom — тестируется напрямую.
 */
public final class ClickGuard {

    /** Что знает сервер об игроке в момент нажатия. */
    public interface Who {
        String map();
        double x();
        double y();
        double z();
        boolean op();
        boolean perm(String node);
    }

    public enum Verdict { OK, NO_HOLO, OTHER_MAP, HIDDEN, TOO_FAR, NO_ELEMENT, NOT_CLICKABLE, DENIED, COOLDOWN }

    /** Итог: вердикт и действия, которые можно выполнять (консольные уже отброшены, если нельзя). */
    public record Result(Verdict verdict, List<Action> actions) {
        static Result of(Verdict v) { return new Result(v, List.of()); }
    }

    private final Map<String, Long> last = new ConcurrentHashMap<>();
    private final double reach;
    private final int defaultCooldown;

    public ClickGuard(double reach, int defaultCooldown) {
        this.reach = reach;
        this.defaultCooldown = defaultCooldown;
    }

    public static boolean visible(Hologram h, Who w) {
        return switch (h.view) {
            case ALL -> true;
            case OP -> w.op();
            case PERM -> w.op() || w.perm("holo.view." + h.id);
        };
    }

    static boolean allowed(String cond, Who w) {
        if (cond == null || cond.equals("all")) return true;
        if (cond.equals("op")) return w.op();
        if (cond.startsWith("perm:")) return w.op() || w.perm(cond.substring(5));
        return false;
    }

    /**
     * @param who    ключ игрока (для задержки)
     * @param elem   номер элемента в ряду; −1 — клик по самой панели
     * @param right  ПКМ
     * @param tick   текущий тик сервера
     */
    public Result check(Hologram h, String who, Who w, int page, int line, int elem, boolean right, long tick) {
        if (h == null) return Result.of(Verdict.NO_HOLO);
        if (!h.map.equals(w.map())) return Result.of(Verdict.OTHER_MAP);
        if (!visible(h, w)) return Result.of(Verdict.HIDDEN);
        double max = (h.range + reach);
        if (h.distSq(w.x(), w.y(), w.z()) > max * max) return Result.of(Verdict.TOO_FAR);
        List<Action> acts;
        String cond;
        int cd;
        if (elem < 0) {
            acts = right ? h.right : h.left;
            cond = "all";
            cd = defaultCooldown;
        } else {
            Element e = h.element(page, line, elem);
            if (e == null) return Result.of(Verdict.NO_ELEMENT);
            acts = right ? e.right : e.left;
            cond = e.cond;
            cd = e.cooldown >= 0 ? e.cooldown : defaultCooldown;
        }
        if (acts.isEmpty()) return Result.of(Verdict.NOT_CLICKABLE);
        if (!allowed(cond, w)) return Result.of(Verdict.DENIED);
        String k = who + '|' + h.id + '|' + page + '|' + line + '|' + elem + (right ? "|r" : "|l");
        Long prev = last.get(k);
        if (prev != null && tick - prev < cd) return Result.of(Verdict.COOLDOWN);
        last.put(k, tick);
        List<Action> ok = h.creatorOp ? acts : acts.stream().filter(a -> a.type() != ActionType.CONSOLE).toList();
        return new Result(Verdict.OK, ok);
    }

    /** Забыть задержки игрока (вышел). */
    public void forget(String who) { last.keySet().removeIf(k -> k.startsWith(who + '|')); }
}
