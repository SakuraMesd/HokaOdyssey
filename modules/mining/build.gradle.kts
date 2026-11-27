// Модуль «mining»: ломание блоков только инструментами; лазер Аксиома (луч — мод Hoka Odyssey, ломание — здесь).
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // канал ядра мода: op 11 — лазер
}

tasks.jar { archiveFileName.set("mining.jar") }
