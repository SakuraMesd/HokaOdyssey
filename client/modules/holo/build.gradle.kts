// Клиентский модуль «holo»: голограммы в кубоидном космическом стиле (панель, рамка из светящихся кубиков, текст,
// предметы, картинки, шкалы, кнопки), наведение и нажатия, редактор (OP). Данные — серверный модуль holo.
dependencies {
    compileOnly(project(":core"))      // API ядра (ink.hoka.odyssey.client.api) — в jar модуля НЕ попадает
    "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("holo.jar")
}
tasks.named<Test>("test") {
    useJUnitPlatform()
    // превью PNG: по умолчанию build/preview; для отчёта — -PholoPreview=../tasks/holo-1-preview
    systemProperty("holo.preview", (findProperty("holoPreview") as String?)?.let { rootProject.file(it).absolutePath }
        ?: layout.buildDirectory.dir("preview").get().asFile.absolutePath)
}
