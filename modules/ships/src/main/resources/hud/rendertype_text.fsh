#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 hkUV;
flat in int hkKind;
flat in float hkVal;
flat in float hkHead;
flat in float hkPitch;
flat in float hkP11;

out vec4 fragColor;

// Ванильный шейдер + HUD пилота корабля (см. rendertype_text.vsh). Элементы HUD рисуются математически —
// чёткие и сглаженные при любом разрешении (картинки глифов больше 256 px игра не показывает).
const vec3 HK_GRAY = vec3(0.84, 0.88, 0.92);
const float HK_R = 0.6;                                                   // как в rendertype_text.vsh

// 1 на линии |d| <= w (в тех же единицах), сглаженно на один пиксель
float hkLine(float d, float w, float px) {
    return 1.0 - smoothstep(w, w + px, abs(d));
}

void main() {
    if (hkKind >= 0) {
        vec2 uv = hkUV;
        float rr = length(uv);
        float px = max(fwidth(rr), 1e-5);                                 // размер пикселя в единицах элемента
        vec3 col = HK_GRAY;
        float a = 0.0;
        if (hkKind == 3) {                                                // круг-компас
            float R0 = 1.0 / 1.15;
            a = 0.38 * hkLine(rr - R0, px * 0.6, px);
            float deg = degrees(atan(uv.x, uv.y) + hkHead);               // курс в этой точке круга
            float degPx = degrees(px / max(rr, 1e-3));                    // градусов на пиксель по дуге
            float d90 = abs(fract(deg / 90.0 + 0.5) - 0.5) * 90.0 / degPx;
            float d30 = abs(fract(deg / 30.0 + 0.5) - 0.5) * 30.0 / degPx;
            float d10 = abs(fract(deg / 10.0 + 0.5) - 0.5) * 10.0 / degPx;
            float len = d90 < 1.5 ? 0.085 : (d30 < 1.2 ? 0.055 : 0.03);
            float d = d90 < 1.5 ? d90 : (d30 < 1.2 ? d30 : d10);
            float wpx = d90 < 1.5 ? 1.0 : (d30 < 1.2 ? 0.8 : 0.55);
            if (rr < R0 && rr > R0 - len) a = max(a, (d90 < 1.5 ? 0.6 : 0.42) * (1.0 - smoothstep(wpx, wpx + 1.0, d)));
            // шкала наклона внутри круга: горизонт и линии через 10° (ниже горизонта — пунктиром), по наклону камеры
            float S = HK_R * 1.15;                                        // половина размера круга в долях высоты
            vec2 hp = uv * S;
            float pxh = px * S;
            if (rr < R0 - 0.03) {
                for (int i = -9; i <= 9; i++) {
                    float dd = radians(10.0 * float(i)) - hkPitch;
                    if (abs(dd) > 1.2) continue;
                    float y = tan(dd) * hkP11;
                    if (abs(hp.y - y) > pxh * 3.0) continue;
                    float w = i == 0 ? 0.30 : (abs(i) % 3 == 0 ? 0.15 : 0.08);
                    float ln = hkLine(hp.y - y, pxh * 0.55, pxh) * step(abs(hp.x), w) * step(0.06, abs(hp.x));
                    if (i < 0) ln *= step(0.5, fract(abs(hp.x) * 28.0));
                    a = max(a, (i == 0 ? 0.42 : 0.28) * ln);
                }
            }
            // метка носа сверху снаружи круга
            if (uv.y > R0 + px * 2.0 && uv.y < R0 + 0.06) a = max(a, 0.6 * hkLine(uv.x, px * 0.8, px));
        } else if (hkKind == 0) {                                         // кольцо носа
            a = 0.75 * hkLine(rr - 0.5, px * 0.9, px);
            float ax = abs(uv.x), ay = abs(uv.y);
            float tick = max(hkLine(uv.x, px * 0.9, px) * step(0.62, ay) * step(ay, 0.95),
                             hkLine(uv.y, px * 0.9, px) * step(0.62, ax) * step(ax, 0.95));
            a = max(a, 0.75 * tick);
        } else if (hkKind == 2) {                                         // прицел мыши
            float ax = abs(uv.x), ay = abs(uv.y);
            float arm = max(hkLine(uv.x, px * 0.9, px) * step(0.32, ay) * step(ay, 0.92),
                            hkLine(uv.y, px * 0.9, px) * step(0.32, ax) * step(ax, 0.92));
            float dot = 1.0 - smoothstep(0.09, 0.09 + px, rr);
            a = 0.9 * max(arm, dot);
            col = vec3(1.0);
        } else if (hkKind == 1) {                                         // точка пунктира
            a = 0.5 * (1.0 - smoothstep(0.7, 0.7 + px, rr));
        } else if (hkKind == 5 || hkKind == 6) {                          // полоска скорости / высоты
            float pxx = fwidth(uv.x), pxy = fwidth(uv.y);
            float frame = max(hkLine(abs(uv.x) - 1.0 + pxx, pxx * 0.5, pxx), hkLine(abs(uv.y) - 1.0 + pxy, pxy * 0.5, pxy));
            float top = -1.0 + 2.0 * hkVal;
            float fill = (1.0 - smoothstep(top - pxy, top + pxy, uv.y)) * step(abs(uv.x), 0.62);
            float tk = abs(fract((uv.y + 1.0) * 5.0 + 0.5) - 0.5) / 5.0;    // риски через 10%
            float tick = hkLine(tk, pxy * 0.5, pxy) * step(0.62, abs(uv.x));
            a = max(max(0.45 * frame, 0.5 * fill), 0.35 * tick);
            if (hkKind == 5 && hkVal > 0.715) col = mix(HK_GRAY, vec3(1.0, 0.78, 0.45), 0.7);  // форсаж
        } else {                                                          // буквы, цифры, подписи — из картинки
            a = texture(Sampler0, texCoord0).a * 0.72;
        }
        a *= vertexColor.a;
        if (a < 0.01) {
            discard;
        }
        fragColor = vec4(col, a);
        return;
    }
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
