package ink.hoka.odyssey.survival;

import ink.hoka.odyssey.clientmods.ClientModsApi;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.NetworkBuffer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 4 доп. слота инвентаря выживания (скафандр, джетпак, прочее ×2). Окно рисует мод Hoka Odyssey 0.67+ (свой экран
 * вместо ванильного): S2C op 9 — «включено» и содержимое, C2S op 9 — клик (слот, кнопка, shift). Правила кликов —
 * как у обычного слота (ЛКМ — взять/положить/поменять, ПКМ — половину/по одному, shift — в инвентарь); клиент
 * показывает итог сразу, сервер решает и присылает своё.
 */
final class Extras {

    static final int N = 4, OP = 9;
    private final Map<UUID, ItemStack[]> slots = new ConcurrentHashMap<>();
    private ClientModsApi client;

    void attach(ClientModsApi api) {
        client = api;
        if (api != null) api.onCore(OP, (p, data) -> MinecraftServer.getSchedulerManager().scheduleNextTick(() -> click(p, data)));
    }

    void detach() { if (client != null) client.offCore(OP); }

    ItemStack[] of(Player p) {
        return slots.computeIfAbsent(p.getUuid(), k -> new ItemStack[]{ItemStack.AIR, ItemStack.AIR, ItemStack.AIR, ItemStack.AIR});
    }

    void forget(Player p) { slots.remove(p.getUuid()); }

    /** Содержимое и «включено» — моду игрока. */
    void send(Player p) {
        if (client == null || !client.hasMod(p)) return;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            out.writeByte(1);
            out.writeBoolean(true);
            for (ItemStack it : of(p)) {
                byte[] bytes = NetworkBuffer.makeArray(buf -> buf.write(ItemStack.NETWORK_TYPE, it), MinecraftServer.process());
                out.writeInt(bytes.length);
                out.write(bytes);
            }
        } catch (IOException ignored) {
        }
        client.sendCore(p, OP, b.toByteArray());
    }

    private void click(Player p, byte[] data) {
        if (!p.isOnline()) return;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readUnsignedByte() != 1) return;
            int i = in.readUnsignedByte(), button = in.readUnsignedByte();
            boolean shift = in.readBoolean();
            if (i >= N) return;
            ItemStack[] s = of(p);
            ItemStack slot = s[i], cur = p.getInventory().getCursorItem();
            if (shift) {
                if (!slot.isAir() && p.getInventory().addItemStack(slot)) s[i] = ItemStack.AIR;
            } else if (button == 0) {
                if (!cur.isAir() && !slot.isAir() && cur.isSimilar(slot)) {
                    int room = slot.maxStackSize() - slot.amount(), n = Math.min(room, cur.amount());
                    slot = slot.withAmount(slot.amount() + n);
                    cur = cur.amount() - n <= 0 ? ItemStack.AIR : cur.withAmount(cur.amount() - n);
                } else { ItemStack t = slot; slot = cur; cur = t; }
                s[i] = slot;
                p.getInventory().setCursorItem(cur);
            } else if (button == 1) {
                if (cur.isAir()) {
                    if (!slot.isAir()) {
                        int take = (slot.amount() + 1) / 2;
                        cur = slot.withAmount(take);
                        slot = slot.amount() - take <= 0 ? ItemStack.AIR : slot.withAmount(slot.amount() - take);
                    }
                } else if (slot.isAir() || (cur.isSimilar(slot) && slot.amount() < slot.maxStackSize())) {
                    slot = slot.isAir() ? cur.withAmount(1) : slot.withAmount(slot.amount() + 1);
                    cur = cur.amount() - 1 <= 0 ? ItemStack.AIR : cur.withAmount(cur.amount() - 1);
                }
                s[i] = slot;
                p.getInventory().setCursorItem(cur);
            }
        } catch (IOException ignored) {
        }
        send(p);
    }
}
