// Модуль «Корабли»: чертежи кораблей (строит админ), призыв игроком, полёт и посадка.
dependencies {
    compileOnly(project(":api"))
    testImplementation(project(":api"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { archiveFileName.set("ships.jar") }
tasks.test { useJUnitPlatform() }
