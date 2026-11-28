// Модуль «Реактивный ранец» (jetpack): предмет в слот нагрудника, топливо в самом ранце, полёт считает клиентский
// модуль jetpack (тяга вверх и вперёд, плавный спуск), сервер списывает топливо и рассылает, кто летит.
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))   // ClientModsApi: связь с клиентским модулем jetpack
    compileOnly(project(":modules:hoitems"))      // HoItems: ранец и канистра — свои предметы с иконками (softdepends)
}

tasks.jar { archiveFileName.set("jetpack.jar") }

dependencies {
    testImplementation(project(":api"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
