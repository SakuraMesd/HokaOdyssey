package ink.hoka.odyssey.server.tall;

import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.packet.server.play.data.LightData;

import java.util.BitSet;
import java.util.List;

/**
 * Чанк высокого мира: свет считается дёшево и шлётся только для секций с блоками.
 * <ul>
 *   <li>Небо — 15 выше skyTop колонки (HeightIndex, предикат как у игры: вода и листва ослабляют), дальше растекание
 *       внутри секции с ослаблением max(1, lightBlock) — как у игры.</li>
 *   <li>Пустые секции не шлём вовсе: клиент (SkyLightSectionStorage) над самой верхней секцией со светом считает
 *       небо полным (15), а пустую секцию ниже — по нижнему ряду ближайшей секции со светом над ней. Это ровно то же,
 *       что дали бы вертикальные лучи: тени под площадками до земли, под землёй — темно.</li>
 *   <li>Свет блоков (светокамень и т.п.) — только внутри своей секции (без перехода в соседние).</li>
 * </ul>
 * Minestom LightingChunk считает все 1876 секций — 2.4 с на столбец, поэтому свой (ColumnLight).
 * Шаг 9: только для высоких карт на .htw; при map.yml {@code store: cubes} — {@link CubeColumn}.
 */
public final class TallChunk extends DynamicChunk implements TallColumn {

    private static final int MIN_SECTION = TallWorld.MIN_Y >> 4;
    /** Полоса верфи кораблей (модуль ships, Shipyard.X0 − 64): там не генерируем землю. */
    public static final int YARD_X0 = 400_000 - 64;

    /** Поле DynamicChunk.sections: конструктор Minestom копирует список в неизменяемый — подменяем на свой. */
    private static final java.lang.reflect.Field SECTIONS;
    static {
        try {
            SECTIONS = DynamicChunk.class.getDeclaredField("sections");
            SECTIONS.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private final TallSections sparse;
    /** Высоты, «что менялось», свет (общее с CubeColumn). */
    private final ColumnState st = new ColumnState();

    @Override public ColumnState st() { return st; }

    public TallChunk(Instance instance, int chunkX, int chunkZ) {
        this(instance, chunkX, chunkZ, new TallSections(TallWorld.HEIGHT >> 4));
    }

    private TallChunk(Instance instance, int chunkX, int chunkZ, TallSections list) {
        super(instance, chunkX, chunkZ, list);                              // копия ссылок на EMPTY — сразу заменим
        this.sparse = list;
        try { SECTIONS.set(this, list); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    @Override public Section materialize(int index) { return sparse.materialize(index); }

    @Override public int realSections() { return sparse.real(); }

    /**
     * Новый столбец (в хранилище нет): своя генерация вместо генератора Minestom — по кубам (CubeGen.generateCube),
     * в столбец ложатся только непустые секции. Земля как у плоской карты (0…40) + площадки TallWorld, верфь пустая.
     */
    public static TallChunk generated(Instance instance, int chunkX, int chunkZ) {
        TallChunk c = new TallChunk(instance, chunkX, chunkZ);
        c.fillGen(null);
        c.afterLoad();
        return c;
    }

    @Override public boolean isReal(int i) { return sparse.isReal(i); }

    @Override public void putSection(int i, Section s) { sparse.set(i, s); }

    @Override
    public void setBiome(int x, int y, int z, net.minestom.server.registry.RegistryKey<net.minestom.server.world.biome.Biome> biome) {
        int i = (y >> 4) - MIN_SECTION;
        if (i >= 0 && i < sections.size()) materialize(i);
        super.setBiome(x, y, z, biome);
        st.changed(this, i >= 0 && i < sections.size() ? i : -1, false);
    }

    @Override
    public void setBlock(int x, int y, int z, Block block, net.minestom.server.instance.block.BlockHandler.Placement placement,
                         net.minestom.server.instance.block.BlockHandler.Destroy destroy) {
        int i = (y >> 4) - MIN_SECTION;
        if (i >= 0 && i < sections.size() && !block.isAir()) materialize(i);   // воздух в пустую — нечего менять
        super.setBlock(x, y, z, block, placement, destroy);
        if (block.isAir() && i >= 0 && i < sections.size()) sparse.release(i);   // секция опустела — память назад
        boolean in = i >= 0 && i < sections.size();
        if (in) st.heights.update(sections, MIN_SECTION, x, y, z, block.stateId());
        st.changed(this, in ? i : -1, true);
    }

    /**
     * Кубовая карта (CUBIC-CHUNKS.md, этап 3): столбец уходит «скелетом» — все секции пустые (биомы настоящие), без
     * света и блок-энтити. Блоки и свет приходят кубами (CubeStreamer) только в окне по высоте вокруг игрока.
     */
    @Override
    public net.minestom.server.network.packet.server.SendablePacket getFullDataPacket() {
        if (!TallWorld.isCubes(getInstance())) return super.getFullDataPacket();
        var biomeSer = Palette.biomeSerializer(net.minestom.server.MinecraftServer.getBiomeRegistry().size());
        Palette air = Palette.blocks();
        byte[] data = net.minestom.server.network.NetworkBuffer.makeArray(buf -> {
            for (Section s : sections) {
                buf.write(net.minestom.server.network.NetworkBuffer.SHORT, (short) 0);
                buf.write(Palette.BLOCK_SERIALIZER, air);
                buf.write(biomeSer, s.biomePalette());
            }
        });
        var empty = new LightData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of());
        return new net.minestom.server.network.packet.server.play.ChunkDataPacket(getChunkX(), getChunkZ(),
                new net.minestom.server.network.packet.server.play.data.ChunkData(getHeightmaps(), data, java.util.Map.of()), empty);
    }

    /**
     * Minestom пересылает столбец целиком, когда отменили/не вышла установка (BlockPlacementListener.refresh) и когда
     * ломают воздух (InstanceContainer.breakBlock; отменённое ломание — только ack). На кубовой карте скелет сотрёт у игрока все кубы столбца — вместо него
     * ресинк только задетых кубов (CubeStreamer.resync, правило записи §4.2).
     */
    @Override
    public void sendChunk(net.minestom.server.entity.Player player) {
        if (TallWorld.isCubes(getInstance()) && CubeStreamer.resync(player, this)) return;
        super.sendChunk(player);
    }

    /** Блоки с данными в полосе Y [y0, y1] (ключ — индекс в столбце, CoordConversion.chunkBlockIndex). */
    @Override
    public java.util.Map<Integer, Block> dataBlocks(int y0, int y1) {
        java.util.Map<Integer, Block> m = new java.util.HashMap<>();
        for (var e : entries.int2ObjectEntrySet()) {
            int y = net.minestom.server.coordinate.CoordConversion.chunkBlockIndexGetY(e.getIntKey());
            if (y >= y0 && y <= y1) m.put(e.getIntKey(), e.getValue());
        }
        return m;
    }

    /** Загружен из хранилища (палитры залиты напрямую) — пересчитать карты высот; свет не слать (ещё никто не видит). */
    @Override
    public void afterLoad() {
        int y = net.minestom.server.instance.heightmap.Heightmap.getHighestBlockSection(this);   // уже Y в блоках (верх верхней непустой секции)
        motionBlockingHeightmap().refresh(y);
        worldSurfaceHeightmap().refresh(y);
        st.heights.recompute(sections, MIN_SECTION);
        invalidate();
        st.loaded();                                                        // как на диске / как сгенерирован
        TallWorld.loaded(this);                                          // при заливке setBlock пометил свет — ещё никто не видит, слать не надо
    }

    @Override
    protected LightData createLightData(boolean requiredFullChunk) {
        return ColumnLight.compute(getSections(), st.heights, getInstance().getCachedDimensionType().hasSkylight());
    }
}
