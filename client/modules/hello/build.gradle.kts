// Клиентский модуль «hello» — шаблон: копировать для нового (поменять id в client-module.json, пакет, имя jar).
dependencies {
    compileOnly(project(":core"))      // API ядра (ink.hoka.odyssey.client.api) — в jar модуля НЕ попадает
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("hello.jar")
}
