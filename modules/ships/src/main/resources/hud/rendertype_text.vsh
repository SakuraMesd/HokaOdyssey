#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
// HUD пилота корабля: координаты внутри элемента (−1..1), вид элемента, значение (0..1), курс камеры (радианы)
out vec2 hkUV;
flat out int hkKind;
flat out float hkVal;
flat out float hkHead;
flat out float hkPitch;                                                   // высота взгляда камеры над горизонтом, рад
flat out float hkP11;                                                     // масштаб проекции по вертикали

// Ванильный шейдер + HUD пилота корабля (модуль ships, Hoka Odyssey).
// Глифы цвета #FD g b / #FE g b — не текст, а элементы HUD; text display пилота, шейдер ставит их на экран.
// g: 0 — кольцо носа (центр), 1 — точка пунктира (b — доля пути от прицела к центру), 2 — прицел мыши,
//    3 — круг-компас, 4 — буква стороны света (b: 0 С, 1 В, 2 Ю, 3 З), 5 — полоска скорости (b — заполнение),
//    6 — полоска высоты (b — заполнение), 7 — цифра/знак (b = группа·16 + (длина−1)·4 + место; группы: 0 скорость,
//    1 высота, 2 наклон), 8 — подпись (b: 0 скорость, 1 высота, 2 наклон).
// #FD — режим «камера — гаст»: прицел мыши по курсу от точки этого текста (сервер), по вертикали — уровень камеры
// плюс «недоворот» носа (высота точки текста). Размеры — доли половины высоты экрана.
const float HK_R = 0.6;                                                   // радиус круга ограничения прицела

vec2 hkProject(vec3 dir) {
    vec4 c = ProjMat * (ModelViewMat * vec4(dir, 0.0));
    vec2 p = c.w > 0.001 ? c.xy / c.w : c.xy * 10000.0;                  // позади камеры — к краю в ту же сторону
    float m = max(abs(p.x), abs(p.y));
    if (m > 0.92) p *= 0.92 / m;
    return p;
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    hkUV = vec2(0.0);
    hkKind = -1;
    hkVal = 0.0;
    hkHead = 0.0;
    hkPitch = 0.0;
    hkP11 = 1.0;

    ivec3 c = ivec3(round(Color.rgb * 255.0));
    if ((c.r == 254 || c.r == 253) && c.g <= 8) {
        float asp = ProjMat[1][1] / ProjMat[0][0];                        // ширина / высота экрана
        mat3 r = mat3(ModelViewMat);
        vec3 fwd = -vec3(r[0][2], r[1][2], r[2][2]);                      // куда смотрит камера (мир)
        vec2 fh = normalize(fwd.xz + vec2(1e-6));
        float head = atan(fh.x, -fh.y);                                   // курс: 0 — север (−z), +90° — восток (+x)
        int k = c.g;
        float t = float(c.b) / 255.0;
        int id = gl_VertexID % 4;                                         // углы глифа: ЛВ, ЛН, ПН, ПВ
        vec2 corner = id == 0 ? vec2(-1.0, 1.0) : (id == 1 ? vec2(-1.0, -1.0) : (id == 2 ? vec2(1.0, -1.0) : vec2(1.0, 1.0)));
        float alpha = 1.0;

        if (k <= 2) {                                                     // прицел, точки, кольцо носа
            vec2 aim;
            if (c.r == 253) {
                float ownEl = length(Position) > 1e-4 ? asin(clamp(normalize(Position).y, -1.0, 1.0)) : 0.0;
                float el = clamp(asin(clamp(fwd.y, -1.0, 1.0)) + ownEl, -1.553, 1.553);
                vec2 hz = length(Position.xz) > 1e-4 ? normalize(Position.xz) : fh;
                aim = hkProject(vec3(hz.x * cos(el), sin(el), hz.y * cos(el)));
                float L = length(aim * vec2(asp, 1.0));
                if (L > HK_R) aim *= HK_R / L;                            // прицел — не дальше круга
            } else {
                vec4 cl = gl_Position;
                aim = cl.w > 0.001 ? cl.xy / cl.w : cl.xy * 10000.0;
                float m = max(abs(aim.x), abs(aim.y));
                if (m > 0.92) aim *= 0.92 / m;
            }
            vec2 p = mix(aim, vec2(0.0), t);
            float s = k == 1 ? 0.010 : (k == 0 ? 0.048 : 0.034);
            gl_Position = vec4(p + corner * vec2(s / asp, s), -0.999, 1.0);
            if (k == 1 && c.b != 0) alpha = smoothstep(0.03, 0.09, length(aim * vec2(asp, 1.0)));  // сошлись — пунктир гаснет
        } else {
            vec2 ph, hs;                                                  // центр и половина размера (доли высоты)
            float side = HK_R + 0.17;
            if (k == 3) { ph = vec2(0.0); hs = vec2(HK_R * 1.15); }
            else if (k == 4) {
                float a = radians(90.0 * float(c.b)) - head;              // сторона света относительно носа
                ph = vec2(sin(a), cos(a)) * (HK_R + 0.075);
                hs = vec2(0.028);
            }
            else if (k == 5 || k == 6) { ph = vec2(k == 5 ? -side : side, 0.0); hs = vec2(0.011, 0.34); }
            else if (k == 7) {                                            // число по центру своей группы
                int grp = c.b / 16, rem = c.b % 16, n = rem / 4 + 1, i = rem % 4;
                float ox = grp == 0 ? -side : (grp == 1 ? side : 0.0);
                float oy = grp == 2 ? -0.47 : -0.43;
                ph = vec2(ox + (float(i) - float(n - 1) * 0.5) * 0.032, oy);
                hs = vec2(0.016, 0.024);
            }
            else if (c.b == 2) { ph = vec2(0.0, -0.395); hs = vec2(0.056, 0.014); }
            else { ph = vec2(c.b == 0 ? -side : side, 0.405); hs = vec2(0.072, 0.018); }
            gl_Position = vec4((ph + corner * hs) / vec2(asp, 1.0), -0.999, 1.0);
        }
        hkKind = k;
        hkVal = t;
        hkUV = corner;
        hkHead = head;
        hkPitch = asin(clamp(fwd.y, -1.0, 1.0));
        hkP11 = ProjMat[1][1];
        vertexColor = vec4(1.0, 1.0, 1.0, alpha);
        sphericalVertexDistance = 0.0;                                    // без тумана
        cylindricalVertexDistance = 0.0;
    }
}
