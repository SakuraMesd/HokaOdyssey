// Модуль «Свои блоки»: блоки с текстурами/моделями с сервера для игроков с модом Hoka Odyssey
// (носитель — ванильное состояние; мод рисует поверх). Описание — module/blocks/config.yml, файлы — module/blocks/assets/.
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // ClientModsApi — раздача описаний и текстур моду
    compileOnly(project(":modules:houi"))         // окно блока (window) — HoUi, если модуль есть
    testImplementation(project(":api"))
    testImplementation(project(":modules:clientmods"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// тесты — с реестром боевого сервера (блоки мода hoka_odyssey:*, server/build.gradle.kts generateHokaBlocks)
sourceSets.test { resources.srcDir(project(":server").tasks.named("generateHokaBlocks")) }

tasks.jar { archiveFileName.set("blocks.jar") }
