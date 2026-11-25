# CUBIC-CHUNKS.md — настоящие кубические чанки в Hoka Odyssey

Версия 2 от 24.11.2026. В ней исправлены ошибки v1 по рецензии и закрыты пропуски.
Сервер: Minestom 2026.05.17-1.21.11. Клиент: ядро Fabric 0.28.1 (после этой работы будет ≥ 0.29), MC 1.21.11, Sodium 0.8.12 из сборки Fabulously Optimized (Lithium 0.21.4+mc1.21.11).

Документ собран из пяти исследований (ваниль 1.21.11, OpenCubicChunks CC1/CC2/CC3, Sodium по javap, Minestom, наш код) и рецензии к v1, где факты перепроверены по mcsrc, клону Minestom с тега, javap Sodium и Lithium, fabric-networking-api-v1 5.1.6. **Ничего не запускалось.**

Пометка **«проверить»** означает, что утверждение не подтверждено чтением кода или опытом. Такие места закрываются до начала кода соответствующего этапа (§11).

---

## 0. Короткий итог

- **Куб = 16×16×16 = одна LevelChunkSection.** Ключ куба — `SectionPos.asLong` (X 22 бита, Y 20, Z 22) на клиенте и на сервере. Minestom-овский `CoordConversion.sectionIndex` (21/21/21) — другая упаковка, её не используем и не смешиваем с нашей.
- **Столбец остаётся лёгким заголовком.** Ваниль, Sodium и Minestom без столбца не работают. В заголовке: массив ссылок на кубы, три карты высот, биом по умолчанию и поколение (epoch). Объектов на каждую из 1876 секций больше нет: незагруженные кубы — общая заглушка мира.
- **Заглушки живут в мире, а не в static.** У каждого `ClientLevel` своя `UNLOADED` и свой набор `EMPTY_KNOWN` по биомам: биомы посекционные, а их Holder принадлежат регистрам соединения.
- **Кубы ходят своими пакетами** по каналу `hoka_odyssey:w`. Ванильный пакет столбца превращается в «скелет»: без секций и без света. Пустые кубы, свет которых полностью задаётся картой неба, идут диапазонами (`EMPTY_RANGE`). Скорость отправки считается в байтах.
- **Свет считает сервер, клиент небо не пересчитывает.** Если у куба нет слоя света или куб не загружен, клиент берёт значение из серверной карты неба `skyTop`. Это верх блока, ослабляющего небесный свет, поэтому вода и листва тоже учитываются.
- **Карты высот — серверные.** На сервере есть индекс высот столбца (HeightIndex), который знает верх даже при выгруженных кубах. Клиент свои карты не пересчитывает и только принимает `HEIGHTS`.
- **Отрисовка — только Sodium до этапа 6.** CubeTracker (готовность по 27 кубам, края мира считаются готовыми) напрямую вызывает `RenderSectionManager.onSectionAdded/onSectionRemoved`. Клиент без Sodium в режим кубов не пускается, пока не сделана ванильная ветка (этап 6).
- **«Незагруженный куб» — заморозка, а не воздух.** И на клиенте, и на сервере сущность у незагруженного куба стоит на месте. Сервер не пишет в незагруженный куб (исключение), а действия игрока в кубе, которого у игрока нет, отклоняет.
- **Этапов 8 (0–7)**, плюс этап 8 на будущее. После каждого этапа мир играбелен. Старая tall-карта работает до этапа 7. Оценка — **50–80 рабочих дней**, а не 23–32.

---

## 1. Что исправлено относительно v1

| № рецензии | Было в v1 | Стало в v2 | Где |
|---|---|---|---|
| 1 | `static final` заглушки, одна EMPTY_KNOWN, биомы пустых кубов выбрасываются | Заглушки на `ClientLevel`, `EMPTY_KNOWN` по биому, биомы шлются всегда, при копии при записи биом сохраняется, `getNoiseBiome` для UNLOADED берёт биом столбца, сброс tint-кэша | §3.1, §5 |
| 2 | `CubeColumn.setBlock` создаёт отсутствующий куб | Запись в не-READY куб — исключение. Действия игрока принимаются только в кубах из его `sent` | §4.2 |
| 3 | «UNLOAD кубов раньше ForgetLevelChunk» | Требование снято: Minestom шлёт Unload до события. Вместо него поколение столбца (epoch) | §3.2, §4.7, §5 |
| 4 | P4 «стена в коллизиях» | Lithium обходит ванильный BlockCollisions. Основной механизм — заморозка движения (P5 на `Entity.move`), стена в коллизиях — необязательное улучшение | §3.7 |
| 5 | Свет пустых кубов кладётся в очередь, при выгрузке слой не удаляется | L2 сначала проверяет UNLOADED. Свет «по формуле» не шлётся и в очередь не попадает. При выгрузке слой удаляется явно | §3.3 |
| 6 | Карты высот Minestom с EMPTY, skyTop = «верх непрозрачного» | Свой HeightIndex на сервере, три карты в заголовке, пакет `HEIGHTS`, skyTop = верх блока, ослабляющего небо | §4.4, §3.4 |
| 7 | Переопределён только `sendChunk(Player)` | Обе перегрузки `sendChunk`. `reset` не трогает CubeStore. ChunkBatch на кубовых картах запрещён | §4.1 |
| 8 | Версия ядра из HELLO (play) | Рукопожатие в configuration (`hoka_odyssey:hi`). Переход между кубовой и обычной картой — через реконфигурацию | §3.8, §5.1 |
| 9 | Пустые кубы по 10 Б в общем бюджете кубов | `EMPTY_RANGE` и бюджет в байтах | §5 |
| 10 | «Мобы не тикают» | Серверная заморозка сущностей в не-READY кубах и запрет спавна туда | §4.8 |
| 11 | Крайние кубы никогда не ready | Соседи вне мира и за границей считаются готовыми | §3.5 |
| 12 | Без Sodium после этапа 2 мир неиграбелен, регионы слишком поздно | Без Sodium режим кубов недоступен до этапа 6. Регионы объединены с переходом на CubeColumn | §7 |
| 13 | Ключ сервера не уточнён | Только `SectionPos.asLong`, своя реализация на сервере | §4.3 |
| 14 | Поток обработчика Fabric «проверить» | Всегда `if (!mc.isSameThread()) mc.execute(...)` | §3.2 |
| 15 | L5/L6 как средства правильности | При выключенном пересчёте неба клиентские `ChunkSkyLightSources` и `Heightmap.update` просто глушатся | §3.3, §3.4 |
| 16 | Вход в мир не продуман | Первыми идут куб игрока и 26 его соседей. Проверка `LevelLoadTracker` | §4.7, §3.5 |
| 17 | `registerLarge`, «держим ≤ 1 МиБ» | Обычный `register`. Сервер жёстко режет payload до ≤ 1 000 000 Б | §5 |
| 18 | Смена мира не учтена | CubeTracker и маски сбрасываются при смене `ClientLevel` | §3.8 |
| оценки | 23–32 дня | 50–80 дней, отдельный этап отладочных средств | §7 |

---

## 2. Проверенные факты

| Факт | Где |
|---|---|
| `SectionPos.asLong`: X 22 бита, Y 20, Z 22. От BlockPos не зависит. Хватает на ±33,5 млн блоков по горизонтали и на 1876 секций | mcsrc `core/SectionPos.java:22-30, 216-221` |
| `BlockPosPackingMixin` держит Y в младших битах (`Z_OFFSET = PACKED_Y_LENGTH`), поэтому `BlockPos.getFlatIndex` (`l & -16`) корректен. SectionPos миксин не трогает | наш `mixin/BlockPosPackingMixin.java:36-37` |
| `LevelChunkSection` не final. Конструкторы: `(PalettedContainer, PalettedContainerRO)` public (стр. 31) и `(PalettedContainerFactory)` (стр. 37) | `LevelChunkSection.java` |
| `PalettedContainerFactory.create(RegistryAccess)` строится на каждый мир. Биомный контейнер держит `Holder<Biome>` регистров соединения | `PalettedContainerFactory.java:22` |
| Биомы посекционные: `ChunkAccess.getNoiseBiome` читает `sections[o].getNoiseBiome` | `ChunkAccess.java:431-437` |
| `ClientLevel.onChunkLoaded` сбрасывает `tintCaches.invalidateForChunk` только при приходе столбца | `ClientLevel.java:392-393` |
| `ChunkAccess`: стр. 99 — массив секций, 101 — `postProcessing`, 112 — `replaceMissingSections` | `ChunkAccess.java` |
| Sodium `RSM.onSectionAdded(III)` вызывает `getSections()[idx].hasOnlyAir()` без проверки на null. `processChunkEvents()` и `initRenderer(CommandList)` существуют | javap RSM стр. 258-285, SodiumWorldRenderer |
| В Sodium отсутствующая секция работает как стена для обхода видимости, а заведённая EMPTY прозрачна | OcclusionCuller.visitNeighbors, createEmptyData |
| RenderRegion 8×4×8 стоит на абсолютной сетке. Вертикаль OcclusionCuller ограничена ±R | RenderRegionManager.createForChunk:236, OcclusionCuller:204 |
| Sodium подменяет дальность ванильного ViewArea на 0. Тогда `setViewDistance` даёт X=Z=1, Y=1876, то есть около 1876 RenderSection — копейки | ViewArea.java:60-65, Sodium LevelRendererMixin.nullifyBuiltChunkStorage |
| Ванильный ViewArea-окно без правки Octree ставит корень на minY, и ничего не рисуется | `Octree.java:27`, `SectionOcclusionGraph$GraphStorage:383` |
| Lithium 0.21.4 в `ChunkAwareBlockCollisionSweeper` сам вызывает `getChunkForCollisions`, `getSections()`, `hasOnlyAir()` и пропускает воздушные секции. Ванильный BlockCollisions при Lithium в движении сущностей не участвует | javap Lithium (рецензия) |
| `LayerLightSectionStorage.markNewInconsistencies` переносит `queuedSections` в хранилище только при `storingLightForSection` (слой уже есть). Слой создаётся, только когда `sectionStates != 0`, то есть у куба или соседа в 3×3×3 есть непустые блоки | `LayerLightSectionStorage.java:43-45, 143-155, 203-222` |
| `queueSectionData(null)` удаляет только запись очереди, уже установленный слой не удаляет | `LayerLightSectionStorage.java:194-200` |
| `applyLightData` сам вызывает `setLightEnabled(column, true)`, поэтому `enableChunkLight` можно отменить целиком | `ClientPacketListener` ~2421, 894 |
| Ванильный `handleCustomPayload` вызывает `ensureRunningOnSameThread`, но миксин Fabric стоит на HEAD, до этой проверки. Переключения потока в `ClientPlayNetworkAddon.receive` не найдено (вывод не окончательный) | ClientCommonPacketListenerImpl:159-162, javap fabric-networking 5.1.6 |
| S2C custom payload у ванили ≤ 1 048 576 Б, пакет Minestom ≤ 2 097 151 Б, буфер ванильного чанка ≤ 2 МиБ | ClientboundCustomPayloadPacket:16, ServerFlag:35, ClientboundLevelChunkPacketData:53 |
| Minestom: `DynamicChunk.sections` — `final List.of/copyOf`, `Section` final. Разреженный столбец наследуем прямо от `Chunk` | DynamicChunk:50, 64-75 |
| Minestom: `Player.sendPendingChunks` вызывает только `chunk.getFullDataPacket()` | Player.java:792 |
| Minestom: `Player.chunkRemover` сначала шлёт `UnloadChunkPacket`, потом `PlayerChunkUnloadEvent` | Player.java:172-176 |
| Minestom: `PlayerChunkLoadEvent` вызывается под `chunkQueueLock` в потоке тика | Player.java:793 |
| Minestom: вертикальной дальности нет, `sendChunkUpdates` и EntityTracker работают только по X/Z | Player.java:2383, EntityTrackerImpl |
| Minestom: `ChunkBatch.updateChunk` вызывает `chunk.sendChunk()` без аргумента. `ChunkBatch.apply` делает `chunk.reset()`, если столбец не загружен | ChunkBatch:166-174, 228-233 |
| Minestom: `BlockPlacementListener` (стр. 55, 108) и `PlayerActionListener` (36-42) проверяют только загрузку столбца и `getBlock` | — |
| Minestom: `Heightmap.refresh(x,z,startY)` спускается через `chunk.getSection` до `minHeight`. `getHighestBlockSection` возвращает Y в блоках | Heightmap.java:62-90, 130-138 |
| `GeneratorImpl.section(biomeRegistry, GenSection, sx, sy, sz)` — публичный юнит одного куба (`@ApiStatus.Internal`). `SectionModifierImpl.setBlock` границы не проверяет | GeneratorImpl:44, 250-256 |
| HELLO ядра уходит в **play**-фазе | HokaOdysseyClient.java:50-51 |
| Ошибка текущего кода: `TallChunk.afterLoad` делает `getHighestBlockSection(this)*16+15`, а метод уже возвращает Y | Heightmap.java:130-138 |
| В Minestom нет симуляции жидкостей и случайных тиков | DynamicChunk, InstanceContainer |

---

## 3. Клиент

### 3.1 Хранение кубов

**Решение.** `LevelChunk` остаётся, его массив `sections` (1876 ссылок) становится разреженным. Sodium (`onSectionAdded`, `ClonedChunkSectionCache.clone`, `LevelSlice.prepare`) читает именно `getSections()[idx]`, поэтому массив сохраняем: Sodium видит кубы без миксинов в мешинге.

**Заглушки на мир:**

```java
final class HokaSentinelSection extends LevelChunkSection {
  // hasOnlyAir()=true; setBlockState/read/readBiomes/acquire/release -> IllegalStateException
}
// в ClientLevel (@Unique, создаются в ClientLevel.<init> TAIL из palettedContainerFactory() этого мира):
HokaSentinelSection hoka$unloaded;                                  // куб неизвестен
Reference2ObjectMap<Holder<Biome>, HokaSentinelSection> hoka$empty; // куб загружен, весь воздух, один биом
```

- Различаем по идентичности: `s == level.hoka$unloaded`, `s instanceof HokaSentinelSection`.
- `EMPTY_KNOWN` создаётся лениво на каждый встреченный биом. Блоковый контейнер — «весь воздух», биомный — однозначный.
- Пустой куб с **неоднородным** биомом — настоящая `LevelChunkSection`: блоки «весь воздух» и свой биомный контейнер.
- В `LevelChunk`:
  - `@Unique long[] hoka$loaded` — 30 long, маска загруженных кубов;
  - `@Unique Holder<Biome> hoka$defaultBiome` — из `COLUMN`;
  - `@Unique int hoka$epoch`;
  - `@Unique short[] hoka$skyTop` (256).

**Миксины хранения (Mojang-имена):**

| # | Цель | Что делаем |
|---|---|---|
| C1 | `ChunkAccess.<init>`, `@Redirect` вызова `replaceMissingSections(PalettedContainerFactory, LevelChunkSection[])` (стр. 112) | Режим кубов: `Arrays.fill(sections, level.hoka$unloaded)`. Tall-режим (этап 2): ваниль, секции заполнит C3t |
| C2 | `LevelChunk.setBlockState(BlockPos, BlockState, int)` (стр. 268-300), HEAD cancellable | UNLOADED → `return null`. `HokaSentinelSection` (EMPTY_KNOWN) и не воздух → `sections[idx] = new LevelChunkSection(factory.createForBlockStates…, sentinel.getBiomes().copy())`, затем ваниль. Имена методов фабрики и `copy()` у PalettedContainer — **проверить** в 1.21.11 |
| C3 | `LevelChunk.replaceWithPacketData` (стр. 522), HEAD cancellable | Режим кубов: секции не читаем (буфер скелета пуст), только `setHeightmap`. `initializeLightSources` и `clearAllBlockEntities` НЕ вызываем |
| C3t | Тот же метод, tall-режим (этап 2) | Читаем секции сами: `nonEmpty == 0` → прочитать и выбросить блоки, биомы прочитать во временный контейнер; однозначный биом → `EMPTY_KNOWN[биом]`, иначе настоящая секция |
| C4 | `LevelChunk.replaceBiomes` (~543), `ClientPacketListener.handleChunksBiomes` (860-880) | Режим кубов: cancel. Биомы приходят в кубах |
| C5 | `ClientChunkCache$Storage.addEmptySections/dropEmptySections/refreshEmptySections` (239-274), cancel | Режим кубов: обход по `hoka$loaded`. UNLOADED в `loadedEmptySections` не кладём (иначе ~1,17 млн long и `clone()` в SectionOcclusionGraph:131) |
| C6 | `ChunkAccess.getHighestFilledSectionIndex` (137) | Обход по маске |
| C7 | `ChunkAccess.getNoiseBiome` (431-437), HEAD | Секция UNLOADED → `hoka$defaultBiome` столбца |
| C8 | `SectionCopy.<init>` (SectionCopy.java:29), `@Redirect ImmutableMap.copyOf(getBlockEntities())` | Только BE своей секции. Нужно только для ванильного рендера (этап 6) |
| C9 | `ChunkAccess.postProcessing` (стр. 101) | Не трогаем: ~7,5 КБ на столбец терпимо. Использует ли его клиент — **проверить** |

**Сброс оттенков.** На каждый применённый `CUBES` или `EMPTY_RANGE` один раз на столбец за пакет вызываем `tintCaches.invalidateForChunk(cx, cz)` через accessor к `ClientLevel.tintCaches`.

**Цена.** Около 7,5 КБ ссылок плюс несколько карт по ~0,5 КБ на столбец вместо ~0,6–0,8 МБ. Это оценка, замерить гистограммой кучи на этапе 2.

### 3.2 Жизненный цикл на клиенте

Разбор палитр идёт в StreamCodec (поток netty). Применение — всегда на главном потоке: `if (!mc.isSameThread()) { mc.execute(() -> apply(p)); return; }`.

1. **Скелет столбца** — ванильный `ClientboundLevelChunkWithLightPacket`: пустой буфер, без BE, пустые маски света, карты высот есть.
   - `ClientChunkCache.replaceWithPacketData` создаёт LevelChunk со всеми UNLOADED (C1, C3), затем вызывается `level.onChunkLoaded`.
   - `applyLightData` с пустыми масками включает свет столбца. `readSectionList` по 1878 битам пустого BitSet обходится дёшево.
   - `enableChunkLight` отменён (C10).
   - Следом приходит наш `COLUMN`: epoch, биом по умолчанию, полные `HEIGHTS`.
2. **`CUBES`.** Если epoch куба ≠ `hoka$epoch` столбца или столбца нет — отбросить с отладочным счётчиком. Иначе для каждого куба:
   - (a) `sections[idx]` = прочитанная секция, или `EMPTY_KNOWN[биом]`, или секция «воздух + свои биомы»; выставить бит `hoka$loaded`;
   - (b) BE — кодом из лямбды LevelChunk.java:534-541 (`getBlockEntity(pos, IMMEDIATE)` + `loadWithComponents`);
   - (c) `chunkSource.onSectionEmptinessChanged(cx,cy,cz,empty)` (ClientChunkCache:181);
   - (d) свет по правилам §3.3 через `level.queueLightUpdate`, затем `setSectionDirtyWithNeighbors`;
   - (e) CubeTracker: флаги «блоки» и «свет» (§3.5).
3. **`EMPTY_RANGE`.** Для каждого куба диапазона делаем то же, что в п. 2, с `EMPTY_KNOWN[биом]`. Свет в очередь не ставится, кроме `updateSectionStatus(pos, true)`. CubeTracker получает оба флага сразу.
4. **`UNLOAD`.**
   - `sections[idx] = unloaded`, снять бит;
   - удалить BE куба (`getBlockEntities()` с фильтром по `SectionPos.blockToSectionCoord(y)`, `setRemoved` + `remove`);
   - убрать куб из `loadedEmptySections` (accessor);
   - в `queueLightUpdate`: `queueSectionData(SKY/BLOCK, pos, null)`, **явное удаление установленного слоя** (§3.3, L7) и `updateSectionStatus(pos, true)`;
   - `setSectionDirty`, CubeTracker снимает флаги.
5. **Выгрузка столбца** — ванильный Forget от Minestom.
   - C11 чистит свет только загруженных кубов (по маске) и удаляет их слои.
   - CubeTracker забывает кубы столбца, epoch столбца теряется вместе с LevelChunk.
   - Кубы этого столбца, пришедшие позже, отбрасываются по п. 2.

| # | Цель | Что делаем |
|---|---|---|
| C10 | `ClientPacketListener.enableChunkLight` (894) | cancel в режиме кубов |
| C11 | `ClientPacketListener.queueLightRemoval` (916) | Обход по `hoka$loaded` с удалением слоёв |
| C12 | `ClientLevel.setServerVerifiedBlockState` | Ранний выход, если куб UNLOADED (страховка к серверному фильтру) |

### 3.3 Свет на клиенте

Принцип: **клиент небесный свет не считает.** Сервер присылает слой куба там, где свет не сводится к формуле, и досылает `LIGHT` после изменений.

**Формула неба** `F(x,y,z) = y > skyTop(x,z) ? 15 : 0`. `skyTop` — верх блока, который ослабляет небесный свет (§4.4), берётся из `HEIGHTS`.

| # | Цель | Что делаем |
|---|---|---|
| L1 | `LevelLightEngine.checkBlock` (32-40): пропуск skyEngine, либо `SkyLightEngine.checkNode` (47-73) cancel | Небо не пересчитываем. Блочный свет (факелы) клиенту оставляем |
| L2 | `SkyLightSectionStorage.getLightValue(long, boolean)` (26-52), HEAD cancellable | Порядок: 1) куб UNLOADED → F; 2) есть DataLayer → ваниль; 3) нет DataLayer → F, без ванильного «подъёма вверх» |
| L3 | `SkyLightSectionStorage.createDataLayer` (87-107) | Вместо `repeatFirstLayer` — слой по F поколоночно (16×16 колонок, ступенька по `skyTop`). Однородный случай — `new DataLayer(15)` или `new DataLayer()` |
| L4 | `SkyLightSectionStorage.onNodeRemoved` (69-85), `SkyLightEngine.countEmptySectionsBelowIfAtBorder` (194-219), `removeSourcesBelow` (81-104) | Ограничить циклы нижним загруженным кубом столбца: redirect `hasLightDataAtOrBelow` (стр. 124) на свою проверку по маске. Если L1 полностью выключает небо, часть этих путей не вызывается — **проверить** по коду, какие остаются |
| L5 | `LevelChunk.setBlockState` → `skyLightSources.update`, `ChunkSkyLightSources.fillFrom` | Режим кубов: cancel. Источники неба на клиенте не нужны, если небо не считается. Не читает ли их кто-то ещё — **проверить** grep по mcsrc |
| L6 | Постановка света в очередь (§3.2 п. 2d) | Для куба шлётся режим (0/15/массив) или «по формуле» (режим 0). Режим 0 — в очередь ничего не ставим, при необходимости слой создаст L3. Режимы 1/2/3 → `queueSectionData` |
| L7 | Выгрузка куба | Удалить слой явно: accessor к `LayerLightSectionStorage.updatingSectionData` + `removeLayer(pos)` и пометка `changedSections`. Точный путь (с учётом `swapSectionMap`) — **проверить**. Иначе L2 «Нет слоя» и Sodium `copyLightArray` возьмут старый слой |

Почему так. Слой у воздушного куба вдали от блоков ваниль всё равно не создаст (`sectionStates == 0`), и присланный свет навсегда висел бы в `queuedSections` (O(N) на каждый проход). Сервер шлёт массив только кубам, у которых свет отличается от F (боковое затенение, факелы). Если такой куб без непустых соседей, слой не создастся, и мы проиграем в точности только на воздухе вдали от блоков, где это не видно: так же ведёт себя и ваниль.

Перед кодом **проверить** точные дескрипторы SkyLightEngine и SkyLightSectionStorage 1.21.11. Миксины CC2 (1.18) переносим только как идею.

**Сделано (шаг 14, ядро 0.44.0):** op 11 LIGHT (`server tall/LightOp`, клиент `ServerLight`). Формула F на клиенте — без HEIGHTS: режим 0 несёт d тёмных снизу (1 байт на куб или 256 по колонкам). Первый op 11 в мире включает режим «свет от сервера»: L1 — `SkyLightEngine.checkNode` cancel и без заливки 15 в `setLightEnabled`; L2 — `getLightValue(JZ)` без слоя → последний op 11 куба; L3 — `createDataLayer` (небо и блоки) из op 11; L4 не нужен (без checkNode циклы не запускаются); L5 — `ChunkSkyLightSources.update` в `LevelChunk.setBlockState` пропуск; L6 — в очередь движка только кубам со слоем/состоянием, прочие — в памяти ServerLight; L7 — UNLOAD: очередь чистится, слой заменяется нейтральным (небо 15, блоки 0), op 11 при возврате приходит всегда. Верфь кораблей — ванильный свет. Сервер шлёт op 11 только ядру ≥ 0.44.0 (`Handshake.coreAtLeast`), остальным — UpdateLightPacket.

### 3.4 Карты высот на клиенте

- `MOTION_BLOCKING` и `WORLD_SURFACE` приходят в `COLUMN`/`HEIGHTS`. Записываем через `Heightmap.setRawData`/`setHeight` (точное имя — **проверить**).
- `Heightmap.update` (80-108) в режиме кубов cancel: клиент карту не пересчитывает и не спускается сквозь UNLOADED до minY. Погода и дождь берут серверное значение, поэтому крыша в кубе, которого у игрока нет, тоже прикрывает от дождя.
- `primeHeightmaps` не вызывается, потому что карты приходят всегда.

### 3.5 Отрисовка: Sodium

Новый класс **CubeTracker** (не миксин):
- `Long2IntOpenHashMap status` по `SectionPos.asLong`, флаги 1 (блоки) и 2 (свет), `ready` = И флагов по 3×3×3, очереди load и unload;
- **сосед вне мира** (`cy < minSec` или `cy > maxSec`) или **за границей** |x| или |z| > 500 000 считается имеющим оба флага;
- сбрасывается при смене `ClientLevel` (§3.8).

Куб готов, когда он и 26 соседей готовы. Ровно этот объём читает `LevelSlice.prepare`, поэтому мешинг правильный с первого раза. Сервер шлёт кубы на V+1.

| # | Цель (`@Pseudo`, `remap=false` на классе, `@At(..., remap=true)` для целей MC, `require=1`) | Что делаем |
|---|---|---|
| S1 | `RenderSectionManager.onChunkAdded(II)V`, `onChunkRemoved(II)V`, HEAD | cancel в режиме кубов |
| S2 | `RenderSectionManager.onSectionAdded(III)V`, `@WrapOperation` INVOKE `LevelChunkSection.hasOnlyAir()Z` | `s == null \|\| op.call(s)` — страховка |
| S3 | `SodiumWorldRenderer.processChunkEvents()V`, TAIL | Слить очереди CubeTracker: сначала `rsm.onSectionRemoved`, затем `rsm.onSectionAdded` |
| S4 | `SodiumWorldRenderer.initRenderer(…CommandList;)V`, TAIL | `onSectionAdded` для всех ready-кубов (после reload и смены дальности) |
| S5 | Приход `LIGHT` к построенному кубу | `setSectionDirty` → Sodium (`LevelRendererMixin` @Overwrite) → `scheduleRebuild` |
| S6 | Вход в мир: `LevelLoadTracker` (стр. 127) ждёт `isSectionCompiledAndVisible(позиция игрока)` | Считает ли Sodium заведённую EMPTY-секцию скомпилированной — **проверить** javap. Если нет — миксин: в режиме кубов готовность = «куб игрока ready в CubeTracker» |

Флаги света ставит наш обработчик (`CUBES`, `EMPTY_RANGE`). Sodium-овский ChunkTracker получит события от скелета, но они уйдут в отменённый `onChunkAdded`.

Не трогаем: OcclusionCuller, RenderRegion*, RemovableMultiForest.

Особые случаи:
- пока куба под камерой нет, работает `isOutOfGraph` → TreeSectionCollector без отсечения, при телепорте возможен короткий провал FPS;
- заведённый, но не построенный куб даёт `visibilityData = 0`, и картинка «дорисовывается». Поэтому ближние кубы шлются первыми.

`SodiumTallMixin` продолжает работать только в tall-режиме без кубов.

### 3.6 Отрисовка: ваниль без Sodium (этап 6)

До этапа 6 клиент без Sodium в режим кубов **не допускается** (§3.8): ViewArea на 1,17 млн RenderSection, `doesChunkExistAt` по столбцу.

Подкласс `HokaViewArea`: `@Redirect NEW ViewArea` в `LevelRenderer.allChanged` (стр. 299). Порт с CC3 (MIT, копирайт сохранить), сверяя с mcsrc-1.21.11.

| # | Цель | Что делаем |
|---|---|---|
| V1 | `ViewArea.setViewDistance` (60-66) | `sectionGridSizeY = min(sectionsCount, 2*rY+1)` |
| V2 | `ViewArea.repositionCamera` (76-98) | Кольцо по Y, как по X/Z, с клэмпом. По желанию fast-path CC3 |
| V3 | `getRenderSection(III)` (122-131), `containsSection` (133-141) | `floorMod(j, sizeY)` + `abs(j - cam.y) <= rY` |
| V4 | `SectionOcclusionGraph$GraphStorage.<init>` (383), `@ModifyArg` 3-го аргумента Octree | `level.getSectionsCount()`. ОБЯЗАТЕЛЬНО |
| V5 | `SectionOcclusionGraph.initializeQueueForFullUpdate` (197-236) | Клэмп к окну |
| V6 | `SectionRenderDispatcher$RenderSection.doesChunkExistAt` (214), `hasAllNeighbors` (219+) | По кубу: готов в CubeTracker |
| V7 | `SectionOcclusionGraph.queueSectionsWithNewNeighbors` (~169-180) | Проверять каждую секцию |
| V8 | C8 (`SectionCopy`) | Включить |

Итог при прорисовке 12: 25³ = 15 625 RenderSection.

### 3.7 Физика: незагруженный куб

Правило: **сущность у незагруженного куба стоит.**

| # | Цель | Что делаем |
|---|---|---|
| P1 | `Level.isLoaded(BlockPos)` (614) | Для ClientLevel в режиме кубов — бит `hoka$loaded`. Это чинит `Pilot.hits` (стр. 283) |
| P2 | `LivingEntity` стр. 2345, `@WrapOperation hasChunkAt(BlockPos)` | По кубу → без куба гравитации нет |
| P5 | `Entity.move(MoverType, Vec3)`, HEAD cancellable | Если AABB сущности, расширенный на вектор движения, задевает UNLOADED-куб, обнулить `deltaMovement` и cancel. Покрывает игрока, предметы, лодки, падающие блоки и корабль. Проверка 1–8 бит маски, дёшево |
| P4 | (необязательно, позже) стена в коллизиях | Ванильный `BlockCollisions` + `@Pseudo` в Lithium `ChunkAwareBlockCollisionSweeper` (WrapOperation `hasOnlyAir`/чтения состояния → полный блок для UNLOADED). Пакет класса — **проверить** по распакованному Lithium |

`ShipGravity.clip` (стр. 407): с заморозкой луч проходит UNLOADED как воздух — **проверить** поведение корабля у края загруженного объёма.

### 3.8 Режим, рукопожатие, смена мира

- **Рукопожатие в configuration.** Новое ядро при старте конфигурации шлёт C2S `hoka_odyssey:hi { coreVersion, hasSodium, sodiumVersion }`. Сервер в `AsyncPlayerConfigurationEvent` ждёт его с тайм-аутом (например 2 с). Пришёл ли этот пакет до события и как Minestom отдаёт config-плагин-сообщения — **проверить** (вариант Б: список каналов из `minecraft:register`; шлёт ли Fabric register в configuration — **не проверено**).
- `hoka_odyssey:cubes` (S2C, configuration, кодек unit) сервер шлёт только при `hi.core ≥ 0.29 && hi.hasSodium` (до этапа 6) и только при входе на карту с `cubes: true`. Старое ядро его не получает никогда.
- **Кубовую карту без допуска не пускаем:** такой клиент идёт на обычную карту или получает кик с текстом «обновите ядро / установите Sodium».
- **Переход между кубовой и обычной картой** только через реконфигурацию (у Minestom `Player.startConfigurationPhase()` — **проверить** наличие и поведение). Флаги `TallWorld.net` и `cubes` переключаются на INIT конфигурации. Переходы между картами одного режима — обычный Respawn.
- **Смена ClientLevel** (respawn в другое измерение, реконфиг): заглушки и маски живут в мире и уходят вместе с ним. CubeTracker очищается в миксине `Minecraft.setLevel` / `ClientLevel.<init>`.

---

## 4. Сервер (Minestom)

### 4.1 Столбец `CubeColumn extends net.minestom.server.instance.Chunk`

- **Конструктор** `super(instance, cx, cz, false)`: генератор Minestom столбцом не вызывается. В `Maps.java:113-119` — `setChunkSupplier(CubeColumn::new)`, `setGenerator` не ставится.
- **Хранилище:** `CubeHandle[] cubes` (null — куба нет в памяти), ссылка на CubeStore, `HeightIndex heights` (§4.4), `int epoch` (растёт при каждой загрузке столбца в память), биом по умолчанию.
- **`getSection(sy)`**: READY-куб или общий read-only `EMPTY`. **`getSections()`**: неизменяемый вид. **`getSectionAt`**: отдаёт секцию только READY-куба, для остального бросает исключение.
- **`setBlock`** (логика DynamicChunk:78-137: entries, tickable, handlers): куб должен быть READY, иначе `IllegalStateException` (§4.2). Затем `dirtyBlocks`, `dirtyLight`, обновить HeightIndex, поставить изменения в очередь `BLOCKS`/`HEIGHTS`.
- **`getBlock`**: для не-READY куба AIR (для Minestom-физики это допустимо, потому что сущности там заморожены, §4.8). Модули используют API `isCubeLoaded` (§4.10).
- **Карты высот:** свои наследники `Heightmap` (`CubeMotionHeightmap`, `CubeSurfaceHeightmap`). Их `getHeight` и `refresh` берут значения из HeightIndex и не спускаются через `getSection`. Можно ли так переопределить Heightmap Minestom (не final ли методы) — **проверить**.
- **`getFullDataPacket()`**: кэшированный скелет `ChunkDataPacket` (пустой буфер, карты высот из HeightIndex, без BE, пустой LightData). Кэш свой, сбрасывается при изменении высот.
- **`sendPacketToViewers`**: `BlockChangePacket`, `BlockEntityDataPacket`, `WorldEventPacket` и наш `BLOCKS` уходят только игрокам, у которых куб в `sent`. Остальное — как есть.
- **`sendChunk(Player)` и `sendChunk()`** — обе перегрузки переопределены: пересылают куб(ы), а не столбец.
- **`reset()`** выгружает кубы столбца из памяти без записи на диск и не трогает данные CubeStore других столбцов. `ChunkBatch`/`AbsoluteBlockBatch` на кубовых картах запрещены (лог-ошибка при использовании).
- **`copy`, `invalidate`, `tick`, `updateSnapshot`** свои, проходят только по READY-кубам.
- Не вызывать: `instance.generateChunk`, `Instance.getBlockLight/getSkyLight`, `invalidateSection`.

### 4.2 Правило записи (против потери данных)

- `CubeColumn.setBlock` в не-READY куб — `IllegalStateException("cube not loaded")`. Вызывающий код обязан сначала `loadCubes` (§4.10).
- Действия игрока (`PlayerBlockPlaceEvent`, `PlayerBlockBreakEvent`, `PlayerStartDiggingEvent`, `PlayerBlockInteractEvent`) отменяются, если куб цели (и куб, куда ставится блок) не в `sent` этого игрока. После отмены — ресинк куба игроку.
- Взрывы, корабли, команды пишут через `instance.setBlock` — значит, перед записью грузят кубы. Своих взрывов у Minestom нет, наши модули будут проверены по grep `setBlock`.
- На этапе 3 (сервер ещё на TallChunk, все кубы столбца в памяти) правило действует только для действий игрока.

### 4.3 CubeStore (на инстанс)

- Ключ — `SectionPos.asLong` (своя реализация на сервере: те же 22/20/22 бита, юнит-тест против ванильных значений). `CoordConversion.sectionIndex` не используется.
- `CubeHandle`: состояние (LOADING, READY, UNLOADING), `Section` или «пустой», свет sky/block (режим 0/15/массив 2048), dirty-флаги, набор игроков-зрителей, время последнего интереса, отметка высот на момент сохранения (§4.6).
- `CompletableFuture<CubeHandle> load(cx,cy,cz)`: читает регион, при промахе генерирует. Работает в виртуальном потоке.
- **Выгрузка:** куб не нужен ни одному игроку 30 с и не закреплён API кораблей → сохранить, если dirty, и убрать из памяти. Столбец выгружается через `instance.unloadChunk`, когда у него нет кубов и игроков в горизонтальной дальности.

### 4.4 HeightIndex: высоты при выгруженных кубах

На колонку (x, z) три величины:
- `skyTop` — верх блока, ослабляющего небесный свет (тот же признак, что использует ваниль в `ChunkSkyLightSources` — **проверить** точный предикат в 1.21.11; вода и листва в него входят);
- `MOTION_BLOCKING`;
- `WORLD_SURFACE`.

Задача — знать верх, когда снят верхний блок, а ниже лежат выгруженные кубы.

- **Сводка куба** (для изменённых кубов): на колонку и предикат — верх внутри куба (5 бит: 0–15 или «нет»), итого ≈ 256 × 3 × 5 бит ≈ 480 Б. Хранится в заголовке столбца только для кубов из маски «изменён».
- **Для нетронутых кубов** генератор отвечает аналитически: `int highest(x, z, yFrom, yTo, kind)`. Это **обязательное требование** к генераторам кубовых карт. Нынешний `TallWorld.generator()` (земля 0…40 и площадки) отвечает тривиально, будущим генераторам планет это нужно закладывать.
- **Поиск верха:** спуск сверху вниз. Изменённые кубы берутся из сводок, промежутки между ними — запросом к генератору. Ни один куб для этого не грузится.
- При `setBlock` сводка READY-куба обновляется, изменения уходят игрокам пакетом `HEIGHTS`.

Альтернатива — сегменты непрозрачности по колонке, как `ServerHeightMap` в CC1. Её берём, если окажется, что генератор не может отвечать аналитически.

### 4.5 Генерация по кубам (CubeGen)

```java
Section sec = new Section();
var gs   = new GeneratorImpl.GenSection(sec.blockPalette(), sec.biomePalette());
var unit = GeneratorImpl.section(MinecraftServer.getBiomeRegistry(), gs, cx, cy, cz);
generator.generate(unit);
// gs.specials() применить своим setBlock (как InstanceContainer.applyGenerationData:491+)
```

- Интерфейс нашего генератора: `generateCube(cx,cy,cz,unit)`, `highest(x,z,yFrom,yTo,kind)` (§4.4), `biomeAt(x,z)` для биома столбца. Позже — `populate(cube)` с рамкой требуемых соседей, без рекурсии (как ICubeGenerator CC1).
- Форки `unit.fork` запрещены (assert). Генератор пишет только в свой куб: `SectionModifierImpl.setBlock` границы не проверяет.
- За |x| или |z| > 500 000 — пустота, такие кубы не шлются.
- `GeneratorImpl`/`GenSection`/`UnitImpl` — `@ApiStatus.Internal`. Версию Minestom закрепить.

### 4.6 Свет по кубам (CubeLight)

1. **Небо, первичный расчёт куба:**
   - весь куб выше `skyTop` по всем 256 колонкам → режим «всё 15»;
   - весь ниже и без боковых источников → «всё 0»;
   - иначе массив: колонки сверху (выше skyTop — 15), затем растекание внутри куба (алгоритм из `TallChunk.createLightData`, урезанный до куба).
2. **Обмен краями:** граничные значения уходят 6 загруженным соседям. Если у соседа свет изменился, он попадает в очередь пересчёта. Распространение — только по READY-кубам.
3. **Блочный свет:** внутри куба плюс тот же обмен краями.
4. **setBlock:** если `skyTop` колонки сменился, пересчитать небо READY-кубов этой колонки между старым и новым значением, разослать `HEIGHTS` и `LIGHT`.
5. **Выгруженные кубы:** при сохранении куба пишем отметку `skyTop` его колонок на тот момент (2 бита: выше, ниже или внутри куба, как `lastSaveHeightMapInfo` в CC1). Если при загрузке отметка не совпала с HeightIndex, куб пересчитывается. Без этого после стройки над выгруженной пещерой в ней останется свет.
6. **Режим отправки:** если свет куба точно равен формуле F (§3.3) и блочный свет 0, куб помечается «по формуле»: пустой такой куб идёт в `EMPTY_RANGE`, непустой — в `CUBES` с режимом 0. Иначе отправляется режим или массив.
7. **Рассылка** раз за тик по очереди «кубы с грязным светом».

**Сделано (этап 4, шаг 12, `tall/CubeLight.java`):** на CubeColumn кубовых карт вместо ColumnLight. Пустой куб с пустыми 26 соседями и без skyTop в полосе [низ−16, верх+30] — «сквозной»: свет из плоской задачи 48×48 по высотам, не хранится. Остальные READY — массивы в `CubeLight.Store` (совпал с F — без массива). Событие (правка, загрузка куба) — одна заливка 3×3×3 кубов, рамка — грани кольца; смена skyTop — ещё посчитанные кубы 3×3 столбцов в полосе. Загрузка столбца — его кубы, затем 3×3×3 вокруг кубов, чья боковая грань не совпала с F генератора. Игрокам — только изменившиеся кубы (`CubeStreamer.relightCubes`), протокол прежний (ванильный `UpdateLightPacket`). Тесты — `CubeLightTest` (сверка с заливкой всей коробки). Ядро 0.43.1+ пропускает незнакомые op.

**Сделано (шаг 13):** запись куба несёт отметку высот (`tall/CubeMark`, поле int `CubeCodec`): биты 0–1 — skyTop его колонок при сохранении ниже / внутри (полоса [y0−1, y0+15]) / выше; биты 2–4 — в кубе нет блоков с высотой SKY/MOTION/SURFACE. Эти биты для кубов на диске — ещё и в теге `hclear` заголовка столбца: спуск высот (`HeightIndex.Unknown`) такой невыгруженный куб пропускает, а не берёт «верх куба». При загрузке куба отметка сравнивается с HeightIndex (несовпадение — счётчик в `/hocubes stats`; свет куба на диске не хранится и считается при загрузке всегда). Если `HeightIndex.fix` при загрузке куба сдвинул skyTop, пересвечивается вся полоса между старым и новым (событие SKY, как правка). В полосе смены skyTop посчитанный куб целиком выше skyTop своих колонок и до, и после — не пересчитывается («всё 15»). Тесты — `CubeLightHeightsTest`.

### 4.7 CubeStreamer (на игрока)

- **Состояние:** центр (cx, cy, cz), `R` = `effectiveViewDistance()`, `V` = `min(SETTINGS клиента, предел сервера)`, `LongOpenHashSet sent` и `pending`, приоритетная очередь по 3D-расстоянию, `bytesPerTick` из ACK, `batchLead ≤ 10`.
- **Нужное множество:** столбцы, отправленные Minestom (`PlayerChunkLoadEvent`), × `|cy - cy_игрока| ≤ V+1`.
- **События:**
  - `PlayerChunkLoadEvent` (под `chunkQueueLock`) — только поставить в очередь: `COLUMN` и кубы столбца;
  - `PlayerChunkUnloadEvent` — забыть `sent`/`pending` столбца. Клиент к этому моменту уже получил Unload и сам всё почистил, `UNLOAD` для этих кубов не шлём;
  - `PlayerTickEvent` — при смене `cy` или столбца пересчитать разницу, ушедшие кубы → `UNLOAD`. Телепорт по Y сбрасывает окно целиком.
- **Отправка в тик:** `BATCH_START` → READY-кубы до бюджета в байтах (пустые формульные — `EMPTY_RANGE`, в бюджете почти не учитываются) → `BATCH_END(n, bytes)`.
- **Порядок:** `COLUMN` раньше любого куба столбца (одно соединение, один поток отправки). Каждый payload ≤ 1 000 000 Б, сервер режет сам.
- **Вход в мир и телепорт:** первыми идут куб игрока и 26 соседей (S6).
- **Ванильные ChunkBatch*** с нашими не смешивать.
- **Граница:** кубы с |x| или |z| > 500 000 не шлются. Столбцы за ней — пустые скелеты.

### 4.8 Сущности

- **Видимость:** `entity.updateViewableRule(p -> abs(p.sectionY - e.sectionY) <= V)`. При пересечении границы куба по Y — `updateViewableRule()` / `updateViewerRule()` без аргумента.
- **Заморозка:** раз в тик (`InstanceTickEvent`) для сущностей кубовой карты: если куб под сущностью или куб, в который она движется, не READY → `velocity = 0`, `noGravity = true` с тегом-меткой. Когда куб станет READY, вернуть исходное значение.
- **Спавн** в не-READY куб запрещён (`AddEntityToInstanceEvent` cancel — существует ли и отменяемо ли, **проверить**).
- Спавн мобов только в READY-кубах с посчитанным светом. Жидкостей и случайных тиков в Minestom нет. Если их введём — по кубам и только в READY.

### 4.9 Хранение регионами кубов

- **Заголовок столбца** (`ChunkLoader`, `supportsParallelLoading/Saving=true`): HeightIndex (три карты + сводки изменённых кубов), `long[30]` «куб есть на диске», `long[30]` «куб изменён», биом по умолчанию, теги. Файл `maps/<карта>/cols/r.<rx>.<rz>.hcl`: 32×32 столбца, таблица смещений.
- **Кубы:** `maps/<карта>/cubes/r.<rx>.<ry>.<rz>.hcr`, 16×16×16 кубов на файл. Таблица 4096 × (offset, length, timestamp) = 48 КБ, сектора по 4 КБ, сжатие zstd или gzip. Пишем **только изменённые** кубы: нетронутые воссоздаёт детерминированный генератор, поэтому подземный камень не пишется.
- **Запись куба:** палитра блоков (`Palette.BLOCK_SERIALIZER`), биомы, свет (режим или 2048 Б), отметка высот (§4.6 п. 5), блоки с данными (SNBT).
- **Конвертер** `.htw → регионы`: команда `/hocubes convert <карта>`. Режет столбец на кубы, сравнивает с генератором, пишет только отличающиеся и строит HeightIndex.
- Образец формата — RegionLib (CC1, MIT).

### 4.10 API для кораблей и модулей

В `OdysseyServerApi`:
- `isCubeLoaded(Instance, Point)`;
- `CompletableFuture<Void> loadCubes(Instance, BoundingBox)`;
- `pinCubes(Instance, BoundingBox)` / `unpin` — не выгружать, пока корабль рядом.

Замены в коде кораблей:
- `Flight.insideBlocks` (~205-219): незагруженный куб = твёрдый;
- `Flight` посадка (стр. 336), `ShipLeveler.hits` (134-144), `HoShipCommand` (237): `isChunkLoaded` → `isCubeLoaded`;
- `ShipsModule.summon` (369-373): кроме `loadChunk`, ждать `loadCubes` в [at.y−reach, at.y+reach];
- `ShipsModule:439`, `HoShipCommand:80/244`: перед `getBlock` и `setBlock` — `loadCubes`. Иначе после этапа 4 запись бросит исключение (§4.2).

---

## 5. Протокол

### 5.1 Configuration

| Канал | Направление | Тело |
|---|---|---|
| `hoka_odyssey:tall` | S2C | unit (как сейчас, не меняется) |
| `hoka_odyssey:hi` | C2S | `varint coreVersion`, `bool hasSodium`, `string sodiumVersion` |
| `hoka_odyssey:cubes` | S2C | unit; шлётся только допущенному клиенту (§3.8) |

### 5.2 Play

- S2C `hoka_odyssey:w` — обычный `register`, не `registerLarge`: Fabric режет большие payload своим протоколом, Minestom его не знает. Сервер держит каждый payload ≤ 1 000 000 Б.
- C2S `hoka_odyssey:wc`.
- Первый байт — код операции. Позиция куба — `long SectionPos.asLong`, столбца — `long ChunkPos.asLong`.

**S2C:**

| Op | Имя | Поля |
|---|---|---|
| 1 | `COLUMN` | `long col`, `varint epoch`, `varint defaultBiome`, `short[256] skyTop`, `short[256] motionBlocking`, `short[256] worldSurface` (Y со смещением от minY) |
| 2 | `CUBES` | `varint n`; на куб: `long pos`, `varint epoch`, `byte flags` (бит0 — есть блоки, бит1 — есть BE, биты 2-3 — небо: 0 по формуле / 1 всё 0 / 2 всё 15 / 3 массив, биты 4-5 — блочный свет: 0 нет / 1 всё 0 / 3 массив); [блоки: `short nonAir` + PalettedContainer состояний]; **биомы всегда**: PalettedContainer биомов (однозначный ~3 Б); [небо 2048 Б]; [блок-свет 2048 Б]; [BE: `varint m`, × (`short idx = x<<8\|z<<4\|y`, `varint type`, NBT)] |
| 3 | `EMPTY_RANGE` | `long col`, `varint epoch`, `varint runs`, × (`varint cyFrom` (zigzag), `varint count`, `varint biome`). Только пустые кубы с однородным биомом и светом «по формуле» |
| 4 | `UNLOAD` | `varint n`, n × `long pos` (только смещение по Y и окну, не выгрузка столбца) |
| 5 | `LIGHT` | `varint n`, × (`long pos`, `byte flags` как в CUBES, массивы) |
| 6 | `HEIGHTS` | `long col`, `varint k`, k × (`byte idx = x<<4\|z`, `short sky`, `short motion`, `short surface`) |
| 7 | `BLOCKS` | `long pos`, `varint k`, k × `varlong(stateId<<12 \| local12)` |
| 8 | `BATCH_START` | — |
| 9 | `BATCH_END` | `varint cubes`, `varint bytes` |
| 10 | `VIEW` | `varint vertical`, `varint horizontal` |
| 11 | `LIGHT` (сделано, шаг 14) | `varint n`, × (`long pos`, `byte flags`: биты 2-3 небо 0 формула / 1 всё 0 / 2 всё 15 / 3 массив, бит 6 — массив слоем 128 Б, биты 4-5 блоки 0 не трогать / 1 всё 0 / 3 массив; [небо 0: `byte d` 0…16 у всех колонок или 0xFF + 256 Б d по колонкам z<<4\|x]; [небо 3: 128/2048 Б]; [блоки 3: 2048 Б]). Код 5 из старого плана не используется (занят WINDOW) |

**Порядок:** скелет (ванильный ChunkData в батче Minestom) → `COLUMN` → кубы столбца. Выгрузка столбца — ванильный Forget. Опоздавшие кубы клиент отбрасывает по epoch.

**C2S:**

| Op | Имя | Поля |
|---|---|---|
| 1 | `ACK` | `float desiredBytesPerTick` — клиент меряет время применения батча, как `ChunkBatchSizeCalculator`, но в байтах |
| 2 | `SETTINGS` | `varint verticalDistance` |

**Объёмы (оценка, НЕ замер):**
- пустой формульный куб в `EMPTY_RANGE` — доли байта;
- «земля» с палитрой ≤ 16 — 2–4 КБ сырых, ~1–1,5 КБ после сжатия кадра;
- худший куб ~12 КБ.

При R=12, V=8 это ≈ 11 900 кубов в окне, но в воздухе почти все уходят диапазонами.

---

## 6. Отладочные средства (этап 1, без них отладка вслепую)

- **Клиент, F3-строки:** кубов загружено, из них EMPTY / настоящих, ready в CubeTracker, в очереди Sodium, отброшено по epoch, `queuedSections` света, байт за последний батч.
- **Клиент, оверлей** (клавиша): рамки кубов вокруг игрока, цвет по состоянию (UNLOADED / EMPTY / ready / без света).
- **Клиент:** счётчик исключений от `HokaSentinelSection` с первым стеком в лог (ловит моды, пишущие мимо setBlockState).
- **Сервер `/hocubes`:** `stats` (столбцов и кубов в памяти, READY/LOADING, dirty, очередь света), `dump <x y z>` (состояние куба, зрители, режим света, высоты колонки), `player <ник>` (окно, sent, pending, bytesPerTick).
- **Сервер:** логирование нарушений правила записи (§4.2) со стеком.

---

## 7. Этапы

Оценки — рабочие дни одного разработчика с Claude. Проверка — на ПК игрока: на этом ПК Minecraft не запускаем.

### Этап 0. Починки текущего tall-режима (0,5–1 д, только сервер)

- `TallChunk.afterLoad`: `int y = Heightmap.getHighestBlockSection(this); motionBlockingHeightmap().refresh(y); worldSurfaceHeightmap().refresh(y);`
- Убирать столбец из `TallWorld.DIRTY` после загрузки.
- `TallWorld.border()` → `new WorldBorder(1_000_000, 0, 0, 5, 15)`.
- `TallLoader.supportsParallelLoading/Saving() = true`.

**Проверить игроку:** постройка ниже 0 после перезахода — правильный свет и погода; граница мира на ±500 000.

### Этап 1. Отладка и рукопожатие (3–4 д)

- §6 в объёме, имеющем смысл на tall-карте (F3, `/hocubes stats`).
- `hoka_odyssey:hi` и решение о допуске на сервере (пока только лог).
- Закрыть вопросы §11 п. 1–3.

**Проверить игроку:** в логе сервера видны версия ядра и наличие Sodium; старое ядро 0.28.1 входит как раньше.

### Этап 2. Клиент: разреженные секции на старом протоколе (4–6 д)

Сервер не меняется. Клиент в tall-режиме:
- заглушки на мир, `EMPTY_KNOWN` по биому;
- C3t, C2 с сохранением биома, C6, сброс tint-кэша.

**Проверить игроку:**
- память (F3, по возможности `jcmd GC.class_histogram`) и время загрузки против 0.28.1;
- блок в воздухе на 12000 ставится и ломается;
- цвета травы, неба и тумана, дождь и снег как раньше;
- Sodium рисует как раньше;
- в логе нет исключений заглушки (полная сборка FO).

### Этап 3. Протокол кубов, сервер на старом хранении (15–22 д)

Карта `cubes: true` — копия tall-карты. Допуск — ядро ≥ 0.29 с Sodium.

**3а. Данные и картинка (10–14 д).**
- *Сервер:* `CubeStreamer` режет TallChunk на кубы, `COLUMN` / `CUBES` / `EMPTY_RANGE` / `UNLOAD` / `HEIGHTS` / `BLOCKS`, epoch, бюджет в байтах, скелет в `getFullDataPacket`, фильтр `sendPacketToViewers`, обе перегрузки `sendChunk`. Свет считается как сейчас (столбцом) и нарезается в режимы, высоты — из Minestom-карт TallChunk (после этапа 0 они верные), `skyTop` — из расчёта света.
- *Клиент:* CubeReceiver, C1, C3–C5, C7, C10–C12, свет L1–L7, высоты §3.4, Sodium S1–S6 + CubeTracker. `SodiumTallMixin` — только при tall без cubes.

**3б. Физика и действия (5–8 д).**
- *Клиент:* P1, P2, P5, проверка Pilot и ShipGravity.
- *Сервер:* отказ в действиях игрока вне `sent` (§4.2), видимость сущностей по V (§4.8).

**Проверить игроку:**
- мир выглядит как на tall-карте;
- `/hotall 12000` — пауза, потом кубы подгружаются, игрок не падает;
- спуск в шахту: снизу догружается, сверху выгружается (F3: кубов ≈ (2R+1)²·(2V+3));
- пещера под ещё не загруженным потолком тёмная;
- дождь не идёт под крышей на 12000, когда игрок на 11950;
- корабль останавливается у незагруженного куба;
- блоки другого игрока на 12000 не видны игроку на 0;
- перезаход и смена карты кубы↔обычная — без крашей.

### Этап 4. Сервер: разреженный столбец и хранение (20–30 д)

**4а. CubeColumn, CubeStore, регионы (12–18 д).**
- `CubeColumn`, `CubeStore` с выгрузкой, `CubeGen`, HeightIndex, регионы, конвертер `/hocubes convert`;
- правило записи §4.2 целиком, серверная заморозка сущностей §4.8, API кораблей §4.10;
- свет пока упрощённый: небо по `skyTop` и растекание внутри куба, без обмена краями. Ступеньки на границах кубов допустимы.

**4б. Полный CubeLight (8–12 д).**
- Обмен краями, пересвет после смены `skyTop`, отметки высот при сохранении, режим «по формуле».

**Проверить игроку:**
- полёт на ±15000 и обратно без провалов;
- `/hocubes stats`: память не растёт без предела после облёта;
- после рестарта постройки на 0, −10000 и 12000 на месте;
- размер папки карты мал (подземный камень не пишется);
- свет сквозь крышу на границе кубов без ступенек (4б);
- постройка крыши над выгруженной пещерой → пещера тёмная после возврата (4б);
- призыв корабля на 10000.

### Этап 5. Вертикальная дальность как настройка (2–3 д)

Опция клиента, `SETTINGS` / `VIEW`, предел сервера. Начальное V = 8, подобрать на сборке FO.

**Проверить игроку:** ползунок меняет объём подгрузки; FPS на FO.

**Сделано (ядро 0.45.0):** ползунок «Дальность по высоте» 4…32 куба, по умолчанию 10 (`VerticalView`, `config/hoka_odyssey.json`). С Sodium — группа «Hoka Odyssey» на странице «Общие» (`compat/SodiumOptionsMixin` → `buildGeneralPage`, config API Sodium), без Sodium — сразу после «Дальности прорисовки» (`VideoOptionsMixin`). Окно отрисовки Sodium (`SodiumTallMixin`) = ±настройка+1, смена — сразу. Сервер: канал `hoka_odyssey:wc` объявляется ядру 0.45+ при первом спавне, клиент шлёт op 2 VIEW (`varint`) при объявлении канала и при смене; `CubeStreamer.vertical` = min(`-Dhoka.cubes.vertical-max`, по умолчанию 32; max(4, запрос)); до VIEW — 10, старое ядро — прежняя формула. По горизонтали — `minestom.chunk-view-distance` = 32 (OdysseyServer.main).

### Этап 6. Ванильная отрисовка без Sodium (5–8 д)

V1–V8, `HokaViewArea`, затем снять требование Sodium из допуска.

**Проверить игроку:** ядро без Sodium — мир рисуется, при движении по Y секции не пропадают; F3 «C:» около 15 тыс.

### Этап 7. Чистка (1–2 д)

Перевести tall-карты на `cubes: true` конвертером, удалить старый код (§9).

**Проверить игроку:** все карты работают.

**Итого:** около 50–80 рабочих дней. Первый ощутимый выигрыш по памяти клиента даёт этап 2 (через 1,5–2 недели). Настоящие кубы по сети — после этапа 3, на сервере — после этапа 4а.

### Этап 8 (будущее). Планеты, корабли, космос

- Планета — отдельный Instance. Вход в атмосферу = смена мира (реконфиг, если меняется режим).
- Вид издали — модель или свой LOD (идея октодерева Voxy со своим ключом: у Voxy Y = 8 бит). Код Voxy и DH не брать.
- `getMinSectionY/getMaxSectionY` держать большими, чтобы камера Sodium всегда была «внутри мира».
- Точность float на ±500 000 (дрожание моделей и частиц) — **проверить** на этапе 3.

---

## 8. Риски

| Риск | Как снять |
|---|---|
| Lithium, ImmediatelyFast, MoreCulling читают или пишут секции мимо наших миксинов | Сеттеры заглушки бросают исключение, счётчик в F3. Этап 2 гоняется на полной FO. Коллизии — через заморозку P5, а не через стену |
| Биомы и оттенки «застревают» | Биомы в каждом кубе, `getNoiseBiome` для UNLOADED → биом столбца, `invalidateForChunk` на каждый куб |
| Потеря данных при записи в незагруженный куб | §4.2: исключение и отказ, лог со стеком |
| Опоздавшие кубы после Forget | epoch столбца |
| Слой света не создаётся или висит устаревший | L2 (UNLOADED раньше слоя), L6 (формула не ставится в очередь), L7 (явное удаление) |
| Неверные карты высот и `skyTop` при выгруженных кубах | HeightIndex с аналитическим генератором. Если генератор не умеет `highest` — сегменты CC1 |
| Дыры и ступеньки света на границах (CC1 #730) | Клиент небо не считает. Сервер: обмен краями, пересвет по отметкам (этап 4б) |
| Пустые кубы не пришли → дыры в отсечении Sodium | `EMPTY_RANGE` обязателен, края мира в CubeTracker готовы |
| Долгая первая загрузка | `EMPTY_RANGE`, бюджет в байтах, ближние кубы первыми |
| Remap в `@Pseudo`-миксинах Sodium и Lithium молча не применяется | `require=1`, лог применения при старте |
| Internal API Minestom | Закрепить версию, проверять компиляцией при обновлении |
| Клиент без Sodium | Не допускается до этапа 6 |
| Старое ядро получит скелет → краш | Допуск по `hoka_odyssey:hi`. Без него кубовую карту не открывать |
| Рукопожатие в configuration не сработает так, как задумано | Вариант Б — каналы из `minecraft:register`. Закрыть на этапе 1 |
| Payload > 1 МиБ | Сервер режет до 1 000 000 Б, ассерт перед отправкой |
| Сущности сервера проваливаются | Заморозка в не-READY кубах, запрет спавна |
| Генератор не умеет `highest` аналитически | Требование к API генератора. Иначе сегменты CC1 (+время) |
| Оценки сроков | Отладка только на ПК игрока: каждый этап заканчивается коротким списком проверок |
| Погода, world border, UV дождя на больших Y | Проверить на этапе 3, мелкие фиксы |

---

## 9. Что удаляется (этап 7)

**Клиент:**
- `mixin/compat/SodiumTallMixin.java` (вместе с багом «секции без данных»);
- признак высокого мира `getSectionsCount() > 64` → флаг cubes;
- ветка C3t tall-режима.

**Сервер:**
- `tall/TallChunk.java` целиком. Алгоритм света 16³ переезжает в `CubeLight`;
- `tall/TallLoader.java` остаётся только для чтения в конвертере;
- `TallWorld.lightDirty/flushLight` → очередь кубов;
- `TallWorld.generator()` как генератор Minestom → `CubeGen` (`generateCube`, `highest`, `biomeAt`).

**Остаётся:**
- `BlockPosPackingMixin`, `BlockPosNetMixin`, `TallWorld.vanilla*/tall*`;
- подмена `NetworkBufferTypeImpl$BlockPositionType`;
- тип измерения через рефлексию;
- `hoka_odyssey:tall`, FrameWatch, `/hotall`.

---

## 10. Лицензии

- **Можно брать** с сохранением копирайта «OpenCubicChunks and contributors» и текста MIT: CubicChunks (CC1), CubicChunks2, CubicChunks3, CubicChunksCore, RegionLib.
- **Не брать:** dasm (лицензия не указана), Better Cubic Chunks (All Rights Reserved, исходников нет), Voxy (All rights reserved), Distant Horizons (GPL-3.0).
- **Sodium и Lithium:** только миксуемся, код не копируем. Лицензию Sodium 0.8.12 — **проверить**.

---

## 11. Открытые вопросы (закрыть до кода своего этапа)

| № | Вопрос | Нужен к этапу |
|---|---|---|
| 1 | Как Minestom принимает config-плагин-сообщение `hoka_odyssey:hi` и успевает ли оно к `AsyncPlayerConfigurationEvent`. Шлёт ли Fabric `minecraft:register` в configuration | 1 |
| 2 | Как старое ядро реагирует на неизвестный config-payload (на случай ошибки допуска) | 1 |
| 3 | `Player.startConfigurationPhase()` в Minestom 2026.05.17 для перехода между режимами | 1 |
| 4 | Имена методов `PalettedContainerFactory` и `PalettedContainer.copy()` в 1.21.11 для заглушек и C2 | 2 |
| 5 | Точные сигнатуры SkyLightEngine и SkyLightSectionStorage 1.21.11 для L1–L4. Путь явного удаления слоя (L7) | 3 |
| 6 | Кто ещё читает `ChunkSkyLightSources` на клиенте (L5) | 3 |
| 7 | Метод записи карты высот на клиенте (`setRawData`/`setHeight`) | 3 |
| 8 | Считает ли Sodium EMPTY-секцию скомпилированной для `LevelLoadTracker` (S6) | 3 |
| 9 | Точный предикат «ослабляет небесный свет» в 1.21.11 для `skyTop` | 3 |
| 10 | Пакет класса Lithium `ChunkAwareBlockCollisionSweeper` (для P4, если понадобится) | 3б |
| 11 | Можно ли переопределить методы Minestom `Heightmap` (final или нет). `AddEntityToInstanceEvent` отменяемо ли | 4а |
| 12 | Трогает ли клиент `ChunkAccess.postProcessing` | (необязательно) |
| 13 | Реальная память секции и RenderSection (гистограмма кучи у игрока) | 2 |

## 12. Генераторы планет, pregen, экономное хранение (ядро 0.4x, 25 ноя)

- **API** `ink.hoka.odyssey.api.worldgen` (`WorldGenerator`, `GeneratorProvider`, `GenSettings`, `Bounds`,
  `HeightKind`, `CubeBuffer`); модуль — `registerGenerator`, карта — `map.yml generator: <id>`. Встроенные `flat`
  (= прежний FlatTallGen) и `void` — через тот же API. В ядре: `tall/gen/` — `ModuleCubeGen` (границы → индекс →
  hasBlocks/uniform модуля → generate), `GeneratorRegistry` (+ защита от выгрузки модуля), `CubeGens` (разбор map.yml).
  Генератор карты — поле `CubeRegionLoader.gen` (было: один `TallChunk.gen()` на сервер).
- **Старые карты**: нет `generator_seen`, а данные есть — всегда `flat` (как было). Генератор не зарегистрирован —
  карта не грузится (подставлять flat нельзя: правки легли бы поверх чужого рельефа).
- **Смена генератора/версии**: тег `gen` = `id@ver` в заголовке столбца; не совпал — высоты заголовка не берём
  (генератор + «с запасом» над кубами с диска, уточнит `HeightIndex.fix`). Правки остаются полными снимками кубов.
- **Индекс pregen** `genidx/r.<rx>.<rz>.hgi` (детерминированный генератор, кубы на диск не пишутся): на столбец —
  диапазон непустых кубов, однородные отрезки (state+биом), при `highest = UNKNOWN` — 3×256 высот. Заголовок:
  id, версия, сид, отпечаток (хэш 16 кубов) — не совпал, файл игнорируется. Плоская карта ≈ 0,13 Б/столбец.
- **`/hocubes pregen`** — пул платформенных потоков MIN_PRIORITY, регулятор по длине тика, спираль регионов от
  центра, прогресс `pregen.yml` (возобновление после рестарта). Недетерминированный генератор — пишет кубы,
  карта выгружена, проверка свободного места. `/hocubes size`, `/hocubes gentest`.
- **RegionFile v2** (HCR2): сектор 256 байт, raw deflate (`-Dhoka.region.level`, по умолчанию 6). v1 читаются и
  пишутся как раньше, новые файлы — v2, `/hocubes compact` переводит. Однородный куб: 256 Б вместо 4 КБ.
  Откат на ядро до v2 НЕ поддерживается (старое ядро не откроет HCR2).
- **Остановка**: карты сохраняются ДО выключения модулей (сохранение сверяет кубы с генератором), потом ещё раз.
