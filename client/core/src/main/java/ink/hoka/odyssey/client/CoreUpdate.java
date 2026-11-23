package ink.hoka.odyssey.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Автообновление самого мода. В подписанном списке модулей сервер указывает версию и sha256 ядра; если она новее
 * нашей — ядро докачивается как обычный объект (с проверкой sha), кладётся рядом в папку mods под именем
 * {@code hoka-odyssey.next} (Fabric его не грузит), а при выходе из игры маленький процесс ждёт, пока игра
 * отпустит файл, и заменяет старый jar новым. Следующий запуск — уже на новой версии. Ничего удалять и копировать
 * руками не нужно.
 */
final class CoreUpdate {

    private CoreUpdate() {}

    private static String wantSha, wantVersion;
    private static boolean staged, hooked;

    /** Из списка модулей: версия и sha ядра на сервере. */
    static void offer(String version, String sha, int size) {
        if (version == null || sha == null || compare(version, ModuleManager.coreVersion()) <= 0) return;
        wantVersion = version;
        wantSha = sha;
        HokaOdysseyClient.LOG.info("Hoka Odyssey: на сервере ядро {} (у нас {}) — скачиваю", version, ModuleManager.coreVersion());
        if (ModuleManager.hasObject(sha)) stage();
        else ModuleManager.need(List.of(sha));
    }

    /** Пришёл объект — если это ядро, приготовить замену. */
    static void objectArrived(String sha) {
        if (sha.equals(wantSha) && !staged) stage();
    }

    private static void stage() {
        try {
            Path mods = FabricLoader.getInstance().getGameDir().resolve("mods");
            Path next = mods.resolve("hoka-odyssey.next");
            Path part = mods.resolve("hoka-odyssey.next.part");
            Files.copy(ModuleManager.object(wantSha), part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, next, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            staged = true;
            hook(next, mods.resolve("hoka-odyssey-" + wantVersion + "+1.21.11.jar"));
            HokaOdysseyClient.LOG.info("Hoka Odyssey: ядро {} готово — заменится при выходе из игры", wantVersion);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player != null) mc.player.displayClientMessage(Component.literal(
                        "§bМод Hoka Odyssey обновлён до " + wantVersion + " — перезапустите игру, когда будет удобно."), false);
            });
        } catch (IOException e) {
            HokaOdysseyClient.LOG.error("Hoka Odyssey: обновление ядра не подготовлено: {}", e.toString());
        }
    }

    /** При выходе из игры: дождаться, пока игра отпустит jar, удалить старый, поставить новый. */
    private static void hook(Path next, Path target) {
        if (hooked) return;
        hooked = true;
        Path current = currentJar();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                String cmd;
                if (win) {
                    cmd = "ping -n 6 127.0.0.1 >nul"
                            + (current != null ? " & del /f /q \"" + current + "\"" : "")
                            + " & move /y \"" + next + "\" \"" + target + "\"";
                    new ProcessBuilder("cmd", "/c", cmd).start();
                } else {
                    cmd = "sleep 5" + (current != null ? "; rm -f '" + current + "'" : "") + "; mv -f '" + next + "' '" + target + "'";
                    new ProcessBuilder("sh", "-c", cmd).start();
                }
            } catch (Exception ignored) {
            }
        }, "hoka-odyssey-update"));
    }

    /** Наш jar в папке mods (или null — запуск из среды разработки). */
    private static Path currentJar() {
        try {
            for (Path p : FabricLoader.getInstance().getModContainer(Net.NS).orElseThrow().getOrigin().getPaths())
                if (p.toString().endsWith(".jar")) return p.toAbsolutePath();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Сравнить версии «1.2.3» по числам. */
    static int compare(String a, String b) {
        String[] x = a.split("[.+-]"), y = b.split("[.+-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
