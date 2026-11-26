#version 330

// x: radius of the near circle (real blocks there), y/z: fog start/end (spherical), FogRgb: fog color
layout(std140) uniform HokaLod {
    vec4 LodParams;
    vec4 LodFog;
};

in vec4 vertexColor;
in vec3 relPos;

out vec4 fragColor;

void main() {
    if (length(relPos.xz) < LodParams.x) discard;
    float d = length(relPos);
    float f = clamp((d - LodParams.y) / max(1.0, LodParams.z - LodParams.y), 0.0, 1.0);
    fragColor = vec4(mix(vertexColor.rgb, LodFog.rgb, f * f * (3.0 - 2.0 * f)), 1.0);
}
