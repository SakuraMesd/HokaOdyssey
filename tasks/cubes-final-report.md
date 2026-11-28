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

