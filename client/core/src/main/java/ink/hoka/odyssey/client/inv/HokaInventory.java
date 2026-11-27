package ink.hoka.odyssey.client.inv;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * 0.67 Инвентарь выживания Hoka Odyssey: включается сервером (модуль survival, S2C op 9) — тогда вместо ванильного
 * окна инвентаря открывается {@link HokaInventoryScreen}. Здесь — 4 доп. слота (скафандр, джетпак, прочее ×2): их
 * хранит сервер, клиент только показывает и шлёт клики (C2S op 9: слот, кнопка, shift). На других серверах — ваниль.
 */
public final class HokaInventory {

    private HokaInventory() {}

    public static final int OP = 9, EXTRA = 4;
    public static volatile boolean enabled;
    static final ItemStack[] EXTRAS = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};

    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> {
            enabled = false;
            for (int i = 0; i < EXTRA; i++) EXTRAS[i] = ItemStack.EMPTY;
        });
    }

    /** S2C op 9: sub 1 — включено + 4 предмета (длина + байты предмета в формате игры). */
    public static void handle(DataInputStream in) throws IOException {
        int sub = in.readUnsignedByte();
        if (sub != 1) return;
        boolean on = in.readBoolean();
        ItemStack[] got = new ItemStack[EXTRA];
        for (int i = 0; i < EXTRA; i++) {
            int n = in.readInt();
            byte[] b = new byte[Math.max(0, n)];
            in.readFully(b);
            got[i] = decode(b);
        }
        Minecraft.getInstance().execute(() -> {
            enabled = on;
            System.arraycopy(got, 0, EXTRAS, 0, EXTRA);
        });
    }

    private static ItemStack decode(byte[] b) {
        var conn = Minecraft.getInstance().getConnection();
        if (b.length == 0 || conn == null) return ItemStack.EMPTY;
        try {
            return ItemStack.OPTIONAL_STREAM_CODEC.decode(new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(b), conn.registryAccess()));
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** Клик по доп. слоту серверу. */
    static void click(int slot, int button, boolean shift) {
        ink.hoka.odyssey.client.Net.sendOp(OP, out -> {
            out.writeByte(1);
            out.writeByte(slot);
            out.writeByte(button);
            out.writeBoolean(shift);
        });
    }
}
