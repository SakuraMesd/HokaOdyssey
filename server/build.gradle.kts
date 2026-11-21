// Ядро Hoka Odyssey → build/libs/server.jar (кладётся на сервер в Pterodactyl).
plugins { id("com.gradleup.shadow") }

dependencies {
    implementation(project(":api"))
    implementation("org.slf4j:slf4j-simple:2.0.17")
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
tasks.test { useJUnitPlatform() }
