// Модуль «survival»: выживание — здоровье, урон, голод, еда, смерть с выпадением вещей, сохранение игроков, /gm.
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // своё окно инвентаря у мода: доп. слоты по каналу ядра
}

tasks.jar { archiveFileName.set("survival.jar") }
