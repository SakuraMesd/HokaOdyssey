package ink.hoka.odyssey.survival;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Сохранение игрока: инвентарь (все слоты, броня, вторая рука), здоровье, голод, насыщение, опыт, режим (если задан
 * командой /gm), карта и точка. Файл module/survival/players/&lt;uuid&gt;.dat (NBT, gzip), запись через временный
 * файл — при сбое старый не портится. Предметы — кодеком Minestom (все компоненты: зачарования, имена, свои метки).
 */
final class PlayerData {

    private PlayerData() {}

    static Path file(Path dir, UUID id) { return dir.resolve(id + ".dat"); }

    /** Записать (поток сервера: снимок берётся сразу, запись на диск — тут же, файл маленький). */
    static void save(Path dir, Player p, String map, String gameMode, float exhaustion, Logger log) {
        try {
            CompoundBinaryTag.Builder b = CompoundBinaryTag.builder();
            b.putInt("version", 1);
            b.putString("name", p.getUsername());
            ListBinaryTag.Builder<BinaryTag> inv = ListBinaryTag.builder();
            var pi = p.getInventory();
            for (int i = 0; i < pi.getSize(); i++) {
                ItemStack it = pi.getItemStack(i);
                if (it.isAir()) continue;
                BinaryTag t = ItemStack.CODEC.encode(Transcoder.NBT, it).orElse(null);
                if (t == null) continue;
                inv.add(CompoundBinaryTag.builder().putInt("slot", i).put("item", t).build());
            }
            b.put("inventory", inv.build());
            b.putFloat("health", p.isDead() ? 20f : p.getHealth());
            b.putInt("food", p.getFood());
            b.putFloat("saturation", p.getFoodSaturation());
            b.putFloat("exhaustion", exhaustion);
            b.putInt("level", p.getLevel());
            b.putFloat("exp", p.getExp());
            b.putInt("fire", p.getFireTicks());
            if (gameMode != null) b.putString("gamemode", gameMode);
            if (map != null && !p.isDead()) {
                Pos at = p.getPosition();
                b.putString("map", map);
                b.putDouble("x", at.x()).putDouble("y", at.y()).putDouble("z", at.z());
                b.putFloat("yaw", at.yaw()).putFloat("pitch", at.pitch());
            }
            Files.createDirectories(dir);
            Path f = file(dir, p.getUuid()), tmp = f.resolveSibling(f.getFileName() + ".tmp");
            BinaryTagIO.writer().write(b.build(), tmp, BinaryTagIO.Compression.GZIP);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("не сохранил игрока {}: {}", p.getUsername(), e.toString());
        }
    }

    /** Прочитать (null — нет файла или он битый: битый откладывается в .broken). */
    static CompoundBinaryTag load(Path dir, UUID id, Logger log) {
        Path f = file(dir, id);
        if (!Files.isRegularFile(f)) return null;
        try {
            return BinaryTagIO.reader().read(f, BinaryTagIO.Compression.GZIP);
        } catch (IOException e) {
            log.warn("файл игрока {} не читается ({}) — откладываю в .broken", f.getFileName(), e.toString());
            try { Files.move(f, f.resolveSibling(f.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            return null;
        }
    }

    /** Применить к игроку всё, кроме места (место — отдельно, после появления в мире). */
    static void apply(Player p, CompoundBinaryTag t) {
        var pi = p.getInventory();
        pi.clear();
        for (BinaryTag e : t.getList("inventory")) {
            if (!(e instanceof CompoundBinaryTag c)) continue;
            int slot = c.getInt("slot", -1);
            BinaryTag item = c.get("item");
            if (slot < 0 || slot >= pi.getSize() || item == null) continue;
            ItemStack it = ItemStack.CODEC.decode(Transcoder.NBT, item).orElse(ItemStack.AIR);
            if (!it.isAir()) pi.setItemStack(slot, it);
        }
        float hp = t.getFloat("health", 20f);
        p.setHealth(Math.max(1f, Math.min(hp, (float) p.getAttributeValue(net.minestom.server.entity.attribute.Attribute.MAX_HEALTH))));
        p.setFood(Math.max(0, Math.min(20, t.getInt("food", 20))));
        p.setFoodSaturation(Math.max(0, Math.min(20, t.getFloat("saturation", 5f))));
        p.setLevel(Math.max(0, t.getInt("level", 0)));
        p.setExp(Math.max(0, Math.min(1, t.getFloat("exp", 0))));
        int fire = t.getInt("fire", 0);
        if (fire > 0) p.setFireTicks(fire);
    }

    static GameMode gameMode(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase()) {
            case "survival", "0", "s", "выживание" -> GameMode.SURVIVAL;
            case "creative", "1", "c", "творческий" -> GameMode.CREATIVE;
            case "adventure", "2", "a", "приключение" -> GameMode.ADVENTURE;
            case "spectator", "3", "sp", "наблюдатель" -> GameMode.SPECTATOR;
            default -> null;
        };
    }
}
