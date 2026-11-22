// Модуль «Свои предметы»: предметы с иконкой (PNG) или моделью (.bbmodel) с сервера — рисует мод Hoka Odyssey.
// Описание — module/hoitems/config.yml, файлы — module/hoitems/assets/.
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))
}

tasks.jar { archiveFileName.set("hoitems.jar") }
