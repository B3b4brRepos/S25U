#version 150
out vec4 fragColor;

// 13-tap Jimenez downsample — avoids temporal flicker
uniform sampler2D u_Texture;
uniform vec2 u_TexelSize;
uniform float u_Premultiply;

in vec2 uv;

vec3 s(vec2 o) {
    vec4 c = texture(u_Texture, uv + o * u_TexelSize);
    return c.rgb * mix(1.0, c.a, u_Premultiply);
}

void main() {
    vec3 a = s(vec2(-2.0,  2.0)); vec3 b = s(vec2(0.0, 2.0)); vec3 c = s(vec2(2.0,  2.0));
    vec3 d = s(vec2(-2.0,  0.0)); vec3 e = s(vec2(0.0, 0.0)); vec3 f = s(vec2(2.0,  0.0));
    vec3 g = s(vec2(-2.0, -2.0)); vec3 h = s(vec2(0.0,-2.0)); vec3 i = s(vec2(2.0, -2.0));
    vec3 j = s(vec2(-1.0,  1.0)); vec3 k = s(vec2(1.0, 1.0));
    vec3 l = s(vec2(-1.0, -1.0)); vec3 m = s(vec2(1.0,-1.0));

    vec3 color = e * 0.125
        + (a + c + g + i) * 0.03125
        + (b + d + f + h) * 0.0625
        + (j + k + l + m) * 0.125;

    fragColor = vec4(color, 1.0);
}
