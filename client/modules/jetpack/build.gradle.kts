// Клиентский модуль «jetpack»: модель ранца на спине у всех игроков в ранце (по повороту тела, присед), полёт своего
// игрока на тяге (пробел в воздухе) и плавный спуск (Shift), пламя из сопел, HUD топлива. Данные — модуль jetpack сервера.
dependencies {
    compileOnly(project(":core"))      // API ядра (ink.hoka.odyssey.client.api) — в jar модуля НЕ попадает
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("jetpack.jar")
}
