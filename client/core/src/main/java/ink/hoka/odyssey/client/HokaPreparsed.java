package ink.hoka.odyssey.client;

import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/** 0.57: пакет столбца, секции которого уже разобраны в сетевом потоке (ChunkPacketPreparseMixin). */
public interface HokaPreparsed {
    /** Готовые секции, если разобраны той же фабрикой и их столько же; иначе null (разобрать как обычно). Один раз. */
    LevelChunkSection[] hoka$take(PalettedContainerFactory f, int count);
}
