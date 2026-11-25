package ink.hoka.odyssey.client.mixin;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Редактор Axiom (если стоит) при загрузке сверяет упаковку координат блока с ванильной (26/26/12) и падает
 * «BlockPos representation changed!», а у нас высокий мир — 24/24/16. Подставляем ему ожидаемое число по нашей
 * упаковке: дальше Axiom сам пакует координаты через BlockPos (как и сервер-модуль axiom). Нет Axiom — миксин пропускается.
 */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.utils.PositionUtils", remap = false)
public abstract class AxiomPositionMixin {
    @ModifyConstant(method = "<clinit>", constant = @Constant(longValue = -9223371899415820288L), require = 0)
    private static long hokaOdyssey$tallMin(long vanilla) {
        return BlockPos.asLong(-33554432, -2048, -33554432);
    }
}
