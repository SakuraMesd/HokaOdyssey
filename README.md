# Hoka Odyssey

Сервер на Minestom (Minecraft 1.21.11) с системой модулей. Ядро — мир и загрузчик модулей,
вся игра — в модулях (папка `module/`). Как писать модуль — [MODULES.md](MODULES.md).

## Структура

| Папка | Что |
|---|---|
| `api/` | API для модулей (`OdysseyModule`, конфиг, планировщик, сервисы). Модули подключают его как `compileOnly` |
| `server/` | Ядро → `server/build/libs/server.jar` |
| `modules/example/` | Шаблон модуля |
| `run/` | Локальный сервер для разработки (не коммитится) |

## Нужно

- JDK 25
- Gradle 9 (или `gradlew`)

## Команды

```
gradle build            # ядро + все модули
gradle runServer        # локальный сервер на localhost:25565 с модулями из modules/*
gradle deployModules    # пересобрать модули в run/module/ (потом в консоли: homodule reload <id>)
```

## Боевой сервер

Pterodactyl, «Hoka Odyssey», вход `2.152.87.39:25573`. На сервер кладутся `server.jar` и модули в `module/`.
