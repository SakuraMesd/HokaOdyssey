// Шаблон модуля. Новый модуль: скопировать папку, поменять id/main в module.yml и пакет, добавить в settings.gradle.kts.
dependencies {
    compileOnly(project(":api"))   // API и Minestom уже есть в server.jar — в jar модуля не кладём
}

tasks.jar { archiveFileName.set("example.jar") }
