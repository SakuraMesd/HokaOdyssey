package ink.hoka.odyssey.client.mixin;

import ink.hoka.odyssey.client.TallWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Deque;

/**
 * К2 (0.48): очередь света мира игра разбирает по max(10, 10%) задач за кадр (при очереди меньше 1000). В высоком мире
 * в ней стоят свет столбцов (enableChunkLight — без него Sodium не рисует столбец и его 8 соседей), свет кубов op 11,
 * выгрузки op 4 и Forget: вернувшийся столбец ждал своей очереди кадрами — «старое грузится так же долго, как новое».
 * Здесь после ванильной порции очередь добирается дальше, пока не вышел бюджет времени кадра. Обычные миры не трогаем.
 * <p>0.49: бюджет не 2 мс, а доля среднего кадра (30%, от 0,25 до 1,5 мс): при кадре 1 мс прибавка была +200% —
 * «пила» 1000 → 330 FPS всю прогрузку. Задачи столбцов теперь дешёвые (TallLight: только слои, где есть состояние),
 * поэтому очередь разбирается не медленнее.</p>
 */
@Mixin(ClientLevel.class)
public abstract class LightQueueBudgetMixin {


    @Shadow @Final private Deque<Runnable> lightUpdateQueue;

    @Unique private long hoka$pollStart;

    @Inject(method = "pollLightUpdates", at = @At("HEAD"))
    private void hoka$pollStart(CallbackInfo ci) {
        hoka$pollStart = System.nanoTime();
    }

    /**
     * 0.49: ваниль при очереди от 1000 задач выполняет ВСЮ очередь за один кадр (после телепорта — кадр в десятки мс).
     * В высоком мире ванильная порция — минимальная (10 задач), остальное — по бюджету времени ниже.
     */
    @WrapOperation(method = "pollLightUpdates", at = @At(value = "INVOKE", target = "Ljava/util/Deque;size()I"))
    private int hoka$vanillaPortion(Deque<Runnable> q, Operation<Integer> op) {
        int n = op.call(q);
        if (!TallWorld.net || ((ClientLevel) (Object) this).getSectionsCount() <= 64) return n;
        return 0;
    }

    @Inject(method = "pollLightUpdates", at = @At("TAIL"))
    private void hoka$pollMore(CallbackInfo ci) {
        if (!TallWorld.net || ((ClientLevel) (Object) this).getSectionsCount() <= 64) return;
        // очередь копится (прогрузка не успевает) — бюджет больше, чтобы задержка света не росла без предела
        long budget = lightUpdateQueue.size() > 4000 ? 3_000_000L : ink.hoka.odyssey.client.FrameWatch.budgetNs(0.30, 250_000L, 1_500_000L);
        long end = hoka$pollStart + budget;
        while (System.nanoTime() < end) {
            Runnable r = lightUpdateQueue.poll();
            if (r == null) break;
            r.run();
        }
    }
}
