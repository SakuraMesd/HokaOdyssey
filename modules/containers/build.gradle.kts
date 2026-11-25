// Модуль «Контейнеры»: сундуки и бочки в мире (вещи в NBT блока).
dependencies {
    compileOnly(project(":api"))
}

tasks.jar { archiveFileName.set("containers.jar") }
