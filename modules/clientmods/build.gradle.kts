// Модуль «Клиентские модули»: раздаёт игрокам с модом Hoka Odyssey клиентские модули (client/modules/*),
// подписывает их список и даёт серверным модулям связь со своими клиентскими частями (ClientModsApi).
dependencies {
    compileOnly(project(":api"))
}

tasks.jar { archiveFileName.set("clientmods.jar") }
