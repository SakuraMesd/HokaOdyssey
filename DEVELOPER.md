# Hoka Odyssey — инструкция для разработчика и нейросети

> Этот файл — контекст для ИИ-ассистента (Claude, ChatGPT, Cursor и т.п.). Дайте его нейросети целиком
> перед задачей. Для Claude Code / Cursor / Codex можно положить копию в корень как `CLAUDE.md` / `AGENTS.md`,
> тогда он подхватится сам.

## 1. Что это за проект

**Hoka Odyssey** — Minecraft-сервер на **Minestom** (не Paper, не Spigot!) для клиентов **1.21.11**.
Игровая идея — космос по мотивам Cubic Odyssey: корабли, полёты, высадка на планеты.

Архитектура: **ядро** (мир, карты, права, загрузчик модулей) + **модули** (вся игра). Модуль — jar в папке
`module/` сервера, аналог плагина Paper. Новую механику почти всегда делаем **новым модулем**, ядро не трогаем.

| | |
|---|---|
| Язык | Java 25 |
| Сборка | Gradle 9 (`gradlew` или установленный `gradle`) |
| Движок | `net.minestom:minestom:2026.05.17-1.21.11` |
| Конфиги | YAML (snakeyaml 2.4) |
| Логи | slf4j |
| Тесты | JUnit 5 |
| Тестовый сервер | `2.152.87.39:25573` (вход с лицензией) |

## 2. Главные правила (обязательно для нейросети)

1. **Это Minestom, а не Bukkit/Paper.** Классов `org.bukkit.*`, `JavaPlugin`, `plugin.yml`, `Listener`,
   `@EventHandler` здесь НЕТ. Не выдумывай API: если не уверен в методе Minestom — проверь по исходникам
   (`net.minestom.server.*`, sources-jar в кэше Gradle) или по https://minestom.net/docs.
2. **Новая механика = новый модуль** в `modules/<id>/`. Ядро (`server/`) и API (`api/`) меняем только если
   без этого никак, и тогда — отдельным осознанным изменением.
3. Слушатели — только через `events()`, команды — только через `registerCommand()`, задачи — только через
   `scheduler()`. **Никогда** не регистрируй в `MinecraftServer.getGlobalEventHandler()` /
   `getSchedulerManager()` / `getCommandManager()` напрямую из модуля: после перезагрузки модуля старый код
   продолжит работать.
4. Всё, что модуль создал сам (сущности, `Instance`, потоки, открытые файлы), — убрать в `onDisable()`.
5. **Все команды начинаются с `/ho`** (`/hoship`, `/hoplanet`…). Админ-команды — только для операторов:
   `cmd.setCondition((s, c) -> !(s instanceof Player p) || server().isOp(p));`
6. **Тексты для игроков — на русском**, через Adventure (`Component.text("…", NamedTextColor.GREEN)`).
7. Код в слушателях и `scheduler().run/later/repeat` выполняется **в тике сервера** — никаких долгих операций
   (файлы, сеть, БД, тяжёлые расчёты). Их — в `scheduler().async(...)`, результат обратно в тик —
   `scheduler().run(...)`.
8. Чужой модуль используем только через **сервис** и только если он указан в `depends`/`softdepends`.
9. Нет глобального static-состояния, которое переживает перезагрузку модуля.
10. Перед сдачей: `gradle build` проходит, модуль включается без ошибок, `homodule reload <id>` работает.

## 3. Структура репозитория

```
HokaOdyssey/
├─ api/                  API для модулей (то, к чему пишут модули). compileOnly в модулях
│  └─ src/main/java/ink/hoka/odyssey/api/
│     OdysseyModule, ModuleContext, ModuleDescriptor, ModuleScheduler, ModuleConfig, Services, OdysseyServerApi
├─ server/               ядро → server/build/libs/server.jar
│  └─ src/main/java/ink/hoka/odyssey/server/
│     OdysseyServer        main: Minestom, карты, права, модули, консоль, остановка
│     ModuleManager, ModuleClassLoader, ModuleContextImpl, ServiceRegistry, ModuleCommand (/homodule)
│     maps/   Maps, HoMapCommand (/homap)       — миры в папке maps/
│     perms/  Ops, HoOpCommand (/hoop)          — операторы, ops.json
│     blocks/ BlockRules, MultiBlocks, Dir      — ванильная установка блоков
├─ modules/
│  └─ example/           шаблон модуля — копировать для нового
├─ run/                  локальный сервер для разработки (в git не идёт)
├─ README.md, MODULES.md, DEVELOPER.md
└─ settings.gradle.kts   сюда добавлять include("modules:<id>")
```

Папки **сервера** (рядом с `server.jar`): `module/` (jar модулей + `module/<id>/` данные модулей),
`maps/<карта>/` (миры), `ops.json` (операторы), `eula.txt`.

## 4. Как сделать новый модуль

1. Скопировать `modules/example` → `modules/<id>` (id: латиница/цифры/`-`/`_`, например `ships`).
2. `modules/<id>/build.gradle.kts`: `tasks.jar { archiveFileName.set("<id>.jar") }`.
3. `src/main/resources/module.yml`:
   ```yaml
   id: ships
   name: Корабли
   version: 0.1.0
   main: ink.hoka.odyssey.ships.ShipsModule
   authors: [Имя]
   description: Полёты и кресло пилота
   depends: []          # без них модуль не включится; включаются раньше
   softdepends: []      # необязательные; если есть — включаются раньше
   ```
4. Пакет `ink.hoka.odyssey.<id>`, главный класс:
   ```java
   public final class ShipsModule extends OdysseyModule {
       @Override protected void onEnable() {
           events().addListener(PlayerSpawnEvent.class, e -> { /* ... */ });
           registerCommand(new HoShipCommand(this));
           scheduler().repeat(this::tick, 1, 1);          // каждый тик
       }
       @Override protected void onDisable() { /* убрать свои сущности/инстансы */ }
   }
   ```
5. `settings.gradle.kts`: `include("modules:<id>")`.
6. `src/main/resources/config.yml` — значения по умолчанию (скопируется в `module/<id>/config.yml`).

## 5. API модуля

`OdysseyModule` (наследуемся от него):

| Метод | Что |
|---|---|
| `onLoad()` / `onEnable()` / `onDisable()` | жизненный цикл; исключение в `onEnable` — модуль не включится, остальные работают |
| `id()`, `descriptor()` | id и данные из module.yml |
| `logger()` | slf4j-логгер с именем модуля |
| `events()` | `EventNode<Event>` модуля — `addListener(Класс.class, e -> …)`; снимается целиком при выключении |
| `registerCommand(Command)` | команда Minestom; снимается при выключении |
| `scheduler()` | `run(r)`, `later(r, тиков)`, `repeat(r, задержка, период)` → `Task`; `async(r)` — отдельный поток (должен уважать `Thread.interrupted()`, ядро ждёт его ≤3 с при выключении) |
| `config()` | `ModuleConfig`: `getString/getInt/getLong/getDouble/getBoolean("a.b", def)`, `getStringList`, `keys("раздел")`, `set`, `save`, `reload` |
| `dataFolder()` | `module/<id>/` для своих файлов |
| `services()` | `register(Интерфейс.class, impl)`, `get(…)` → Optional, `require(…)`, `unregister(…)` |
| `context().module("id")` | другой включённый модуль (Optional) |
| `server()` | ядро, см. ниже |

`server()` (`OdysseyServerApi`):

| Метод | Что |
|---|---|
| `world()` / `spawn()` | основная карта `maps/world` и её точка появления |
| `map("имя")` | `Optional<InstanceContainer>` карты из `maps/` (загрузится при необходимости) |
| `mapNames()` | имена всех карт |
| `isOp(player)` | оператор ли игрок (для админ-команд) |
| `modules()`, `version()` | включённые модули, версия ядра |

Модули общаются через сервисы:
```java
// модуль space
public interface SpaceApi { Pos shipPosition(UUID ship); }
services().register(SpaceApi.class, new SpaceApiImpl());
// модуль ships, module.yml: depends: [space]
SpaceApi space = services().require(SpaceApi.class);   // брать заново после перезагрузок, не кэшировать навсегда
```

## 6. Что уже есть в ядре (не переделывать)

- **Карты** (`maps/<имя>/` в формате Anvil + `map.yml`): загрузка по требованию, автосохранение раз в 5 мин и
  при остановке, пустые выгружаются через 10 мин. Генерация `flat` (трава на y=40) или `void` (площадка на y=64).
- **Операторы** (`ops.json`), уровень прав 4 у OP.
- **Установка блоков как в ванили**: ступеньки (с углами), сундуки (соединение, Shift — не соединять), бочки/
  раздатчики/поршни по взгляду, печи и т.п. лицом к игроку, брёвна по оси, плиты (двойные), факелы/таблички/
  флаги/головы на стену, кнопки/рычаги, заборы/панели/стены с соединениями, двери и кровати (2 блока),
  открывание дверей/люков/калиток.
  Нет: рельсы, редстоун-провод, waterlogged, высокие растения, отваливание блоков без опоры.
- Игрок при входе: основная карта, креатив. Позиция и инвентарь между заходами **не сохраняются** (пока).

Команды (все — только OP и консоль):

| Команда | |
|---|---|
| `/homap list` / `tp <карта> [игрок]` / `create <имя> [flat\|void]` / `delete <карта> confirm` / `setspawn` | карты |
| `/hoop <ник>` / `/hoop` | дать/забрать OP / список |
| `/homodule list` / `info <id>` / `reload <id>` / `unload <id>` / `load <файл.jar>` | модули |
| `stop` (консоль) | остановка: модули выключаются, карты сохраняются |

## 7. Шпаргалка Minestom (самое нужное)

```java
// команда с аргументами и подсказками
public final class HoShipCommand extends Command {
    public HoShipCommand(OdysseyModule m) {
        super("hoship");
        setCondition((s, c) -> !(s instanceof Player p) || m.server().isOp(p));   // только OP
        setDefaultExecutor((s, c) -> s.sendMessage(Component.text("/hoship spawn <имя>", NamedTextColor.AQUA)));
        var name = ArgumentType.Word("имя");
        addSyntax((s, c) -> { /* c.get(name) */ }, ArgumentType.Literal("spawn"), name);
    }
}

// события (пакет net.minestom.server.event.*)
events().addListener(PlayerSpawnEvent.class, e -> { if (e.isFirstSpawn()) { /* … */ } });
events().addListener(PlayerBlockPlaceEvent.class, e -> { /* e.setCancelled(true) */ });
events().addListener(PlayerBlockInteractEvent.class, e -> { /* клик ПКМ по блоку */ });
events().addListener(PlayerUseItemEvent.class, e -> { /* ПКМ предметом в воздух */ });
events().addListener(PlayerMoveEvent.class, e -> { /* осторожно: каждый тик у каждого игрока */ });

// игроки, сообщения, телепорт между картами
for (Player p : MinecraftServer.getConnectionManager().getOnlinePlayers()) p.sendActionBar(Component.text("…"));
server().map("space").ifPresent(inst -> player.setInstance(inst, new Pos(0.5, 65, 0.5)));

// блоки
instance.setBlock(x, y, z, Block.OAK_STAIRS.withProperty("facing", "north"));
Block b = instance.getBlock(x, y, z);

// display-сущность (модели кораблей и т.п.); убрать в onDisable: entity.remove()
Entity e = new Entity(EntityType.BLOCK_DISPLAY);
e.editEntityMeta(BlockDisplayMeta.class, meta -> meta.setBlockState(Block.IRON_BLOCK));
e.setInstance(instance, new Pos(0, 65, 0));
```

Проверить точную сигнатуру — по исходникам Minestom этой версии, а не по памяти нейросети: API Minestom часто
меняется между версиями.

## 8. Сборка, запуск, проверка

```bash
gradle build            # ядро + модули + тесты
gradle runServer        # локальный сервер localhost:25565 (папка run/, модули из modules/*)
gradle deployModules    # пересобрать модули в run/module/
gradle :server:test     # тесты ядра
```

Цикл: правим код → `gradle deployModules` → в консоли запущенного сервера `homodule reload <id>` (сервер не
перезапускаем; jar можно заменять на живом сервере). Логи модуля — с его id в квадратных скобках.

Тесты логики без клиента — JUnit в `src/test/java`, в `@BeforeAll` вызвать `MinecraftServer.init()`
(пример: `server/src/test/.../blocks/BlockRulesTest.java`, `maps/MapsTest.java`).

**Выкладка на тестовый сервер** — делает администратор: `server.jar` и `module/<id>.jar` в папку сервера,
затем `homodule load <id>.jar` или `homodule reload <id>`.

## 9. Чек-лист перед сдачей

- [ ] `gradle build` без ошибок
- [ ] модуль в `modules/<id>`, добавлен в `settings.gradle.kts`, `module.yml` заполнен
- [ ] все команды `/ho…`, админские — с проверкой `isOp`
- [ ] слушатели/команды/задачи — только через `events()` / `registerCommand()` / `scheduler()`
- [ ] в `onDisable` убраны свои сущности/инстансы/потоки
- [ ] тяжёлое — в `async`, в тике только быстрое
- [ ] тексты игрокам — на русском
- [ ] `homodule reload <id>` проходит без ошибок и без дублей (сообщения/задачи не удваиваются)
- [ ] настройки — в `config.yml` с понятными комментариями

## 10. Шаблон запроса к нейросети

```
Контекст: файл DEVELOPER.md (ниже/в репозитории). Проект Hoka Odyssey на Minestom 2026.05.17-1.21.11,
Java 25, система модулей. Не используй Bukkit/Paper API.

Задача: сделай модуль <id> — <что должно происходить в игре, по шагам>.
Команды: /ho<…> (кто может: все / только OP). Настройки в config.yml: <какие>.
Зависит от модулей: <нет / какие и какой сервис нужен>.

Требования: соблюдай раздел «Главные правила» и «Чек-лист». Напиши все файлы модуля
(build.gradle.kts, module.yml, config.yml, классы), строку для settings.gradle.kts и как проверить в игре.
Если не уверен в методе Minestom — скажи, какой класс проверить, не выдумывай.
```

## 11. Идея игры (для контекста при проектировании)

- **Корабль внутри** — неподвижная постройка из блоков в своей карте (по нему можно ходить, сундуки работают);
  «полёт» — движется космос вокруг (сфера из display-сущностей со звёздами/планетами), а не корабль.
- **Полёт и гонки** — модель корабля из display-сущностей на невидимом управляемом мобе (счастливый гаст):
  движение считает клиент, задержки управления нет; серверу — проверки скорости и чекпоинтов.
- **Планеты** — отдельные карты в `maps/` со своей генерацией; посадка — переход с затемнением.
- Не копировать название, арт и узнаваемые элементы Cubic Odyssey — делаем своё «по мотивам».
