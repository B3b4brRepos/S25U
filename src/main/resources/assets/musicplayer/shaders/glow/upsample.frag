#version 150
out vec4 fragColor;

// 3x3 tent upsample — accumulates each mip level for Deep Glow falloff
uniform sampler2D u_Low;
uniform sampler2D u_High;
uniform vec2 u_TexelSize;
uniform float u_Weight;

in vec2 uv;

void main() {
    vec2 t = u_TexelSize;
    vec3 up = texture(u_Low, uv).rgb * 4.0;
    up += (texture(u_Low, uv + vec2( t.x, 0.0)).rgb
         + texture(u_Low, uv + vec2(-t.x, 0.0)).rgb
         + texture(u_Low, uv + vec2(0.0,  t.y)).rgb
         + texture(u_Low, uv + vec2(0.0, -t.y)).rgb) * 2.0;
    up += texture(u_Low, uv + t).rgb
        + texture(u_Low, uv - t).rgb
        + texture(u_Low, uv + vec2( t.x, -t.y)).rgb
        + texture(u_Low, uv + vec2(-t.x,  t.y)).rgb;
    up /= 16.0;
    fragColor = vec4(texture(u_High, uv).rgb + up * u_Weight, 1.0);
}
