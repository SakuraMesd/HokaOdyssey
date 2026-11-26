// Ядро-мод Hoka Odyssey: маленький, ставится один раз. Всё остальное приходит с сервера модулями.
version = "0.62.1"
group = "ink.hoka"

val mc = "1.21.11"

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mc", mc)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "mc" to mc)
    }
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("hoka-odyssey-${project.version}+$mc.jar")
}

// совместимость с Sodium (высокий мир): только для компиляции, в мод не входит. Jar — из сборки игрока (не в git).
dependencies {
    "modCompileOnly"(files("../libs/sodium-fabric-0.8.12+mc1.21.11.jar"))
}

// 0.59: общий API генераторов планет (gen-api) — тот же исходник, что у сервера, компилируется в ядро (второй копии нет).
// -Phoka.genApi=<папка с исходниками> — другой путь (например, пока gen-api не в дереве).
val genApi = (findProperty("hoka.genApi") as String?) ?: "../../gen-api/src/main/java"
the<SourceSetContainer>()["main"].java.srcDir(genApi)
