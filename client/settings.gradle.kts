pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "hoka-odyssey-client"

include("core")             // ядро-мод (ставится игроку один раз): загрузчик клиентских модулей + API + крючки
include("modules:hello")    // шаблон клиентского модуля — копировать для нового
include("modules:planets")  // планеты: модель издали + затемнение входа
include("modules:sky")      // небо карты: градиент, узор, звёзды, кубические солнце и луна
include("modules:ships")    // корабли: модель из блоков, (далее) управление, камера и HUD
include("modules:vessels")  // корабли-модели (.bbmodel): отрисовка, посадка ПКМ, полёт пилота
include("modules:jetpack")  // реактивный ранец: модель на спине, тяга (пробел), плавный спуск (Shift), HUD топлива
