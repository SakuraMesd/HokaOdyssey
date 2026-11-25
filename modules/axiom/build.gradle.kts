// Модуль «Axiom»: серверная часть клиентского редактора мира Axiom 6.1.x (MC 1.21.11) по протоколу AxiomPaperPlugin (MIT).
// zstd-jni (сжатие туннеля и блок-сущностей) кладём внутрь jar модуля: в server.jar его нет.
dependencies {
    compileOnly(project(":api"))
    implementation("com.github.luben:zstd-jni:1.5.7-6")
    testImplementation(project(":api"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("axiom.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)   // проверка zstd в отдельном загрузчике
}

tasks.jar {
    archiveFileName.set("axiom.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // толстый jar: классы и нативные библиотеки zstd-jni рядом с нашими (грузит ModuleClassLoader модуля)
    from(configurations.runtimeClasspath.map { cp -> cp.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/versions/**", "module-info.class")
    }
}
