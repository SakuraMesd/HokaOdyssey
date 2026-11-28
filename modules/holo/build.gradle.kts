// Модуль «Голограммы» (holo): панели в мире в кубоидном космическом стиле — рисует клиентский модуль holo
// (текст, предметы, картинки, шкалы, кнопки с действиями, страницы), редактор в игре (только OP).
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // ClientModsApi: связь с клиентским модулем holo (softdepends)
    compileOnly(project(":modules:hoitems"))      // HoItems: свои предметы по id (softdepends)
    compileOnly(project(":modules:houi"))         // HoUi: действие «открыть окно» (softdepends)
    testImplementation(project(":api"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { archiveFileName.set("holo.jar") }
tasks.test { useJUnitPlatform() }
