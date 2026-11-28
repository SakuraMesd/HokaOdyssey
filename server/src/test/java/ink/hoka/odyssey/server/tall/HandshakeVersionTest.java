package ink.hoka.odyssey.server.tall;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Сравнение версий ядра для допуска на кубовые карты (Handshake.cubesReady, этап 6). */
class HandshakeVersionTest {

    @Test
    void vanillaRender() {
        assertTrue(Handshake.atLeast("0.83.0", Handshake.VANILLA_RENDER));
        assertTrue(Handshake.atLeast("0.100.1", Handshake.VANILLA_RENDER));
        assertTrue(Handshake.atLeast("1.0.0", Handshake.VANILLA_RENDER));
        assertFalse(Handshake.atLeast("0.82.0", Handshake.VANILLA_RENDER));
        assertFalse(Handshake.atLeast("0.9.0", Handshake.VANILLA_RENDER), "0.9 < 0.83 (числа, не строки)");
    }
}
