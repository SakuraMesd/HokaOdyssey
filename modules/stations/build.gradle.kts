// Модуль «Станции» (stations): регионы станций (коробки), видимые границы (рисует клиентский модуль stations),
// гравитация станции — через VacuumService чужого модуля gravity (softdepends; без него регионы работают без гравитации).
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // ClientModsApi: связь с клиентским модулем stations (softdepends)
    compileOnly(project(":stubs:gravity-api"))    // заглушка VacuumService — в jar НЕ кладём, класс придёт из gravity.jar
    testImplementation(project(":api"))
    testImplementation(project(":stubs:gravity-api"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar { archiveFileName.set("stations.jar") }
tasks.test { useJUnitPlatform() }
