// Переносимый генератор планеты «vale» v3 (сервер + клиент): только gen-api, StrictMath. jar → run/generators/.
// Горы до ~1950, хребты, мезы, тепуи, разломы, реки, 24 биома; процедурные великаны, грибы, цветы, рудные валуны + объекты из objects/.
dependencies {
    compileOnly(project(":gen-api"))
    // тесты: кэши рельефа не меняют результат; замер прогрузки окна игрока (ValeWindowBench, -Phoka.bench)
    testImplementation(project(":gen-api"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> { options.release.set(21) }      // клиент — Java 21
tasks.jar { archiveFileName.set("vale.jar") }
tasks.test {
    useJUnitPlatform()
    systemProperty("hoka.test.demo-objects-dir", rootProject.layout.projectDirectory.dir("demo-objects").asFile.absolutePath)
    findProperty("hoka.bench")?.let { systemProperty("hoka.bench", it.toString()) }
    maxHeapSize = "3g"
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { findProperty("hoka.bench") == null }
}

val deployGenerator by tasks.registering(Copy::class) {
    group = "odyssey"
    description = "Положить jar генератора в run/generators/"
    from(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("run/generators"))
}
tasks.build { dependsOn(deployGenerator) }
