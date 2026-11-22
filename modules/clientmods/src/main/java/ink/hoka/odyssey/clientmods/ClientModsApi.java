package ink.hoka.odyssey.clientmods;

import net.minestom.server.entity.Player;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Связь серверного модуля со своей клиентской частью (клиентский модуль с тем же id, что передаётся сюда).
 * Получить: {@code services().get(ClientModsApi.class)} (в module.yml — {@code softdepends: [clientmods]}).
 *
 * <pre>
 * api.onReady("ships", p -> api.send(p, "ships", bytes));       // клиентская часть загрузилась у игрока
 * api.on("ships", (p, bytes) -> ...);                            // сообщение от клиентской части
 * </pre>
 */
public interface ClientModsApi {

    /** У игрока стоит мод Hoka Odyssey (поздоровался). */
    boolean hasMod(Player p);

    /** У игрока загружен клиентский модуль {@code module}. */
    boolean isReady(Player p, String module);

    /** Отправить клиентскому модулю {@code module} игрока. Нет модуля — молча ничего. */
    void send(Player p, String module, byte[] data);

    /** Сообщения от клиентского модуля {@code module}. Снимается само при выключении модуля-владельца — нет; снимите в onDisable через {@link #off}. */
    void on(String module, BiConsumer<Player, byte[]> handler);

    /** Клиентский модуль {@code module} загрузился у игрока (в т.ч. после обновления модуля). */
    void onReady(String module, Consumer<Player> handler);

    /** Снять все свои обработчики модуля {@code module}. */
    void off(String module);

    // ───── файлы для клиентских модулей (мимо ресурсов игры: без пака и без перезагрузок) ─────

    /**
     * Положить файл (owner — id серверного модуля; path — как клиентский модуль его спросит: ctx.file / texture).
     * Видно игрокам после {@link #publishFiles()}.
     */
    void putFile(String owner, String path, byte[] data);

    void removeFile(String owner, String path);

    void clearFiles(String owner);

    /** Подписать список и разослать игрокам с модом — у них обновится сразу (текстуры — без перезагрузки). */
    void publishFiles();

    // ───── модели .bbmodel на сущностях (рисует мод; файл модели — через putFile или module/clientmods/files) ─────

    /** Повесить модель на сущность (обычно невидимую INTERACTION): мод рисует её там, курс — сущности. */
    void attachModel(net.minestom.server.entity.Entity e, String path, float scale);

    /** Играть анимацию модели на сущности ({@code null} — поза по умолчанию). */
    void playModelAnim(net.minestom.server.entity.Entity e, String anim, float speed);

    void detachModel(net.minestom.server.entity.Entity e);

    // ───── прямые команды ядру мода (окна и т.п.; протокол — client/core/.../Net.java) ─────

    /** Отправить ядру мода пакет: первый байт — op ядра, дальше payload. Без мода — молча ничего. */
    void sendCore(Player p, int op, byte[] payload);

    /** Пакеты ядра мода с этим op (C2S; 5 — окна). Один обработчик на op. */
    void onCore(int op, BiConsumer<Player, byte[]> handler);

    void offCore(int op);
}
