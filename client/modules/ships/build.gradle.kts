// Клиентский модуль «ships»: рисует летящие корабли из блоков чертежа (данные — модуль ships сервера).
dependencies {
    compileOnly(project(":core"))      // API ядра (ink.hoka.odyssey.client.api) — в jar модуля НЕ попадает
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("ships.jar")
}
