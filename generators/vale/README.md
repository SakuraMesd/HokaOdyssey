# Генератор `vale` v1 — долина у моря

Переносимый генератор (сервер + мод Hoka Odyssey, ядро 0.60+), id `vale`, высоты −500…2000, море 300.

- Рельеф: шум GenNoise (несколько слоёв): ~31% воды (море + мелкие озёра), равнины и холмы 300–380, редкие горы до ~520, снег выше ~455.
- Слои: трава → 3–4 земли → камень; пляжи — песок 3 + песчаник 2; дно — песок/гравий/глина; ниже Y 144 — глубинный сланец; на −500 бедрок.
- Биомы (цвет травы/воды): ocean, beach, plains, meadow, forest, birch_forest, taiga, snowy_slopes, river (озёра).
- Растения одиночными блоками: трава, высокая трава, папоротники, цветы (на лугах и пятнами на равнинах), опавшие листья в лесу.
- Объекты (свои, `demo-objects/` → `objects/`): категории `oak`, `birch`, `spruce`, `bushes`, `rocks`. Лес густой с полянами и редеющими краями, на равнинах одинокие дубы, ельник в горах (выше ~385), ели и на снегу, кусты, валуны.
- hasBlocks/uniform/highest считаются аналитически (проверка — `ValeGenTest.honestHints`).

Сборка: `gradle :generators:vale:build` → `run/generators/vale.jar`. Карта — `map.yml.example`.
generator_options: `sea_level` (300), `tree_density` (1.0, 0…2).
Объекты строит `server/src/test/.../ValeDemoObjects.java` (тест `writeDemoObjects` пишет их в `demo-objects/`).
