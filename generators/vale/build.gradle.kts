// Переносимый генератор планеты «vale» v2 (сервер + клиент): только gen-api, StrictMath. jar → run/generators/.
// Горы до ~1950, хребты, мезы, тепуи, разломы, реки, 24 биома; процедурные великаны, грибы, цветы + объекты из objects/.
dependencies { compileOnly(project(":gen-api")) }

tasks.withType<JavaCompile> { options.release.set(21) }      // клиент — Java 21
tasks.jar { archiveFileName.set("vale.jar") }

val deployGenerator by tasks.registering(Copy::class) {
    group = "odyssey"
    description = "Положить jar генератора в run/generators/"
    from(tasks.jar)
    into(rootProject.layout.projectDirectory.dir("run/generators"))
}
tasks.build { dependsOn(deployGenerator) }
