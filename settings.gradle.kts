rootProject.name = "HokaOdyssey"

include("api")             // API для модулей (то, к чему пишут разработчики модулей)
include("server")          // ядро: Minestom + загрузчик модулей → server.jar
include("modules:example") // шаблон модуля — копировать для нового
include("modules:ships")      // корабли: физика на сервере, модель и (далее) управление — клиентский модуль ships
include("modules:clientmods") // клиентские модули мода Hoka Odyssey: раздача (подпись) + связь сервер↔клиент
include("modules:blocks")     // свои блоки: носитель + текстура/модель с сервера (рисует мод Hoka Odyssey)
include("modules:hoitems")    // свои предметы: иконка PNG или модель .bbmodel с сервера (рисует мод Hoka Odyssey)
include("modules:hosounds")   // свои звуки: .ogg с сервера, hoka:<id> (играет мод Hoka Odyssey)
include("modules:houi")       // окна и меню: раскладка JSON с сервера, рисует мод Hoka Odyssey
