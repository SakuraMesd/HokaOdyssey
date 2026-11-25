// Модуль «Планеты»: модель издали рисует клиентский модуль planets, вход в планету — затемнение и перенос.
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))
}

tasks.jar { archiveFileName.set("planets.jar") }
