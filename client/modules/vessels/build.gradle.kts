// Клиентский модуль «vessels»: корабли-модели (.bbmodel) — рисует, ПКМ — сесть, полёт пилота, камера, HUD.
// Данные — модуль vessels сервера. Нужно ядро мода 0.78+ (меши в моделях, поворот модели кватернионом).
dependencies {
    compileOnly(project(":core"))      // API ядра (ink.hoka.odyssey.client.api) — в jar модуля НЕ попадает
    "testImplementation"(project(":core"))                 // разбор тестовой модели (BbModel ядра)
    "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("vessels.jar")
}
tasks.named<Test>("test") { useJUnitPlatform() }
