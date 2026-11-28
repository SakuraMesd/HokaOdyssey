// Модуль «Корабли-модели» (vessels): корабль — модель .bbmodel; посадка ПКМ, полёт считает клиентский модуль vessels.
// Старый модуль ships (корабли из блоков) — отдельно, этот его не трогает.
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // ClientModsApi: связь с клиентским модулем vessels
    compileOnly(project(":modules:hoitems"))      // HoItems: топливо и улучшения — свои предметы с иконками (softdepends)
    compileOnly(project(":modules:blocks"))       // HoBlocks: руды астероидов (свои блоки) — топливные кристаллы (softdepends)
    testImplementation(project(":api"))
    testImplementation(project(":modules:hoitems"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { archiveFileName.set("vessels.jar") }
tasks.test {
    useJUnitPlatform()
    systemProperty("vessels.demo", rootProject.file("demo-objects/vessels").absolutePath)
}
