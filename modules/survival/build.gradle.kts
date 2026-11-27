// Модуль «survival»: выживание — здоровье, урон, голод, еда, смерть с выпадением вещей, сохранение игроков, /gm.
dependencies {
    compileOnly(project(":api"))
}

tasks.jar { archiveFileName.set("survival.jar") }
