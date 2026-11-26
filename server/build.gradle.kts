// Ядро Hoka Odyssey → build/libs/server.jar (кладётся на сервер в Pterodactyl).
plugins { id("com.gradleup.shadow") }

dependencies {
    implementation(project(":api"))
    implementation("org.slf4j:slf4j-simple:2.0.17")
    compileOnly("it.unimi.dsi:fastutil:8.5.18")   // поля DynamicChunk (высокий мир); в рантайме — из Minestom
}

tasks.shadowJar {
    archiveBaseName.set("server")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest { attributes("Main-Class" to "ink.hoka.odyssey.server.OdysseyServer") }
}
tasks.build { dependsOn(tasks.shadowJar) }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    // старые тесты света проверяют синхронный путь (process/data в том же потоке); поток света — CubeLightAsyncTest
    systemProperty("hoka.light.async", "false")
    // 0.59: тесты переносимого генератора — настоящий jar noise-test и эталон для сверки с клиентом (gen-golden/)
    dependsOn(":generators:noise-test:jar")
    systemProperty("hoka.test.noise-jar", project(":generators:noise-test").layout.buildDirectory.file("libs/noise-test.jar").get().asFile.absolutePath)
    // 0.60: генератор planet (объекты) — настоящий jar
    dependsOn(":generators:planet:jar")
    systemProperty("hoka.test.planet-jar", project(":generators:planet").layout.buildDirectory.file("libs/planet.jar").get().asFile.absolutePath)
    systemProperty("hoka.test.golden-dir", rootProject.layout.projectDirectory.dir("gen-golden").asFile.absolutePath)
}
