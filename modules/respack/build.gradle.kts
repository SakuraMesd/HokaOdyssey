// Модуль «Ресурспак»: общий пак сервера из файлов модулей, раздача по HTTP, отправка игрокам,
// ванильные ассеты клиента (формы блоков и т.п.) для генерации моделей. Сервис — ResourcePackApi.
dependencies {
    compileOnly(project(":api"))
}

tasks.jar { archiveFileName.set("respack.jar") }
