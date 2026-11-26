// Нейтральный API генератора планеты: чистая Java 21, без зависимостей. Один исходник — и в server.jar, и в ядро
// мода (client/core: sourceSets srcDir). Генераторы (generators/*) компилируются только против него.
plugins { `java-library` }

// клиент Minecraft 1.21.11 — Java 21: и API, и генераторы обязаны собираться под неё
tasks.withType<JavaCompile> { options.release.set(21) }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
