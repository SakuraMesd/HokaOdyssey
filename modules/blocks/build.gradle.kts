// Модуль «Свои блоки»: блоки с текстурами/моделями с сервера для игроков с модом Hoka Odyssey
// (носитель — ванильное состояние; мод рисует поверх). Описание — module/blocks/config.yml, файлы — module/blocks/assets/.
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // ClientModsApi — раздача описаний и текстур моду
}

tasks.jar { archiveFileName.set("blocks.jar") }
