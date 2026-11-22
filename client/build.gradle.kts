// Клиент Hoka Odyssey: ядро-мод (core) + клиентские модули (modules/*). Модули — не Fabric-моды, а jar с
// client-module.json; сервер (модуль clientmods) раздаёт их игрокам, ядро загружает на лету.
// Сборка: gradle build — ядро в core/build/libs, модули собираются в ../run/module/clientmods/jars (для runServer)
// и в build/clientmods (для выкладки на сервер).
plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.19.1" apply false
}

val mc = "1.21.11"

subprojects {
    apply(plugin = "net.fabricmc.fabric-loom-remap")
    repositories { mavenCentral() }

    dependencies {
        "minecraft"("com.mojang:minecraft:$mc")
        "mappings"(project.extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>().officialMojangMappings())
        "modImplementation"("net.fabricmc:fabric-loader:0.19.5")
        "modImplementation"("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))       // javac 25, байткод Java 21 (на нём 1.21.11)
    }
    tasks.withType<JavaCompile> {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
}

// все клиентские модули — в одну папку: для локального сервера и для выкладки
val collectClientModules by tasks.registering(Copy::class) {
    group = "hoka"
    description = "Собрать клиентские модули в run/module/clientmods/jars и build/clientmods"
    subprojects.filter { it.path.startsWith(":modules:") }.forEach { p ->
        dependsOn(p.tasks.named("remapJar"))
        from(p.tasks.named("remapJar"))
    }
    into(layout.projectDirectory.dir("../run/module/clientmods/jars"))
    doLast {
        copy {
            from(layout.projectDirectory.dir("../run/module/clientmods/jars"))
            into(layout.buildDirectory.dir("clientmods"))
        }
    }
}

tasks.register("build") {
    group = "build"
    dependsOn(":core:build", collectClientModules)
}
