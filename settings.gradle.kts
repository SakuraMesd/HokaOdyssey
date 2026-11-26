rootProject.name = "HokaOdyssey"

include("gen-api")         // нейтральный API генератора планеты (Java 21, без зависимостей): сервер + ядро мода
include("api")             // API для модулей (то, к чему пишут разработчики модулей)
include("server")          // ядро: Minestom + загрузчик модулей → server.jar
include("modules:example") // шаблон модуля — копировать для нового
include("modules:containers") // сундуки и бочки в мире
include("modules:planets")    // планеты: модель издали, вход с затемнением
include("modules:sky")        // небо карты: раздел sky: в map.yml → клиентский модуль sky
include("modules:ships")      // корабли: физика на сервере, модель и (далее) управление — клиентский модуль ships
include("modules:clientmods") // клиентские модули мода Hoka Odyssey: раздача (подпись) + связь сервер↔клиент
include("modules:blocks")     // свои блоки: носитель + текстура/модель с сервера (рисует мод Hoka Odyssey)
include("modules:hoitems")    // свои предметы: иконка PNG или модель .bbmodel с сервера (рисует мод Hoka Odyssey)
include("modules:hosounds")   // свои звуки: .ogg с сервера, hoka:<id> (играет мод Hoka Odyssey)
include("modules:houi")       // окна и меню: раскладка JSON с сервера, рисует мод Hoka Odyssey
include("modules:axiom")      // Axiom (клиентский редактор мира): серверная часть по протоколу AxiomPaperPlugin, только OP
include("generators:noise-test") // переносимый генератор планеты «noise-test» v2 → run/generators/ (сервер и клиент)
include("generators:planet")     // генератор планеты «planet» v1: −500…2000, море 300, объекты из objects/ (рельеф — позже)
include("generators:vale")       // генератор «vale» v2: горы до ~1950, хребты, разломы, 24 биома, деревья-великаны, грибы, цветы
