// Клиентский модуль «sky»: своё небо карты (градиент по времени суток, узор, звёзды, туманность, кубические солнце и
// луна). Настройки — раздел sky: в map.yml карты, шлёт серверный модуль sky.
dependencies {
    compileOnly(project(":core"))
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("sky.jar")
}
