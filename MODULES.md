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
