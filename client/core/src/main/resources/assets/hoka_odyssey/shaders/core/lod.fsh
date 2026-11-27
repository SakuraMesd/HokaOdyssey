#version 330

// x: inner radius (real blocks there), y: outer radius, z/w: fog start/end (spherical); LodFog.rgb: fog color
layout(std140) uniform HokaLod {
    vec4 LodParams;
    vec4 LodFog;
};

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

in vec4 vertexColor;
in vec3 relPos;
in vec3 localPos;
flat in vec2 spriteMin;
flat in vec2 spriteSize;
flat in int face;

out vec4 fragColor;

void main() {
    float hd = length(relPos.xz);
    if (hd < LodParams.x || hd >= LodParams.y) discard;
    // texture repeats per block: coordinates in blocks on the face plane (v grows downwards like the atlas)
    vec2 t;
    if (face <= 1) t = localPos.xz;
    else if (face <= 3) t = vec2(localPos.x, -localPos.y);
    else t = vec2(localPos.z, -localPos.y);
    vec2 dx = dFdx(t) * spriteSize, dy = dFdy(t) * spriteSize;
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    float lod = clamp(log2(max(length(dx * atlas), length(dy * atlas))), 0.0, 4.0);
    vec2 inset = 0.5 * exp2(lod) / atlas;                       // half a texel of the chosen mip: no bleeding from neighbours
    vec2 uv = clamp(spriteMin + fract(t) * spriteSize, spriteMin + inset, spriteMin + spriteSize - inset);
    vec4 tex = textureGrad(Sampler0, uv, dx, dy);
#ifdef HOKA_WATER
    float alpha = tex.a * vertexColor.a;
#else
    if (tex.a < 0.1) discard;
    float alpha = 1.0;
#endif
    vec3 light = texture(Sampler2, vec2(0.5 / 16.0, 15.5 / 16.0)).rgb;   // sky light 15, block light 0 - like the real surface
    vec3 c = tex.rgb * vertexColor.rgb * light;
    float d = length(relPos);
    float f = clamp((d - LodParams.z) / max(1.0, LodParams.w - LodParams.z), 0.0, 1.0);
    f = f * f * (3.0 - 2.0 * f);
    fragColor = vec4(mix(c, LodFog.rgb, f), alpha);
}
