#version 150
out vec4 fragColor;

// 9-tap Gaussian optimized to 5 linear samples
uniform sampler2D u_Texture;
uniform vec2 u_Direction;  // texelSize * direction * spread

in vec2 uv;

void main() {
    vec3 c = texture(u_Texture, uv).rgb * 0.2270270270;
    c += texture(u_Texture, uv + u_Direction * 1.3846153846).rgb * 0.3162162162;
    c += texture(u_Texture, uv - u_Direction * 1.3846153846).rgb * 0.3162162162;
    c += texture(u_Texture, uv + u_Direction * 3.2307692308).rgb * 0.0702702703;
    c += texture(u_Texture, uv - u_Direction * 3.2307692308).rgb * 0.0702702703;
    fragColor = vec4(c, 1.0);
}
