package ink.hoka.odyssey.blocks;

import net.minestom.server.instance.block.Block;

/**
 * Свои блоки для других модулей: {@code services().get(HoBlocks.class)} (в module.yml — {@code softdepends: [blocks]}).
 * Например, добыча узнаёт руды космоса: носитель у всех — нотный блок, а id — свой.
 */
public interface HoBlocks {

    /** id своего блока по состоянию в мире (носителю) или null — блок ванильный. */
    String idOf(Block block);
}
