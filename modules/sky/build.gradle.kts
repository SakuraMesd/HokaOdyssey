// Модуль «Небо»: настройки неба карты (раздел sky: в map.yml) → клиентский модуль sky (градиент, узор, звёзды, кубическое солнце).
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))
}

tasks.jar { archiveFileName.set("sky.jar") }
