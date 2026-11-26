// Переносимый генератор «noise-test» v2 (сервер + клиент): только gen-api, StrictMath. jar → run/generators/.
dependencies { compileOnly(project(":gen-api")) }

tasks.withType<JavaCompile> { options.release.set(21) }      // клиент — Java 21
tasks.jar { archiveFileName.set("noise-test.jar") }

val deployGenerator by tasks.registering(Copy::class) {
    group = "odyssey"
    description = "Положить jar генератора в run/generators/"
    from(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("run/generators"))
}
tasks.build { dependsOn(deployGenerator) }
