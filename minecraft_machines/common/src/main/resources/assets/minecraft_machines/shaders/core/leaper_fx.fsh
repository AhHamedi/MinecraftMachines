#version 150
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
in vec2 texCoord;
in vec4 vertexColor;
in float viewDistance;
out vec4 fragColor;
void main() {
    vec4 color = texture(Sampler0, texCoord) * vertexColor * ColorModulator;
    color.a *= 1.0 - smoothstep(FogStart, max(FogStart + 0.001, FogEnd), viewDistance);
    if (color.a < 0.001) discard;
    fragColor = color;
}
