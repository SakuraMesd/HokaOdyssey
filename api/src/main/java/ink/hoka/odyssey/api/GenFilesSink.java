package ink.hoka.odyssey.api;

/**
 * Мост ядро → модуль clientmods: jar переносимых генераторов планет раздаются клиентам файлами clientmods
 * ({@link OdysseyServerApi#generatorFiles()}). clientmods регистрирует этот сервис; ядро зовёт его, когда состав jar
 * сменился (пересобрать подписанный список и разослать). Нет сервиса — генерация на клиенте выключена.
 */
public interface GenFilesSink {
    void generatorFilesChanged();
}
