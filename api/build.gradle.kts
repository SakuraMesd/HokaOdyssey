// API модулей. Модуль подключает его как compileOnly — классы уже есть в server.jar.
plugins { `java-library` }

val minestomVersion: String by rootProject.extra

dependencies {
    api("net.minestom:minestom:$minestomVersion")   // модули работают с Minestom напрямую (события, сущности, мир)
    api("org.yaml:snakeyaml:2.4")                    // конфиги модулей (config.yml)
    api("org.slf4j:slf4j-api:2.0.17")                // логгер модуля
}

java { withSourcesJar() }
