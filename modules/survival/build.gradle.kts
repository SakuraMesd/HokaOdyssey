// Модуль «survival»: выживание — здоровье, урон, голод, еда, смерть с выпадением вещей, сохранение игроков, /gm, PvP-зоны.
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // своё окно инвентаря у мода: доп. слоты по каналу ядра
    testImplementation(project(":api"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { archiveFileName.set("survival.jar") }
tasks.test { useJUnitPlatform() }
