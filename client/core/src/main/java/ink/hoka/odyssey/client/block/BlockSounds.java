package ink.hoka.odyssey.client.block;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Звуки своего блока: основа — ванильный набор по имени ({@code type}: stone, wood, metal…; по умолчанию — как у
 * носителя), поверх — отдельные звуки {@code break / place / step / hit / fall} (свои {@code hoka:<id>} или
 * ванильные {@code minecraft:block.stone.break}), громкость и высота.
 */
final class BlockSounds {

    private BlockSounds() {}

    private static final Map<String, SoundType> PRESETS = Map.ofEntries(
            Map.entry("stone", SoundType.STONE), Map.entry("wood", SoundType.WOOD), Map.entry("metal", SoundType.METAL),
            Map.entry("glass", SoundType.GLASS), Map.entry("grass", SoundType.GRASS), Map.entry("gravel", SoundType.GRAVEL),
            Map.entry("sand", SoundType.SAND), Map.entry("wool", SoundType.WOOL), Map.entry("snow", SoundType.SNOW),
            Map.entry("ladder", SoundType.LADDER), Map.entry("anvil", SoundType.ANVIL), Map.entry("lantern", SoundType.LANTERN),
            Map.entry("bamboo", SoundType.BAMBOO), Map.entry("crop", SoundType.CROP), Map.entry("slime", SoundType.SLIME_BLOCK),
            Map.entry("honey", SoundType.HONEY_BLOCK), Map.entry("coral", SoundType.CORAL_BLOCK), Map.entry("scaffolding", SoundType.SCAFFOLDING),
            Map.entry("vine", SoundType.VINE), Map.entry("chain", SoundType.CHAIN), Map.entry("copper", SoundType.COPPER),
            Map.entry("deepslate", SoundType.DEEPSLATE), Map.entry("amethyst", SoundType.AMETHYST), Map.entry("moss", SoundType.MOSS),
            Map.entry("mud", SoundType.MUD), Map.entry("netherite", SoundType.NETHERITE_BLOCK), Map.entry("bone", SoundType.BONE_BLOCK),
            Map.entry("candle", SoundType.CANDLE), Map.entry("cherry_wood", SoundType.CHERRY_WOOD), Map.entry("bamboo_wood", SoundType.BAMBOO_WOOD),
            Map.entry("decorated_pot", SoundType.DECORATED_POT), Map.entry("tuff", SoundType.TUFF), Map.entry("calcite", SoundType.CALCITE));

    @SuppressWarnings("deprecation")
    static SoundType of(JsonObject s, BlockState carrier) {
        SoundType base = s.has("type") ? PRESETS.getOrDefault(s.get("type").getAsString(), carrier.getSoundType()) : carrier.getSoundType();
        return new SoundType(
                s.has("volume") ? s.get("volume").getAsFloat() : base.getVolume(),
                s.has("pitch") ? s.get("pitch").getAsFloat() : base.getPitch(),
                ev(s, "break", base.getBreakSound()), ev(s, "step", base.getStepSound()), ev(s, "place", base.getPlaceSound()),
                ev(s, "hit", base.getHitSound()), ev(s, "fall", base.getFallSound()));
    }

    private static SoundEvent ev(JsonObject s, String k, SoundEvent def) {
        return s.has(k) ? SoundEvent.createVariableRangeEvent(Identifier.parse(s.get(k).getAsString())) : def;
    }
}
