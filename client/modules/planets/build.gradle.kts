// Клиентский модуль «planets»: планеты издали (скруглённый куб) и затемнение при входе (данные — модуль planets сервера).
dependencies {
    compileOnly(project(":core"))
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("planets.jar")
}
