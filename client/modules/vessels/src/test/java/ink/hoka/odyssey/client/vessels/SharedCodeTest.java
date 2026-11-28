package ink.hoka.odyssey.client.vessels;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Общий код сервера и клиентского модуля — один и тот же файл (кроме строки package): зоны корпуса, поле расстояний,
 * подушка. Сервер проверяет мод пилота тем же расчётом — расходиться нельзя.
 */
class SharedCodeTest {

    static final Path SERVER = Path.of("../../../modules/vessels/src/main/java/ink/hoka/odyssey/vessels");
    static final Path CLIENT = Path.of("src/main/java/ink/hoka/odyssey/client/vessels");

    static String body(Path p) throws Exception {
        String s = Files.readString(p, StandardCharsets.UTF_8);
        return s.substring(s.indexOf('\n') + 1);
    }

    @Test
    void sameAsServer() throws Exception {
        for (String f : new String[]{"Zones.java", "DistField.java", "Cushion.java"})
            assertEquals(body(SERVER.resolve(f)), body(CLIENT.resolve(f)), f + " — копия с сервера (только package другой)");
    }
}
