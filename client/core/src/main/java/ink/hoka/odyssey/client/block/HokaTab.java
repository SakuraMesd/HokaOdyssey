package ink.hoka.odyssey.client.block;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.75 Раздел креатива «Hoka Odyssey»: все свои предметы (items.json модуля hoitems) и свои блоки (blocks.json модуля
 * blocks) сервера — такие же стопки, как выдаёт сервер (ванильный носитель + метка hoka_item / hoka_block + название),
 * поэтому сервер их понимает. Список обновляется, когда сервер прислал новые описания.
 */
public final class HokaTab {

    private HokaTab() {}

    private static volatile List<ItemStack> STACKS = List.of();
    private static CreativeModeTab tab;

    /** Регистрация (main-точка входа, до заморозки реестров). */
    static void register() {
        tab = Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("hoka_odyssey", "hoka"),
                FabricItemGroup.builder()
                        .title(Component.literal("Hoka Odyssey"))
                        .icon(() -> { List<ItemStack> l = STACKS; return l.isEmpty() ? new ItemStack(Items.NOTE_BLOCK) : l.get(0).copy(); })
                        .displayItems((params, out) -> { for (ItemStack s : STACKS) out.accept(s.copy()); })
                        .build());
    }

    /** Новые описания с сервера (поток клиента). */
    static void update(JsonObject blocks, JsonObject items) {
        List<ItemStack> l = new ArrayList<>();
        if (items != null && items.has("items")) for (JsonElement el : items.getAsJsonArray("items")) {
            JsonObject d = el.getAsJsonObject();
            Item it = item(d.has("material") ? d.get("material").getAsString() : "minecraft:paper");
            l.add(stack(it, "hoka_item", d.get("id").getAsString(), d.has("name") ? d.get("name").getAsString() : d.get("id").getAsString(),
                    d.has("color") ? d.get("color").getAsString() : null));
        }
        if (blocks != null && blocks.has("blocks")) for (JsonElement el : blocks.getAsJsonArray("blocks")) {
            JsonObject d = el.getAsJsonObject();
            if (d.has("rot") && d.get("rot").getAsInt() != 0) continue;
            String carrier = d.getAsJsonObject("state").get("block").getAsString();
            Item it = carrier.endsWith("note_block") ? Items.NOTE_BLOCK : carrier.endsWith("_leaves") ? Items.OAK_LEAVES : Items.STRING;
            String id = d.get("id").getAsString();
            l.add(stack(it, "hoka_block", id, d.has("name") ? d.get("name").getAsString() : id, null));
        }
        STACKS = List.copyOf(l);
        rebuild();
    }

    private static Item item(String key) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(key)).orElse(Items.PAPER);
    }

    private static ItemStack stack(Item it, String tag, String id, String name, String color) {
        ItemStack s = new ItemStack(it);
        CompoundTag t = new CompoundTag();
        t.putString(tag, id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        Style st = Style.EMPTY.withItalic(false);
        if (color != null) { var c = TextColor.parseColor(color).result(); if (c.isPresent()) st = st.withColor(c.get()); }
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(st));
        return s;
    }

    /** Пересобрать содержимое раздела (кэш игры строится раз — при входе, когда описаний ещё нет). */
    private static void rebuild() {
        var mc = Minecraft.getInstance();
        if (tab == null || mc.player == null || mc.level == null) return;
        try {
            tab.buildContents(new CreativeModeTab.ItemDisplayParameters(mc.player.connection.enabledFeatures(),
                    mc.player.canUseGameMasterBlocks() && mc.options.operatorItemsTab().get(), mc.level.registryAccess()));
        } catch (Throwable ignored) { }
    }

    static void clear() { STACKS = List.of(); }
}
