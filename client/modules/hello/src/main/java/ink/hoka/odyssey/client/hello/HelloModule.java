package ink.hoka.odyssey.client.hello;

import ink.hoka.odyssey.client.api.ClientContext;
import ink.hoka.odyssey.client.api.ClientModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Шаблон клиентского модуля. Сервер ({@code /hoclient hello <текст>}) шлёт текст — показываем его вверху экрана
 * 5 секунд со значком {@code hello/badge.png} (файл с сервера: поменяли PNG — значок сменится сразу, без перезагрузки
 * ресурсов) и отвечаем серверу «показал».
 */
public final class HelloModule implements ClientModule {

    private String text = "";
    private long until;
    private Identifier badge;
    private boolean hasBadge;

    @Override
    public void enable(ClientContext ctx) {
        ctx.logger().info("hello: загружен");
        badge = ctx.texture("hello/badge.png");
        ctx.onFile("hello/badge.png", bytes -> {
            hasBadge = bytes != null;
            if (bytes != null) {                                                // новый значок — показать на 3 с
                if (text.isEmpty()) text = "значок обновлён";
                until = Math.max(until, System.currentTimeMillis() + 3000);
            }
        });
        ctx.onMessage(bytes -> {
            text = new String(bytes, StandardCharsets.UTF_8);
            until = System.currentTimeMillis() + 5000;
            ctx.send(("показал: " + text).getBytes(StandardCharsets.UTF_8));
        });
        ctx.onHud((g, delta) -> {
            if (System.currentTimeMillis() > until || text.isEmpty()) return;
            var font = Minecraft.getInstance().font;
            Component c = Component.literal(text);
            int icon = hasBadge ? 16 : 0, gap = hasBadge ? 6 : 0;
            int w = font.width(c) + icon + gap;
            int x = (g.guiWidth() - w) / 2, y = 10;
            g.fill(x - 6, y - 4, x + w + 6, y + 20, 0x90101820);
            if (hasBadge) g.blit(RenderPipelines.GUI_TEXTURED, badge, x, y, 0, 0, 16, 16, 16, 16);
            g.drawString(font, c, x + icon + gap, y + 4, 0xFFE8F0FF, false);
        });
    }
}
