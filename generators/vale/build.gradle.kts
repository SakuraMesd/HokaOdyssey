// Переносимый генератор планеты «vale» v1 (сервер + клиент): только gen-api, StrictMath. jar → run/generators/.
// Рельеф из шума: равнины/холмы, редкие горы со снегом, пляжи, озёра, биомы, растения; объекты oak/birch/spruce/bushes/rocks.
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
