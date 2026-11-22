#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out float hkDash;

// Ванильный шейдер + мгновенный прицел пилота корабля (модуль ships, Hoka Odyssey).
// Пилоту (только ему) надет «шлем» hoka_ships:aim: текстура 64×32 с метками (254, 9, b, альфа 254) на гранях головы.
// Передняя грань шлема смотрит туда же, куда мышь (голову игрока клиент поворачивает сам, без сервера):
// b = 1 — передняя грань → прицел на экране по её нормали; b = 2 — задняя → пунктир от прицела к центру экрана;
// b = 0 — прочие грани → убрать. Угол грани — по номеру вершины (порядок ModelPart: ПВ, ЛВ, ЛН, ПН).
void hkColor(vec4 c) {
#ifdef PER_FACE_LIGHTING
    vertexPerFaceColorBack = c;
    vertexPerFaceColorFront = c;
#else
    vertexColor = c;
#endif
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif
#ifndef EMISSIVE
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
#endif
    overlayColor = texelFetch(Sampler1, UV1, 0);

    texCoord0 = UV0;
#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif

    hkDash = -1.0;
    ivec2 ts = textureSize(Sampler0, 0);
    if (ts.x == 64 && ts.y == 32) {
        int k = gl_VertexID % 4;
        vec2 inward = k == 0 ? vec2(-1.0, 1.0) : (k == 1 ? vec2(1.0, 1.0) : (k == 2 ? vec2(1.0, -1.0) : vec2(-1.0, -1.0)));
        ivec2 tc = clamp(ivec2(floor(UV0 * vec2(ts) + inward * 0.5)), ivec2(0), ts - 1);
        ivec4 mk = ivec4(round(texelFetch(Sampler0, tc, 0) * 255.0));
        if (mk.r == 254 && mk.g == 9 && mk.a == 254) {
            vec2 corner = k == 0 ? vec2(1.0, 1.0) : (k == 1 ? vec2(-1.0, 1.0) : (k == 2 ? vec2(-1.0, -1.0) : vec2(1.0, -1.0)));
            float asp = ProjMat[1][1] / ProjMat[0][0];                      // ширина / высота экрана
            lightMapColor = vec4(1.0);
            overlayColor = vec4(1.0);
            sphericalVertexDistance = 0.0;
            cylindricalVertexDistance = 0.0;
            if (mk.b == 1 || mk.b == 2) {
                vec3 dir = mk.b == 1 ? Normal : -Normal;                    // куда смотрит голова (мышь)
                vec4 c = ProjMat * (ModelViewMat * vec4(dir, 0.0));         // направление → точка на экране (без параллакса)
                vec2 aim = c.w > 0.0001 ? c.xy / c.w : c.xy * 10000.0;     // позади камеры — к краю в ту же сторону
                float m = max(abs(aim.x), abs(aim.y));
                if (m > 0.92) aim *= 0.92 / m;
                if (mk.b == 1) {                                            // прицел мыши
                    float s = 0.032;
                    gl_Position = vec4(aim + corner * vec2(s / asp, s), -0.999, 1.0);
                    texCoord0 = (vec2(32.0, 16.0) + vec2(corner.x * 0.5 + 0.5, 0.5 - corner.y * 0.5) * 16.0) / vec2(ts);
                    hkColor(vec4(1.0));
                } else {                                                    // пунктир от прицела к центру (носу)
                    vec2 a = aim * vec2(asp, 1.0);
                    float L = length(a);
                    vec2 dn = L > 1e-5 ? -a / L : vec2(1.0, 0.0);
                    vec2 n = vec2(-dn.y, dn.x);
                    float dFrom = 0.045, dTo = max(dFrom, L - 0.06);           // не залезать на прицел и кольцо носа
                    vec2 pt = a + dn * (corner.x > 0.0 ? dTo : dFrom) + n * corner.y * 0.004;
                    gl_Position = vec4(pt / vec2(asp, 1.0), -0.999, 1.0);
                    hkDash = (corner.x > 0.0 ? dTo - dFrom : 0.0) / 0.028;    // период пунктира — 2.8% высоты экрана
                    texCoord0 = vec2(48.5, 16.5) / vec2(ts);                // белый пиксель
                    hkColor(vec4(0.49, 0.95, 1.0, 1.0));
                }
            } else {
                gl_Position = vec4(-10.0, -10.0, -10.0, 1.0);               // прочие грани шлема — убрать
            }
        }
    }
}
