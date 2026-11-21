package ink.hoka.odyssey.api;

import java.util.List;

/**
 * Описание модуля из module.yml в корне jar:
 * <pre>
 * id: ships                      # латиница, цифры, - и _ ; папка данных будет module/ships/
 * name: Корабли                  # для людей
 * version: 0.1.0
 * main: ink.hoka.odyssey.ships.ShipsModule
 * authors: [Takuyamo]
 * description: Полёты, кресло пилота
 * depends: [space]               # без них модуль не включится; включаются раньше
 * softdepends: [stats]           # если есть — включаются раньше, но не обязательны
 * </pre>
 */
public record ModuleDescriptor(String id, String name, String version, String main, List<String> authors,
                               String description, List<String> depends, List<String> softDepends) {

    public String display() { return name + " " + version + " (" + id + ")"; }
}
