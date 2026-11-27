package ink.hoka.odyssey.client.planets;

import org.joml.Vector3f;

import static ink.hoka.odyssey.client.planets.PlanetsClientModule.fbm;
import static ink.hoka.odyssey.client.planets.PlanetsClientModule.lerp;
import static ink.hoka.odyssey.client.planets.PlanetsClientModule.shade;
import static ink.hoka.odyssey.client.planets.PlanetsClientModule.smooth;
import static ink.hoka.odyssey.client.planets.PlanetsClientModule.surface;
import static ink.hoka.odyssey.client.planets.PlanetsClientModule.withClouds;

/**
 * 0.3 Виды планет (config.yml type): terra — «как Земля»; desert — дюны, каньоны; ice — ледяная корка с трещинами;
 * lava — тёмный базальт и светящиеся реки лавы (не гаснут на ночной стороне); gas — газовый гигант: полосы по широте с
 * завихрениями и бурей (цвета — от color); rock — серая луна с кратерами; star — звезда: грануляция и пятна, светится
 * сама. d — направление от центра (единичное), lit — освещённость (1 — звезда).
 */
final class Kinds {

    private Kinds() {}

    static int paint(String type, int rgb, Vector3f d, int seed, float lit) {
        return 0xFF000000 | switch (type) {
            case "desert" -> desert(d, seed, lit);
            case "ice" -> ice(d, seed, lit);
            case "lava" -> lava(d, seed, lit);
            case "gas" -> gas(d, seed, lit, rgb);
            case "rock" -> rock(d, seed, lit);
            case "star" -> star(d, seed);
            default -> withClouds(surface(d, seed, lit), d, seed, lit) & 0xFFFFFF;
        };
    }

    private static int desert(Vector3f d, int seed, float lit) {
        float e = fbm(d.x * 2.4f, d.y * 2.4f, d.z * 2.4f, seed, 5);
        float dunes = fbm(d.x * 9f + 7, d.y * 3f, d.z * 9f, seed + 3, 3);
        float canyon = Math.abs(fbm(d.x * 3.6f + 50, d.y * 3.6f, d.z * 3.6f, seed + 11, 4));
        int c = lerp(0xC9954F, 0xE8C482, smooth(-0.3f, 0.3f, e + dunes * 0.4f));
        if (canyon < 0.035f) c = lerp(0x7A4A28, c, canyon / 0.035f);             // каньоны — тёмные русла
        if (e > 0.32f) c = lerp(c, 0xA8705A, smooth(0.32f, 0.5f, e));            // красные плато
        if (Math.abs(d.y) > 0.92f) c = lerp(c, 0xF2EEE6, smooth(0.92f, 0.97f, Math.abs(d.y)));   // шапки инея
        int s = shade(c, lit);
        float cl = smooth(0.25f, 0.5f, fbm(d.x * 3f + 90, d.y * 5f, d.z * 3f, seed + 31, 3)) * 0.35f;   // редкая пыльная дымка
        return cl > 0.01f ? lerp(s, shade(0xF0D8A8, 0.3f + 0.7f * lit), cl) : s;
    }

    private static int ice(Vector3f d, int seed, float lit) {
        float e = fbm(d.x * 2.2f, d.y * 2.2f, d.z * 2.2f, seed, 5);
        float crack = Math.abs(fbm(d.x * 6f + 20, d.y * 6f, d.z * 6f, seed + 5, 3));
        int c = lerp(0xA9CBE6, 0xEEF6FB, smooth(-0.35f, 0.35f, e));
        if (crack < 0.03f) c = lerp(0x4F7FB5, c, crack / 0.03f);               // трещины — синие
        if (e < -0.28f) c = lerp(c, 0x2E5A8C, smooth(-0.28f, -0.45f, e));        // разломы с водой под льдом
        return shade(c, 0.2f + 0.8f * lit);
    }

    private static int lava(Vector3f d, int seed, float lit) {
        float e = fbm(d.x * 2.6f, d.y * 2.6f, d.z * 2.6f, seed, 5);
        float river = Math.abs(fbm(d.x * 4.2f + 30, d.y * 4.2f, d.z * 4.2f, seed + 9, 4));
        int rock = shade(lerp(0x1E1614, 0x4A3A34, smooth(-0.3f, 0.4f, e)), lit);
        if (river < 0.05f) {                                                     // лава светится сама
            float k = 1 - river / 0.05f;
            int hot = lerp(0xC8320E, 0xFFC24A, k * k);
            return lerp(rock, hot, smooth(0f, 0.6f, k));
        }
        if (e < -0.3f) return lerp(rock, 0xE0541A, smooth(-0.3f, -0.5f, e) * 0.9f);   // лавовые озёра
        return rock;
    }

    private static int gas(Vector3f d, int seed, float lit, int rgb) {
        float swirl = fbm(d.x * 3f, d.y * 3f, d.z * 3f, seed, 4);
        float lat = d.y + swirl * 0.12f;
        float band = (float) Math.sin(lat * 15 + fbm(d.x * 1.5f, d.y * 6f, d.z * 1.5f, seed + 2, 3) * 2.5f);
        int light = lerp(rgb, 0xFFF4E0, 0.55f), dark = lerp(rgb, 0x3A2A20, 0.45f);
        int c = lerp(dark, light, band * 0.5f + 0.5f);
        // большая буря: овал у экватора
        float sx = d.x - 0.7f, sy = (d.y + 0.25f) * 1.8f, sz = d.z - 0.55f;
        float storm = sx * sx + sy * sy + sz * sz;
        if (storm < 0.06f) c = lerp(0xC0502E, c, smooth(0.0f, 0.06f, storm + swirl * 0.02f));
        return shade(c, 0.1f + 0.9f * lit);
    }

    private static int rock(Vector3f d, int seed, float lit) {
        float e = fbm(d.x * 3f, d.y * 3f, d.z * 3f, seed, 5);
        float cr = fbm(d.x * 11f + 5, d.y * 11f, d.z * 11f, seed + 13, 2);
        int c = lerp(0x5E5E66, 0x9A9AA2, smooth(-0.4f, 0.4f, e));
        if (cr > 0.28f) c = lerp(c, 0x3E3E46, smooth(0.28f, 0.4f, cr));          // кратеры
        return shade(c, lit);
    }

    private static int star(Vector3f d, int seed) {
        float g = fbm(d.x * 14f, d.y * 14f, d.z * 14f, seed, 3);                // грануляция
        float big = fbm(d.x * 3f + 10, d.y * 3f, d.z * 3f, seed + 4, 4);
        int c = lerp(0xFFB43A, 0xFFF2B8, smooth(-0.35f, 0.35f, g + big * 0.6f));
        if (big < -0.38f) c = lerp(c, 0xC0561A, smooth(-0.38f, -0.5f, big));     // пятна
        return c;
    }
}
