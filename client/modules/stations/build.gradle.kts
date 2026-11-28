// Клиентский модуль «stations»: границы регионов станций (светящиеся рёбра, силовая плёнка вблизи), надпись
// входа/выхода, выделение указкой. Данные — серверный модуль stations. Нужно ядро мода с ClientContext.onWorld.
dependencies {
    compileOnly(project(":core"))      // API ядра (ink.hoka.odyssey.client.api) — в jar модуля НЕ попадает
    "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("stations.jar")
}
tasks.named<Test>("test") {
    useJUnitPlatform()
    // превью PNG: по умолчанию build/preview; для отчёта — -PstationsPreview=../tasks/stations-1-preview
    systemProperty("stations.preview", (findProperty("stationsPreview") as String?)?.let { rootProject.file(it).absolutePath }
        ?: layout.buildDirectory.dir("preview").get().asFile.absolutePath)
}
