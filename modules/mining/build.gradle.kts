// Модуль «mining»: ломание блоков только инструментами; лазер Аксиома (луч — мод Hoka Odyssey, ломание — здесь).
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // канал ядра мода: op 11 — лазер
    compileOnly(project(":modules:blocks"))       // HoBlocks: свои блоки (руды космоса) по носителю
}

tasks.jar { archiveFileName.set("mining.jar") }
