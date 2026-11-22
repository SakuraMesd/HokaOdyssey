// Модуль «Окна»: окна и меню, которые рисует мод Hoka Odyssey по раскладке с сервера (module/houi/menus/*.json).
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))
    compileOnly(project(":modules:hoitems"))   // тестовый магазин выдаёт свои предметы
}

tasks.jar { archiveFileName.set("houi.jar") }
