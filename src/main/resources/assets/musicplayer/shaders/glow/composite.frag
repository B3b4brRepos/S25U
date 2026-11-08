#version 150
out vec4 fragColor;

// Deep Glow composite: exposure mapping + white-hot core, Screen-blend style
uniform sampler2D u_Glow;
uniform sampler2D u_Original;  // entity alpha for attenuation on player
uniform float u_Intensity;
uniform float u_Whiteness;

in vec2 uv;

void main() {
    vec3 g = texture(u_Glow, uv).rgb * u_Intensity;

    // Core whitening: brighter pixels whiter
    float peak = max(g.r, max(g.g, g.b));
    g = mix(g, vec3(peak), clamp((peak - 1.0) * 0.45, 0.0, 1.0) * u_Whiteness);

    // Exponential tone map — never hard-clips
    vec3 c = 1.0 - exp(-g);

    // Attenuate behind entity so glow doesn't paint over player
    float entity = texture(u_Original, uv).a;
    c *= 1.0 - 0.95 * entity;

    fragColor = vec4(c, 1.0);
}
