# Hoka Odyssey — контент и клиентский мод

> Как добавить в игру свои **блоки, предметы, звуки, окна, HUD и модели** — без ресурспака, без перезагрузки
> ресурсов у игроков и без пересборки мода. Для людей и для нейросети: дайте этот файл вместе с
> [DEVELOPER.md](DEVELOPER.md) (общие правила модулей) перед задачей.

## 1. Как это устроено

У игрока стоит лёгкий мод **Hoka Odyssey** (Fabric, ~80 КБ). Он ничего не решает сам: **сервер присылает всё
новое**, мод это рисует и проигрывает. Добавили блок, звук или окно на сервере → у игроков появилось сразу.

```
сервер (Minestom)                                   клиент (мод Hoka Odyssey)
 module/blocks   ─┐                                ┌─ блоки: носитель + наша модель/текстура
 module/hoitems  ─┤  файлы (PNG, .bbmodel, .ogg,   ├─ предметы: иконка/модель в руке и инвентаре
 module/hosounds ─┼─ JSON) через clientmods  ───►  ├─ звуки hoka:<id>
 module/houi     ─┤  (подписано, кэш по sha256)    ├─ окна и HUD по JSON-раскладке
 твой модуль     ─┘                                └─ клиентские мини-модули (jar) — свой код на клиенте
```

* Файлы кэшируются у игрока по sha256 — повторно не качаются.
* Текстуры пишутся прямо в видеокарту (ячейки атласа), звуки — из кэша. **Экрана загрузки ресурсов нет.**
* Игрок **без мода** видит ванильные носители (нотный блок, листву, бумагу) и не видит окон/HUD.

| Модуль сервера | Что даёт | Папка данных | Команда |
|---|---|---|---|
| `clientmods` | раздача файлов и клиентских модулей, подпись, связь сервер↔мод (`ClientModsApi`) | `module/clientmods/` | `/hoclient` |
| `blocks` | свои блоки | `module/blocks/` | `/hoblock` |
| `hoitems` | свои предметы (`HoItems`) | `module/hoitems/` | `/hoitem` |
| `hosounds` | свои звуки (`HoSounds`) | `module/hosounds/` | `/hosound` |
| `houi` | окна (`HoUi`) и HUD (`HoHud`) | `module/houi/` | `/houi` |

**Общий цикл для всего:** положил файлы в `module/<модуль>/assets/` → описал в `config.yml` (или JSON) →
`/<команда> reload` → у игроков обновилось.

## 2. Блоки — `module/blocks/`

Блок = ванильное состояние-**носитель** (его физику, хитбокс и скорость ломания игра знает сама) + наш вид.

```yaml
# module/blocks/config.yml
blocks:
  ruby_block:
    name: "Рубиновый блок"
    texture: ruby_block.png          # на все грани; уточнить: side/top/bottom или north/south/east/west/up/down
    sounds: { type: stone }
  lamp:
    name: "Лампа"
    model: lamp.bbmodel              # модель вместо текстур (поза по умолчанию)
    rotatable: true                  # ставится «лицом» к игроку
    sounds: { type: lantern, place: lamp_on }   # lamp_on = звук hoka:lamp_on из hosounds
  grass_tuft:
    name: "Пучок травы"
    type: passable                   # сквозь него ходят
    model: grass_tuft.bbmodel
```

| Поле | Значение |
|---|---|
| `type` | `solid` — полный, закрывает соседей (нотный блок, ~1100 мест); `transparent` — полный, но не закрывает (листва, 63 места) — для моделей и дырявых текстур; `passable` — проходимый декор (растяжка, 127 мест). По умолчанию: модель → `transparent`, иначе `solid` |
| `texture` / `side` / `top` / `bottom` / `north`… | PNG до 256×256 |
| `model` | `.bbmodel`. Формат «Java Block» — координаты 0..16; остальные форматы — центр модели в 0 |
| `rotatable` | 4 поворота (4 места носителя) |
| `layer` | `cutout` (по умолчанию), `solid`, `translucent` (полупрозрачность) |
| `sounds` | `type` (stone, wood, metal, glass, grass, sand, wool, lantern, copper, …) + `break/place/step/hit/fall` |
| `particle`, `ao` | текстура частиц ломания; мягкие тени (true) |

* Носители раздаются **один раз** и пишутся в `module/blocks/states.yml`. **Не правьте его** — иначе в
  построенных мирах блоки поменяются местами.
* Выдать: `/hoblock give ruby_block 16`. Предмет блока в руке выглядит как сам блок.

## 3. Предметы — `module/hoitems/`

```yaml
# module/hoitems/config.yml
items:
  ruby:
    name: "Рубин"
    color: red                       # white, gray, red, gold, yellow, green, aqua, blue, light_purple…
    lore: ["Редкий камень"]
    icon: ruby.png                   # плоская иконка (с объёмом, как ванильные)
  laser:
    name: "Лазер"
    material: iron_sword             # что под капотом (поведение и вид без мода); по умолчанию paper
    icon: laser_icon.png             # в инвентаре — иконка…
    model: laser.bbmodel             # …в руке и на земле — модель
    display: handheld                # item | handheld | block; у модели по умолчанию — вкладка Display из Blockbench
    stack: 1
```

* Выдать: `/hoitem give ruby 5`. Из модуля: `services().get(HoItems.class)` → `item("ruby", 3)`, `idOf(stack)`.
* Предмет помечен в `custom_data` меткой `hoka_item=<id>` (у блоков — `hoka_block=<id>`).

## 4. Звуки — `module/hosounds/`

```yaml
# module/hosounds/config.yml  (файлы .ogg — МОНО, иначе звук не из точки)
sounds:
  laser_shot:
    files:
      - laser1.ogg
      - {file: laser1.ogg, pitch: 0.9}      # варианты — игра выбирает случайно
      - {file: laser2.ogg, volume: 0.8, weight: 2}
    subtitle: "Выстрел лазера"
    range: 32
  space_ambient:
    files: [ambient.ogg]
    stream: true                            # длинные (музыка, фон) — потоком
```

* В игре звук называется `hoka:<id>`. Проверить: `/hosound play laser_shot`, `/hosound stop`.
* Из модуля: `services().get(HoSounds.class)` → `play(player, "laser_shot")`, `playAt(instance, pos, id, vol, pitch)`.

## 5. Окна — `module/houi/menus/<id>.json`

Окно рисует мод по JSON-раскладке. **Поправили файл → `/houi reload` → открытое окно перестроится сразу.**
Картинки — в `module/houi/assets/` (в раскладке путь без `ui/`: `"src": "shop/bg.png"`).

```json
{
  "size": [200, 120], "fit": [0.5, 0.6], "pixel": true,
  "styles": { "btn": { "type": "box", "radius": 2, "color": "#3C7D3A", "hover": { "color": "#4E9A4A" } } },
  "data": { "coins": 0, "goods": [] },
  "children": [
    { "type": "box", "w": 200, "h": 120, "color": "#1B2233", "border": "#3A4A6E", "bw": 1, "radius": 3 },
    { "type": "text", "x": 8, "y": 6, "text": "&lМагазин  &e{coins}" },
    { "type": "scroll", "x": 6, "y": 20, "w": 188, "h": 70, "children": [
      { "type": "repeat", "for": "goods", "w": 180, "h": "auto", "layout": "column", "gap": 2,
        "template": { "type": "box", "w": "fill", "h": 20, "color": "#222B40", "children": [
          { "type": "item", "item": "{.item}", "size": 16, "x": 2, "y": 2 },
          { "type": "text", "x": 22, "y": 6, "text": "{.name}" },
          { "style": "btn", "w": 40, "h": 14, "x": -3, "align": [1, 0.5], "origin": [1, 0.5],
            "action": "buy:{.id}", "disabled": "{coins} < {.price}", "tooltip": "Цена {.price}" } ] } } ] },
    { "style": "btn", "x": 140, "y": 100, "w": 54, "h": 14, "action": "close" }
  ]
}
```

**Узлы** (`type`): `group`, `box`, `text`, `item`, `image`, `slice` (9-slice), `bar`, `repeat`, `scroll`.

| Свойство | Что |
|---|---|
| `x y w h` | позиция и размер; `w/h`: число, `"50%"`, `"fill"`, `"auto"` |
| `align`, `origin` | точка крепления у родителя и у себя, `[0..1, 0..1]` |
| `layout` + `gap`, `padding`, `cols`, `cross` | `row` / `column` / `grid` |
| `style` | имя стиля (или несколько через пробел), свои свойства поверх |
| `if`, `disabled` | условия: `{lvl} >= 10 && {.owned} == 0`, `{msg} != ''` |
| `hover`, `press`, `transition` | свойства при наведении/нажатии (плавно) |
| `appear` | появление: `{alpha, x, y, scale, duration, delay, stagger, easing}` (easing: out, in, inout, back, bounce) |
| `action` | строка уходит на сервер при нажатии; `close` — закрыть окно |
| `tooltip`, `sound` | подсказка (строка или массив); звук нажатия (`hoka:<id>`, `none`) |
| текст | `text`, `color`, `font` (размер), `wrap`, `text_align`, `shadow`; цвета `&a`, `&#RRGGBB`, `&l` |
| `item` | `minecraft:bread`, `hoka:ruby` (свой предмет), `block:ruby_block` (свой блок); `size`, `count` |
| `image` / `slice` | `src` — файл из assets или `minecraft:textures/...`; у slice — `edge` / `border_l…` |
| `bar` | `value`, `max`, `color`, `color2`, `back`, `border`, `smooth` (сек — плавно) |

**Подстановки:** `{имя}` — данные окна, `{.поле}` — поле элемента списка (`repeat`), `{#}` — номер с 1.

**Код модуля** (`softdepends: [houi]`):

```java
HoUi ui = services().get(HoUi.class).orElseThrow();
ui.onAction("shop", (p, action) -> {
    if (action.startsWith("buy:")) {
        // … купить …
        JsonObject d = new JsonObject(); d.addProperty("coins", newCoins);
        ui.update(p, "shop", d);                 // окно обновится без переоткрытия
    }
});
ui.onClose("shop", p -> { /* игрок закрыл */ });
JsonObject data = new JsonObject(); data.addProperty("coins", 100);
ui.open(player, "shop", data);                    // false — у игрока нет мода
```

Не забудь в `onDisable()`: `ui.off("shop")`.

## 6. HUD — `module/houi/hud/<id>.json`

Тот же формат, что у окон, плюс положение на экране:

```json
{
  "size": [120, 30], "anchor": [0, 1], "offset": [6, -6], "scale": 1, "z": 0,
  "data": { "o2": 100 },
  "children": [
    { "type": "text", "x": 4, "y": 4, "text": "&bКислород {o2}%" },
    { "type": "bar", "x": 4, "y": 16, "w": 112, "h": 6, "value": "{o2}", "max": "100", "smooth": 0.6,
      "color": "#3FA9F5", "back": "#0B1220" }
  ]
}
```

`anchor` — точка экрана и та же точка слоя (`[0,1]` — левый нижний угол, `[0.5,0]` — верх по центру).

```java
HoHud hud = services().get(HoHud.class).orElseThrow();
hud.show(p, "oxygen", data);                         // показать (заново — с анимацией appear)
hud.update(p, "oxygen", d);                          // обновить данные
hud.hide(p, "oxygen");                               // убрать
hud.hideVanilla(p, List.of("food", "air"));          // спрятать ванильное (пустой список — вернуть всё)
```

Ванильные части: `health armor food air xp xp_level hotbar crosshair effects bossbar scoreboard mount
actionbar title held_item`.

## 7. Модели на сущностях

Повесить анимированную модель `.bbmodel` на любую сущность (обычно невидимую `INTERACTION`):

```java
ClientModsApi api = services().get(ClientModsApi.class).orElseThrow();
api.attachModel(entity, "models/blacksmith.bbmodel", 1f);   // файл — module/clientmods/files/models/…
api.playModelAnim(entity, "idle", 1f);                       // null — поза по умолчанию
api.detachModel(entity);
```

Проверка: `/hoclient model models/blacksmith.bbmodel`, `/hoclient anim idle`, `/hoclient unmodel`.

## 8. Свои файлы из модуля

Любой модуль может отдать моду свои файлы (их потом берут клиентские модули или раскладки):

```java
api.putFile(id(), "mymod/icon.png", bytes);   // путь — как его спросит клиент
api.publishFiles();                            // подписать и разослать
```

Так устроены `blocks`, `hoitems`, `hosounds`, `houi` — смотри их `publish()` как пример. Файлы без кода
можно класть и вручную в `module/clientmods/files/**` (+ `/hoclient reload`).

## 9. Клиентские мини-модули (свой код на клиенте)

Когда нужно то, что не описать файлом (своя камера, управление, эффект), — клиентский модуль: маленький jar,
который мод скачивает с сервера и загружает **без переустановки мода**.

```
client/modules/<id>/
  build.gradle.kts                       — как у client/modules/hello
  src/main/resources/client-module.json  — {"id": "...", "version": "0.1.0", "main": "пакет.Класс"}
  src/main/java/…/МойМодуль.java         — implements ClientModule
```

```java
public final class MyModule implements ClientModule {
    @Override public void enable(ClientContext ctx) {
        ctx.onMessage(bytes -> { /* от сервера */ });
        ctx.onTick(() -> { });
        ctx.onHud((g, delta) -> g.drawString(Minecraft.getInstance().font, "hi", 4, 4, -1));
        ctx.onFile("mymod/data.json", b -> { /* файл с сервера, и при каждой смене */ });
        var tex = ctx.texture("mymod/icon.png");          // текстура из файла, меняется на лету
        ctx.model("models/x.bbmodel").at(0, 64, 0).play("idle", 1);
        ctx.onMouse((dyaw, dpitch) -> false);              // true — съесть движение мыши
        ctx.onCamera((cam, pt) -> { });                    // своя камера (крен; cam.rotation — полный поворот кватернионом)
        ctx.onLocalTravel(player -> { });                  // прямо перед движением своего игрока (сила — в этом же тике)
        ctx.onInput(in -> ctx.setMove(in, 0, 0));          // ввод хода (keyPresses, вектор хода), с переназначенными клавишами
        ctx.onCollision((e, area, out) -> { });            // свои тела для столкновений (корабли под любым углом)
        ctx.hideHand(true);                                // не рисовать руку от первого лица
        ctx.onEntityTilt((e, pt) -> null);                 // повернуть модель сущности вокруг ног (игрок на палубе корабля)
        ctx.onClick((attack, held) -> false);              // ПКМ/ЛКМ: true — клик забран модулем, игра его не обрабатывает
        ctx.hideHud(true);                                 // 0.79: спрятать HUD игрока (свой HUD — кабина корабля)
        ctx.onContainer((screen, windowId) -> null);       // 0.80: своё оформление окна-контейнера сервера (размер, места
                                                           //       слотов, фон, подписи); слоты — те же, логика сервера не меняется
        ctx.send("привет".getBytes());                     // на сервер
    }
}
```

Сервер: `api.onReady("<id>", p -> …)`, `api.send(p, "<id>", bytes)`, `api.on("<id>", (p, bytes) -> …)`.

* Миксинов в мини-модулях **нет** — только крючки `ClientContext`. Нужен новый крючок → его добавляют в ядро
  мода (`client/core`), это единственный случай, когда мод придётся обновить у всех.
* Сборка: `cd client && gradle build` → jar в `run/module/clientmods/jars/`. На сервер — в
  `module/clientmods/jars/`, затем `/hoclient reload`.
* Список модулей **подписывается** ключом `module/clientmods/signing.key` (только на сервере и в `run/`,
  **никогда не в git**). Без подписи мод ничего не загрузит.

## 10. Ядро мода (`client/core`) — когда трогать

Почти никогда. Новый контент — файлами и модулями сервера. Ядро меняется, только если нужен новый движок
или крючок; после этого мод переустанавливают все игроки. Сборка: `cd client && gradle build` →
`client/core/build/libs/hoka-odyssey-<версия>+1.21.11.jar`.

Протокол ядра (канал `hoka_odyssey:s` / `hoka_odyssey:c`, первый байт — операция) описан в
`client/core/.../Net.java`: S2C 1 список модулей, 2 кусок файла, 3 сообщение модулю, 4 ресурсы, 5 файлы,
6 модели, 7 окна, 8 HUD; C2S 1 привет, 2 «докачай», 3 «загружено», 4 сообщение, 5 окна.

## 11. Чек-лист

- [ ] Файлы в `module/<модуль>/assets/`, описание в конфиге, `/<команда> reload` прошёл без ошибок в логе.
- [ ] Звуки — моно `.ogg`; текстуры блоков/предметов ≤ 256×256.
- [ ] `states.yml` блоков не правили руками.
- [ ] Окно/HUD открывается, кнопки доходят до сервера (`onAction`), `onDisable()` снимает обработчики.
- [ ] Новый клиентский модуль: `client-module.json`, собран, лежит в `module/clientmods/jars/`, `/hoclient` его видит.
- [ ] В git нет `signing.key`, токенов и паролей.
