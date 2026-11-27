// Переносимый генератор «space» v1 (сервер + клиент): космос — астероиды, метеориты, скопления, руды и тайники.
// Только gen-api, StrictMath. jar → run/generators/.
dependencies {
    compileOnly(project(":gen-api"))
    testImplementation(project(":gen-api"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> { options.release.set(21) }      // клиент — Java 21
tasks.jar { archiveFileName.set("space.jar") }
tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

val deployGenerator by tasks.registering(Copy::class) {
    group = "odyssey"
    description = "Положить jar генератора в run/generators/"
    from(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("run/generators"))
}
tasks.build { dependsOn(deployGenerator) }
