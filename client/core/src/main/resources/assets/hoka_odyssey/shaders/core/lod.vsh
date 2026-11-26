#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Hoka Odyssey LOD: vertices are relative to the tile corner, ModelOffset = corner - camera.
in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
out vec3 relPos;

void main() {
    relPos = Position + ModelOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(relPos, 1.0);
    vertexColor = Color * ColorModulator;
}
