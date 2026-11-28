// Заглушка API чужого модуля gravity (tearsmilk): только сигнатуры VacuumService — для компиляции stations.
// В jar модулей НЕ кладётся (compileOnly): в рантайме класс приходит из module/gravity.jar на сервере.
dependencies {
    compileOnly(project(":api"))
}
