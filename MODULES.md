# Модули Hoka Odyssey — как писать

Модуль — это jar в папке `module/` сервера. Аналог плагина Paper: свой класс-наследник `OdysseyModule`,
описание `module.yml`, своя папка данных `module/<id>/`, свои слушатели, команды и задачи.
Ядро включает модули по зависимостям и умеет перезагружать модуль без перезапуска сервера.

## Быстрый старт

1. Скопируйте `modules/example` в `modules/<ваш-модуль>`.
2. Поменяйте в `src/main/resources/module.yml` поля `id`, `name`, `main`, `authors`.
3. Переименуйте пакет `ink.hoka.odyssey.example` → `ink.hoka.odyssey.<ваш-модуль>`.
4. В `modules/<ваш-модуль>/build.gradle.kts` поменяйте имя jar: `archiveFileName.set("<id>.jar")`.
5. Добавьте строку в `settings.gradle.kts`: `include("modules:<ваш-модуль>")`.
6. `gradle runServer` — сервер на `localhost:25565`, ваш модуль уже в `run/module/`.

## module.yml

```yaml
id: ships                       # обязательно; a-z 0-9 _ - ; папка данных module/ships/
name: Корабли
version: 0.1.0
main: ink.hoka.odyssey.ships.ShipsModule   # обязательно
authors: [Takuyamo, Вася]
description: Полёты и кресло пилота
depends: [space]                # без них модуль не включится; включаются раньше
softdepends: [stats]            # если есть — включаются раньше; если нет — модуль всё равно включится
```

## Класс модуля

```java
public final class ShipsModule extends OdysseyModule {
    @Override protected void onEnable() {
        events().addListener(PlayerSpawnEvent.class, e -> ...);   // слушатель
        registerCommand(new LaunchCommand());                     // команда
        scheduler().repeat(this::tickShips, 1, 1);                // каждый тик
        int speed = config().getInt("max-speed", 20);             // конфиг
    }
    @Override protected void onDisable() {
        // убрать то, что создали сами: сущности, миры (Instance), свои потоки
    }
}
```

Что есть у модуля (`OdysseyModule`):

| Метод | Что это |
|---|---|
| `events()` | Узел событий модуля (`EventNode`). Снимается целиком при выключении |
| `registerCommand(cmd)` | Команда Minestom. Снимается при выключении |
| `scheduler()` | `run`, `later(r, тики)`, `repeat(r, задержка, период)`, `async(r)`. Всё отменяется при выключении |
| `config()` | `module/<id>/config.yml` (при первом запуске копируется `config.yml` из jar). `getInt/getString/…("a.b", def)`, `set`, `save`, `reload` |
| `dataFolder()` | `module/<id>/` — для своих файлов |
| `logger()` | Логгер с именем модуля |
| `services()` | Реестр сервисов: дать API другим модулям / взять чужое |
| `server()` | Ядро: `world()` — основной мир, `spawn()`, `modules()`, `version()` |
| `context().module("id")` | Другой включённый модуль |

Всё остальное — напрямую API Minestom (`MinecraftServer.*`, сущности, миры, пакеты):
https://minestom.net/docs

## Как модули общаются

Через сервисы. Модуль-владелец регистрирует интерфейс, другой (с `depends: [владелец]`) получает его:

```java
// модуль space
public interface SpaceApi { Pos shipPosition(UUID ship); }
services().register(SpaceApi.class, new SpaceApiImpl());

// модуль ships, depends: [space]
SpaceApi space = services().require(SpaceApi.class);
```

Классы модуля видны только модулям, у которых он в `depends`/`softdepends`.

## Правила (чтобы перезагрузка не ломала сервер)

1. Слушатели — только через `events()`, команды — `registerCommand`, задачи — `scheduler()`.
   Не регистрируйте в `MinecraftServer.getGlobalEventHandler()` и `getSchedulerManager()` напрямую — после
   перезагрузки модуля старый код продолжит работать.
2. Всё, что создали сами (сущности, `Instance`, потоки, открытые файлы), убирайте в `onDisable()`.
3. Не храните ссылки на объекты другого модуля дольше, чем он включён: после его перезагрузки это будут
   объекты «старой» версии. Берите сервис заново.
4. Код в `run/later/repeat` и слушателях выполняется в тике сервера — без долгих операций (файлы, сеть, БД —
   в `scheduler().async`, результат обратно в тик через `scheduler().run`).
5. Ошибка в `onEnable` — модуль не включится, остальные работают. Смотрите `homodule list`.
6. Берёте чужой сервис — укажите его модуль в `depends` (или `softdepends`). Тогда при перезагрузке того модуля
   ядро перезагрузит и ваш, и вы получите новый сервис. Без этого у вас останется объект старой версии.
7. Свои классы событий (`implements Event`) — только если без них никак; вызывайте их через `events().call(...)`
   своего модуля, не через глобальный обработчик: иначе после перезагрузки старая версия модуля останется в памяти.
8. Async-задачи должны завершаться по `Thread.interrupted()` — при выключении модуля ядро ждёт их не дольше 3 с.

## Генератор планеты

Каждая планета — отдельная высокая карта (`maps/<планета>/map.yml`: `tall: true`, `store: cubes`) со своим
генератором.

### Переносимый генератор (рекомендуется, ядро мода 0.59+)

Пишется ОДИН раз на `gen-api` (пакет `ink.hoka.odyssey.gen`, чистая Java 21, без Minestom/MC) и работает и на
сервере, и у клиента: нетронутые кубы (нет на диске, никто не правил) сервер не генерирует и не шлёт — шлёт метку
op 21, клиент генерирует сам в фоне. Пример целиком — `generators/noise-test` (jar → `run/generators/`, ядро
грузит папку `generators/` при запуске; модуль может поставить свой jar через `registerGenerator(Path)`).

```java
public final class MarsGen implements GenProvider {             // класс указан в hoka-gen.json
    public String id() { return "mars"; }
    public int version() { return 1; }                          // поменял вывод — увеличь (иначе клиенты разойдутся)
    public GenBounds defaultBounds() { return new GenBounds(-1000, 1000, 0); }
    public PlanetGenerator create(GenContext ctx) {             // свой экземпляр на сервере и у каждого клиента
        int stone = ctx.ids().defaultState("minecraft:stone");  // номера состояний — через ctx.ids(), не константами
        GenNoise n = new GenNoise(ctx.seed());
        return out -> { /* out.set(lx, ly, lz, stone), out.fillY(...), out.biome(...) */ };
    }
}
```
`hoka-gen.json` в корне jar: `{"id":"mars","version":1,"provider":"my.pkg.MarsGen"}`. Свои классы — НЕ в пакете
`ink.hoka.odyssey.gen`. Зависимость в сборке — только `compileOnly(project(":gen-api"))`, `options.release = 21`.

Правила (проверяются при загрузке jar — GenBytecodeCheck — и сверкой с клиентом):
1. Только `StrictMath` (Math.sin/cos/tan/pow/exp/log/… запрещены: 1 ulp разницы между JVM и платформами — другой
   рельеф у клиента). `+ − × /`, `Math.sqrt/floor/abs/min/max` можно. Случайность — `GenRandom`/`GenNoise` от сида и
   координат; без `Random()`, `ThreadLocalRandom`, времени, `parallelStream`.
2. Руды и тайники — только `out.secret(...)` и только от `ctx.secretSeed()` (map.yml `secret_seed`, ядро создаёт
   само; клиенту не уходит). У клиента `out.secrets() == false` — их присылает сервер, когда игрок рядом (op 22).
   `uniform()` для куба с секретами — `-1`; `mayHaveSecrets()` — true там, где они бывают.
3. `hasBlocks`/`uniform`/`highest` — как у старого API (скорость и свет); `highest` обязателен или pregen — иначе
   сервер генерирует кубы ради высот.
4. Встроенные `flat` и `void` — тоже переносимые (код в gen-api, jar не нужен).

Раздаёт jar клиентам модуль `clientmods` (подписанный список файлов, клиент кэширует по sha). Нет clientmods —
генерация только на сервере, как раньше. Отладка: `/hocubes genstats`, `/hocubes gencheck [ник] [N]`,
`/hocubes gen on|off <ник>`; эталон сервер↔клиент — `gen-golden/<id>@<v>.txt` (GenPortableTest).

### Генератор модуля (старый API, только сервер)

```java
public final class MarsModule extends OdysseyModule {
    protected void onEnable() { registerGenerator(new MarsProvider()); }   // снимется при выключении сам
}

public final class MarsProvider implements GeneratorProvider {
    public String id() { return "mars"; }                // map.yml: generator: mars
    public int version() { return 1; }                   // поменял рельеф — увеличь
    public Bounds defaultBounds() { return new Bounds(-1000, 1000); }   // где вообще есть блоки (Y; size — квадрат X/Z)
    public WorldGenerator create(GenSettings s) { return new MarsGen(s.seed()); }   // экземпляр на карту
}

final class MarsGen implements WorldGenerator {
    public void generate(CubeBuffer out) {               // куб 16³: out.cx/cy/cz, set/setAbs/fill/fillY/biome
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) { /* ... */ }
    }
    public boolean hasBlocks(int cx, int cy, int cz) { return (cy << 4) <= 300; }       // выше — небо: generate не зовут
    public Block uniform(int cx, int cy, int cz) { return (cy << 4) + 15 < -200 ? Block.STONE : null; }   // недра
    public int highest(int x, int z, int yFrom, int yTo, HeightKind k) { return UNKNOWN; }   // не уверен — UNKNOWN
}
```

Правила генератора:
1. **Детерминированный и потокобезопасный.** Ядро зовёт его параллельно из многих потоков; один и тот же куб
   обязан всегда выходить одинаковым (случайность — только хэш от `seed` и координат, без `Math.random`, без
   общих `HashMap`). Тогда на диск пишутся только правки игроков — планету 100k×100k можно держать. Проверка:
   `/hocubes gentest <карта>`. Недетерминированный (`deterministic() = false`) — каждый куб ложится на диск,
   только для маленьких карт.
2. **`hasBlocks` и `uniform` — главное для скорости.** На планете высотой ~2000 блоков ~125 кубов на столбец;
   если генератор быстро говорит «здесь пусто» / «здесь сплошной камень», генерируются только 3–8 кубов у
   поверхности. Врать нельзя (`gentest` проверяет).
3. **`highest`** — верх колонки без генерации кубов (свет). Ошибка портит свет; не уверен — `UNKNOWN`, тогда
   высоты возьмёт индекс pregen (`/hocubes pregen`) или ядро посчитает по кубам.
4. **Деревья и камни на стыке кубов**: якоря фич — от хэша ячейки сетки (сид + координаты ячейки), каждый куб
   перебирает ячейки вокруг себя и рисует фичу через `setAbs` — лишнее (не свой куб) отбрасывается. Радиус фичи
   держите ≤ 16 блоков.
5. **Поменяли логику — `version()` +1.** Правки игроков на диске останутся, нетронутое сгенерируется новой
   версией, индекс pregen старой версии перестанет использоваться (нужен новый pregen).

map.yml планеты: `generator: mars`, `seed: <число>`, по желанию `gen_min_y`/`gen_max_y`/`gen_size` (поверх
`defaultBounds`) и `generator_options: {…}` (в `GenSettings.options`). `generator_seen` пишет ядро.

**Перезагрузка модуля-генератора** запрещена, пока карта с ним загружена или идёт pregen (иначе на одной карте
смешались бы кубы двух версий). Цикл разработки: `/homap unload mars` → `homodule reload marsgen` → снова на
карту. Основная карта `world` грузится до модулей — её генератор только встроенный (`flat`/`void`).

## Консоль сервера

| Команда | Что делает |
|---|---|
| `homodule list` | Список: ✔ включены, ✘ с ошибкой (и почему) |
| `homodule info <id>` | Подробности модуля |
| `homodule reload <id>` | Перечитать jar из `module/` и перезапустить модуль (и зависящие от него) |
| `homodule unload <id>` | Выключить (и зависящие) |
| `homodule load <файл.jar>` | Включить новый jar, положенный в `module/` |
| `stop` | Остановить сервер (модули выключатся по порядку) |

Цикл разработки: поправили код → `gradle deployModules` → в консоли запущенного сервера `homodule reload <id>`.
Jar можно заменять, не останавливая сервер: ядро грузит модуль из своей копии в `module/.loaded/`.
