// Модуль «Свои звуки»: .ogg с сервера, звуки hoka:<id> — играет мод Hoka Odyssey (без ресурспака и перезагрузок).
dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":modules:clientmods"))
}

tasks.jar { archiveFileName.set("hosounds.jar") }
