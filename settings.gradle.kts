rootProject.name = "HokaOdyssey"

include("api")             // API для модулей (то, к чему пишут разработчики модулей)
include("server")          // ядро: Minestom + загрузчик модулей → server.jar
include("modules:example") // шаблон модуля — копировать для нового
// include("modules:ships")   // корабли — В РАБОТЕ (нет главного класса), не собирается; включить, когда допишем
