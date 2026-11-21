// Общие настройки всех подпроектов. Сборка: gradle build → server/build/libs/server.jar + jar модулей.
// Локальный запуск для разработки: gradle runServer (папка run/, модули — run/module/).
plugins {
    java
    id("com.gradleup.shadow") version "9.6.1" apply false
}

val minestomVersion by extra("2026.05.17-1.21.11")   // последняя сборка Minestom под Minecraft 1.21.11

subprojects {
    apply(plugin = "java")
    group = "ink.hoka.odyssey"
    version = "0.1.0"
    repositories { mavenCentral() }
    extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
    tasks.withType<JavaCompile> {
        options.release.set(25)
        options.encoding = "UTF-8"
    }
}

// jar всех модулей (modules/*) → run/module/
val deployModules by tasks.registering(Copy::class) {
    group = "odyssey"
    description = "Собрать модули и положить их в run/module/"
    subprojects.filter { it.path.startsWith(":modules:") }.forEach { m ->
        dependsOn("${m.path}:jar")
        from(m.layout.buildDirectory.dir("libs")) { include("*.jar") }
    }
    into(layout.projectDirectory.dir("run/module"))
}

// локальный сервер для разработки: порт 25565, мир и модули в run/
tasks.register<JavaExec>("runServer") {
    group = "odyssey"
    description = "Запустить ядро локально из папки run/ (с модулями из run/module/)"
    dependsOn(":server:shadowJar", deployModules)
    workingDir = layout.projectDirectory.dir("run").asFile
    classpath = files(project(":server").layout.buildDirectory.file("libs/server.jar"))
    environment("SERVER_PORT", "25565")
    standardInput = System.`in`
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
