// Пример модуля-генератора планеты: «noise-test» — синусный рельеф. Шаблон для генераторов планет.
dependencies {
    compileOnly(project(":api"))   // API и Minestom уже есть в server.jar — в jar модуля не кладём
}

tasks.jar { archiveFileName.set("noisegen.jar") }
