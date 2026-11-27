#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Hoka Odyssey LOD. Position: relative to the tile corner (ModelOffset = corner - camera).
// Color: tint * face shade * blend / 2. UV0: sprite origin in the block atlas. UV2: (sprite width + face * 1024, sprite height).
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler0;

out vec4 vertexColor;
out vec3 relPos;
out vec3 localPos;
flat out vec2 spriteMin;
flat out vec2 spriteSize;
flat out int face;

void main() {
    relPos = Position + ModelOffset;
    localPos = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(relPos, 1.0);
    vertexColor = vec4(Color.rgb * 2.0, Color.a) * ColorModulator;
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    spriteMin = UV0;
    spriteSize = vec2(float(UV2.x & 1023), float(UV2.y)) / atlas;
    face = UV2.x >> 10;
}
