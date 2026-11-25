package ink.hoka.odyssey.api.worldgen;

/**
 * Вид высоты колонки (как HeightIndex ядра):
 * <ul>
 *   <li>{@link #SKY} — самый верхний Y, где небо уже не полное (блок с lightBlock &gt; 0 или полной верхней гранью;
 *       блок только с полной нижней гранью — Y−1);</li>
 *   <li>{@link #MOTION} — верх блока, мешающего движению (твёрдый, жидкость, waterlogged);</li>
 *   <li>{@link #SURFACE} — верх любого не-воздуха.</li>
 * </ul>
 */
public enum HeightKind { SKY, MOTION, SURFACE }
