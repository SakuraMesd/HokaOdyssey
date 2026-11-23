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
include("modules:ships")    // корабли: модель из блоков, (далее) управление, камера и HUD
