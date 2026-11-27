package ink.hoka.odyssey.planet.space;

import ink.hoka.odyssey.gen.GenRandom;

/**
 * Лут тайников в астероидах: SNBT сундука («Items», как у игры). 3–7 стопок: ванильные ценности и руды космоса
 * (свои блоки модуля blocks — носитель + метка hoka_block и название).
 */
final class Loot {

    private Loot() {}

    /** id, мин, макс, вес; id с «hoka:» — свой блок (носитель note_block). */
    private static final Object[][] TABLE = {
            {"minecraft:iron_ingot", 2, 8, 14}, {"minecraft:gold_ingot", 1, 4, 8}, {"minecraft:copper_ingot", 3, 10, 12},
            {"minecraft:redstone", 4, 16, 10}, {"minecraft:lapis_lazuli", 3, 9, 7}, {"minecraft:diamond", 1, 2, 3},
            {"minecraft:emerald", 1, 3, 4}, {"minecraft:ender_pearl", 1, 2, 4}, {"minecraft:experience_bottle", 2, 6, 6},
            {"minecraft:glowstone_dust", 3, 12, 6}, {"minecraft:quartz", 3, 10, 6}, {"minecraft:amethyst_shard", 2, 8, 6},
            {"minecraft:golden_apple", 1, 1, 2}, {"minecraft:cooked_beef", 2, 6, 6}, {"minecraft:torch", 4, 16, 6},
            {"hoka:nickel_ore|Никелевая руда", 2, 6, 7}, {"hoka:cobalt_ore|Кобальтовая руда", 1, 5, 6},
            {"hoka:silver_ore|Серебряная руда", 1, 4, 5}, {"hoka:platinum_ore|Платиновая руда", 1, 3, 3},
            {"hoka:uranium_ore|Урановая руда", 1, 2, 2}, {"hoka:magnesium_ore|Магниевая руда", 2, 6, 6},
            {"hoka:silicon_ore|Кремниевая руда", 2, 6, 7},
    };

    static String chest(GenRandom g) {
        int total = 0;
        for (Object[] r : TABLE) total += (Integer) r[3];
        int n = 3 + g.nextInt(5);
        boolean[] used = new boolean[27];
        StringBuilder sb = new StringBuilder("{Items:[");
        for (int i = 0; i < n; i++) {
            int t = g.nextInt(total);
            Object[] row = TABLE[TABLE.length - 1];
            for (Object[] r : TABLE) { t -= (Integer) r[3]; if (t < 0) { row = r; break; } }
            int min = (Integer) row[1], max = (Integer) row[2];
            int count = min + (max > min ? g.nextInt(max - min + 1) : 0);
            int slot = g.nextInt(27);
            while (used[slot]) slot = (slot + 1) % 27;
            used[slot] = true;
            if (i > 0) sb.append(',');
            String id = (String) row[0];
            sb.append("{Slot:").append(slot).append("b,count:").append(count);
            if (id.startsWith("hoka:")) {
                String[] p = id.substring(5).split("\\|");
                sb.append(",id:\"minecraft:note_block\",components:{\"minecraft:custom_data\":{hoka_block:\"").append(p[0])
                        .append("\"},\"minecraft:custom_name\":{text:\"").append(p[1]).append("\",italic:0b}}");
            } else sb.append(",id:\"").append(id).append('"');
            sb.append('}');
        }
        return sb.append("]}").toString();
    }
}
