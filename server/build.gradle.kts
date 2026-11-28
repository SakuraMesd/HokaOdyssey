// Ядро Hoka Odyssey → build/libs/server.jar (кладётся на сервер в Pterodactyl).
plugins { id("com.gradleup.shadow") }

dependencies {
    implementation(project(":api"))
    implementation("org.slf4j:slf4j-simple:2.0.17")
    compileOnly("it.unimi.dsi:fastutil:8.5.18")   // поля DynamicChunk (высокий мир); в рантайме — из Minestom
}

tasks.shadowJar {
    archiveBaseName.set("server")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest { attributes("Main-Class" to "ink.hoka.odyssey.server.OdysseyServer") }
}
tasks.build { dependsOn(tasks.shadowJar) }

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    // старые тесты света проверяют синхронный путь (process/data в том же потоке); поток света — CubeLightAsyncTest
    systemProperty("hoka.light.async", "false")
    // 0.59: тесты переносимого генератора — настоящий jar noise-test и эталон для сверки с клиентом (gen-golden/)
    dependsOn(":generators:noise-test:jar")
    systemProperty("hoka.test.noise-jar", project(":generators:noise-test").layout.buildDirectory.file("libs/noise-test.jar").get().asFile.absolutePath)
    // 0.60: генератор planet (объекты) — настоящий jar
    dependsOn(":generators:planet:jar")
    systemProperty("hoka.test.planet-jar", project(":generators:planet").layout.buildDirectory.file("libs/planet.jar").get().asFile.absolutePath)
    // генератор vale (долина, свои объекты demo-objects/)
    dependsOn(":generators:vale:jar")
    systemProperty("hoka.test.vale-jar", project(":generators:vale").layout.buildDirectory.file("libs/vale.jar").get().asFile.absolutePath)
    systemProperty("hoka.test.demo-objects-dir", rootProject.layout.projectDirectory.dir("demo-objects").asFile.absolutePath)
    systemProperty("hoka.test.golden-dir", rootProject.layout.projectDirectory.dir("gen-golden").asFile.absolutePath)
}

// ───── Блоки мода в реестре Minestom (ядро мода 0.88+) ─────
// Minestom строит реестр блоков из block.json (данные net.minestom:data). Дописываем в конец те же блоки, что мод
// регистрирует у клиента (client/core PoolBlocks): solid_0000…, clear_0000…, pass_0000… — по одному состоянию, в том
// же порядке. Номера состояний у сервера и клиента совпадают (29671 + n для 1.21.11), сервер хранит и шлёт настоящий
// блок мода — без ванильных носителей. Размеры запасов — ровно как PoolBlocks.SOLID/CLEAR/PASS у мода.
val hokaPool = mapOf("solid" to 1024, "clear" to 1024, "pass" to 512)
val hokaRegistryDir = layout.buildDirectory.dir("generated/hoka-registry")
val generateHokaBlocks by tasks.registering {
    val cp = configurations.named("runtimeClasspath")
    inputs.property("pool", hokaPool.toString())
    outputs.dir(hokaRegistryDir)
    doLast {
        val dataJar = cp.get().files.first { it.name.startsWith("data-") && it.name.endsWith(".jar") }
        val text = zipTree(dataJar).matching { include("block.json") }.singleFile.readText()
        @Suppress("UNCHECKED_CAST")
        val blocks = groovy.json.JsonSlurper().parseText(text) as MutableMap<String, Any?>
        var maxState = -1
        var maxId = -1
        for (b in blocks.values) {
            val m = b as Map<*, *>
            maxId = maxOf(maxId, (m["id"] as Number).toInt())
            for (s in (m["states"] as Map<*, *>).values) maxState = maxOf(maxState, ((s as Map<*, *>)["stateId"] as Number).toInt())
        }
        val full = "[AABB[0.0, 0.0, 0.0] -> [1.0, 1.0, 1.0]]"
        var state = maxState + 1
        var id = maxId + 1
        val out = LinkedHashMap<String, Any?>(blocks)
        for ((kind, n) in hokaPool) for (i in 0 until n) {
            val name = "%s_%04d".format(kind, i)
            val solid = kind == "solid"
            val pass = kind == "pass"
            val e = LinkedHashMap<String, Any?>()
            e["id"] = id++
            e["translationKey"] = "block.hoka_odyssey.$name"
            e["explosionResistance"] = 6.0; e["friction"] = 0.6; e["defaultStateId"] = state
            e["correspondingItem"] = "minecraft:air"; e["canRespawnIn"] = pass; e["hardness"] = 1.5
            e["pushReaction"] = "BLOCK"; e["mapColorId"] = 0; e["occludes"] = solid; e["requiresTool"] = false
            e["blocksMotion"] = !pass; e["flammable"] = false; e["solid"] = !pass; e["solidBlocking"] = !pass
            e["lightBlock"] = if (solid) 15 else 0
            e["soundType"] = if (solid) "stone" else "glass"
            e["shape"] = full; e["collisionShape"] = if (pass) "[]" else full
            e["interactionShape"] = "[]"; e["occlusionShape"] = if (solid) full else "[]"; e["visualShape"] = "[]"
            e["redstoneConductor"] = false
            e["states"] = mapOf("[]" to mapOf("stateId" to state++))
            out["hoka_odyssey:$name"] = e
        }
        val dir = hokaRegistryDir.get().asFile
        dir.mkdirs()
        File(dir, "block.json").writeText(groovy.json.JsonOutput.toJson(out))
        logger.lifecycle("блоки мода в реестре: ванильных состояний ${maxState + 1}, блоков мода ${state - maxState - 1} (номера ${maxState + 1}…${state - 1})")
    }
}
sourceSets.main { resources.srcDir(generateHokaBlocks) }
tasks.shadowJar { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }   // свой block.json раньше данных Minestom
