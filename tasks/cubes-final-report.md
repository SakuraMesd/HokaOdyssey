# Отчёт cubes-final

Ветка `ai/cubes-final`. Отчёт обновляется по ходу работы.

## Шаг 1. Ревизия плана (CUBIC-CHUNKS.md) по коду

Реализация на деле отличается от плана §3.1–3.5: клиент держит полный `LevelChunk` с общими пустыми секциями
(`SparseSections`) и **окно по Y** (op 5, `CubeReceiver.from/to`), а не маску `hoka$loaded` на каждый куб.
Поэтому «куб загружен» на клиенте = «секция Y в окне» (плюс ожидание кэша/генерации). Ниже — по пунктам.

| Пункт | Состояние | Где |
|---|---|---|
| **3б, клиент** | | |
| P1 `Level.isLoaded` по кубу | сделан (по окну + ожидание генерации) | `client/core/.../mixin/CubeLoadedMixin.java` |
| P2 гравитация `LivingEntity` без куба | покрыт P5: вертикальное движение в куб вне окна обнуляется, гравитации нечего делать | `CubeFreezeMixin` |
| P5 `Entity.move` — стоп у незагруженного куба | сделан (`@ModifyVariable` на HEAD, только по Y; по X/Z ваниль не пускает в незагруженный столбец; верфь x ≥ 399 000 — без заморозки) | `mixin/CubeFreezeMixin.java` |
| P4 стена в коллизиях | нет (в плане — необязательно) | — |
| Pilot.hits / ShipGravity | Pilot — через `isLoaded` (P1); старые `ships` клиентского модуля не меняли | — |
| **3б, сервер** | | |
| Отказ действий вне `sent` (§4.2) | сделан (отмена + ресинк куба) | `server/.../tall/CubeStreamer.java:222` |
| Видимость сущностей по V (§4.8) | **нет** — сознательно не делаю, см. шаг 2 и вопросы | — |
| **4а** | | |
| CubeColumn, CubeStore с выгрузкой, CubeGen, HeightIndex, регионы | сделаны | `tall/CubeColumn`, `CubeStore`, `CubeGen`, `HeightIndex`, `CubeRegionLoader`, `region/` |
| Конвертер `/hocubes convert` | сделан | `tall/HoCubesCommand.java:194–240`, `CubeConvert` |
| Правило записи §4.2 (исключение при записи в незагруженный куб) | сделано | `CubeColumn.setBlock/setBiome` → `CubeStore.violation` |
| Заморозка сущностей §4.8 | сделана (тик сущности, тег `hoka:cube_frozen`, запрос загрузки) | `tall/CubeEntities.java` |
| Запрет спавна в не-READY куб | заменён заморозкой после спавна (отмена ломает `setInstance` у вызывающих) | `CubeEntities` (javadoc) |
| API §4.10 `isCubeLoaded/areCubesLoaded/loadCubes/ensureCubes/pinCubes` | сделан | `api/.../OdysseyServerApi.java:79–140` |
| Замены в `ships` (§4.10) | сделаны (Flight, ShipLeveler, HoShipCommand, ShipsModule, Shipyard, YardStreamer) | `modules/ships` |
| `vessels`: луч, подушка, прилёт, варп, посадка | везде проверка `isCubeLoaded` перед `getBlock`, запись — только в загруженный куб; `getBlock` в незагруженном кубе даёт воздух, без исключения | `modules/vessels` (Combat, Beams, Arrivals, WarpDrive, Landings, HullDamage) |
| **4б** | | |
| Полный CubeLight, обмен краями, пересвет по skyTop, отметки высот | сделан | `tall/CubeLight`, `CubeMark`, тесты `CubeLightTest`, `CubeLightHeightsTest` |
| **Этап 6** (V1–V8, HokaViewArea) | не начат до этой задачи | — |
| Допуск без Sodium (§3.8) | на деле допуска нет: кубовые карты получают все ядра ≥ 0.29; проверка Sodium только в `/hocubes go` | `HoCubesCommand.java:301` |
| **Этап 7** | не начат | — |
| **§9 (удаление)** | ничего не удалено; `SodiumTallMixin` — основной путь Sodium (не только tall), удалять нельзя | — |
| **§11** | п. 1–9, 11, 13 закрыты на этапах 1–4 (см. «Сделано» в плане); п. 10 (Lithium) и 12 (`postProcessing`) не нужны | — |

## Шаг 2. Хвосты 3б / 4

Ревизия не нашла несделанного, что ведёт к потере данных или провалам:
- запись в незагруженный куб — исключение + отказ (§4.2), действия игрока вне `sent` отменяются с ресинком;
- сервер замораживает сущности у незагруженных кубов, клиент (P5) не пускает сущности по Y в куб вне окна;
- `vessels`: все чтения мира (луч выстрела `Combat`, подушка/высадка `Beams`, прилёт `Arrivals`, варп `WarpDrive`,
  посадка `Landings`, повреждения `HullDamage`, поиск выхода в `VesselsModule`) идут после `isCubeLoaded`
  (незагруженное = «промах/нельзя»), запись — только в загруженный куб; `getBlock` в незагруженном кубе на
  `CubeColumn` возвращает воздух без исключения и без загрузки в тике. Ожидание — через `areCubesLoaded`/`ensureCubes`,
  загрузка — `loadCubes` (асинхронно). Правок не потребовалось;
- `ships` (старые): замены §4.10 уже сделаны, правок не нужно.

**Не сделано сознательно — видимость сущностей по V (§4.8).** Правило `updateViewableRule` в Minestom одно на
сущность и уже занято модулями (`ships` — якоря), чужие модули тоже могут его ставить; общее правило ядра их бы
затёрло или требовало рефлексии во внутренности `EntityView`. Кроме того, это спрятало бы корабль/игрока в 200 блоках
над тобой (окно V = 10 кубов = 160 блоков) — для космоса это заметная потеря. Заморозка (физика) сделана. Вопрос
вынесен в `tasks/cubes-final-questions.md`.

## Шаг 3. Этап 6 — ванильная отрисовка без Sodium (ядро мода 0.83.0)

Проверял по байткоду `javap -c` Mojang-именованного jar из кэша Loom
(`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-…jar`): `ViewArea`,
`SectionOcclusionGraph` (+`$GraphStorage`), `Octree`, `SectionRenderDispatcher$RenderSection`, `LevelRenderer`.
После сборки проверил, что строки целей миксинов в jar переотображены в intermediary (`method_…`, `class_…`).

| Пункт | Что сделано | Файл |
|---|---|---|
| V1 | `setViewDistance`: `sectionGridSizeY = 2rY+1`, rY = min(«Дальность по высоте»+1, дальность прорисовки), ≥ 2 | `mixin/ViewAreaRingMixin` |
| V2 | `repositionCamera`: кольцо по Y (как ваниль по X/Z), окно прижато к границам мира | там же, `ViewRing.base/layerY` |
| V3 | `getRenderSection(III)` — слой `floorMod(Y, sizeY)`; `containsSection` — в окне | там же |
| V4 | `GraphStorage.<init>`: `@WrapOperation` на `NEW Octree` — дерево вокруг камеры, а не от дна мира | `mixin/OctreeHeightMixin` |
| V5 | не нужен: куб камеры всегда в окне (`initializeQueueForFullUpdate` берёт его или край мира — оба в окне) | — |
| V6, V7 | не нужны: клиент держит столбец целиком (пустые секции — заглушки `SparseSections`), `doesChunkExistAt` / `hasAllNeighbors` по столбцу верны | — |
| V8 | не нужен: блок-сущности есть у всего столбца, `SectionCopy` работает как в ванили | — |
| пустые секции | без Sodium в высоком мире набор `loadedEmptySections` тоже не ведём (иначе ~4094 long на столбец и `clone()` при каждом полном обновлении графа) | `mixin/SparseEmptySectionsMixin` |
| смена ползунка | «Дальность по высоте» без Sodium пересоздаёт ViewArea (`allChanged`), если радиус кольца изменился | `VanillaView.changed`, `VerticalView.option` |
| допуск | `/hocubes go`: ядро 0.83+ без Sodium или 0.31+ с Sodium (`Handshake.cubesReady`). Другого допуска на сервере нет — кубовые карты и раньше получали все ядра | `server/.../tall/Handshake`, `HoCubesCommand` |

Вместо подкласса `HokaViewArea` с `@Redirect NEW ViewArea` — `@Inject` прямо в `ViewArea` (правило про миксины):
результат тот же. С Sodium ничего не меняется: `ViewRing.radiusY` = 0, октодерево и набор пустых секций — как раньше
(`ViewArea` Sodium вообще не создаёт). Обычный мир (≤ 64 секций) — ваниль.

Итог при прорисовке 12 и высоте по умолчанию 10: 25·25·23 = 14 375 RenderSection (было 25·25·4094 ≈ 2,56 млн).

Тесты: `client/core/src/test/.../ViewRingTest` (радиус, прижатие к краям, слой ↔ секция при любой камере),
`server/src/test/.../tall/HandshakeVersionTest`.

**Как проверить в игре (без Sodium):** убрать Sodium из `mods`, ядро 0.83.0. Войти на `cosmos`/`cubes`: мир рисуется,
F3 «C: …/N» — N около 14–15 тыс. (не миллионы), память в норме. Полёт вверх-вниз на 300+ блоков: секции снизу/сверху
появляются, не пропадают. `/hocubes go` пускает без Sodium. Ползунок «Дальность по высоте» 4 → 20: мир
перестраивается, N растёт. Обычная карта (Anvil) — как раньше.
**С Sodium:** всё как в 0.82 (этот путь не менялся): мир, ползунки, F3.

