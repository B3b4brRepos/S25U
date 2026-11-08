#version 150
out vec4 fragColor;

// Liquid Glass: blurred backdrop + lens refraction + chromatic aberration + specular bevel
uniform mat4 u_Transform;
uniform vec2 u_ScreenSize;

uniform vec4 u_Bounds;       // x y width height (logical coords)
uniform float u_Radius;

uniform sampler2D u_Soft;    // light blur background
uniform sampler2D u_Frost;   // heavy blur background
uniform float u_FrostMix;    // 0 = clear, 1 = frosted

uniform vec4 u_Tint;         // rgb = tint color, a = tint strength
uniform float u_MaxLuma;     // brightness cap for text readability
uniform float u_Saturation;

uniform float u_Refraction;  // edge refraction offset (logical px)
uniform float u_Bevel;       // lens bevel width (logical px)
uniform float u_Dispersion;  // chromatic aberration strength
uniform float u_Highlight;   // specular highlight strength
uniform float u_Opacity;

in vec2 v_Position;
in vec2 v_Screen;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

vec3 sampleBg(sampler2D soft, sampler2D frost, float mix_, vec2 screen, vec2 offset) {
    vec2 uv = (screen + offset) / u_ScreenSize;
    uv.y = 1.0 - uv.y;
    uv = clamp(uv, 0.001, 0.999);
    vec3 s = texture(soft,  uv).rgb;
    vec3 f = texture(frost, uv).rgb;
    return mix(s, f, mix_);
}

void main() {
    vec2 center = u_Bounds.xy + u_Bounds.zw * 0.5;
    vec2 half_  = u_Bounds.zw * 0.5;
    vec2 local  = v_Position - center;

    float dist = sdRoundBox(local, half_, u_Radius);
    float aa   = fwidth(dist);

    float inside = 1.0 - smoothstep(-aa, 0.0, dist);
    if (inside < 0.001) discard;

    // Lens normal from SDF gradient — approximate
    vec2 eps  = vec2(0.5, 0.0);
    vec2 norm = normalize(vec2(
        sdRoundBox(local + eps.xy, half_, u_Radius) - sdRoundBox(local - eps.xy, half_, u_Radius),
        sdRoundBox(local + eps.yx, half_, u_Radius) - sdRoundBox(local - eps.yx, half_, u_Radius)
    ));

    // Edge falloff for refraction — only near border
    float edgeDist  = max(0.0, -dist);                   // 0 at edge, positive inside
    float bevelMask = 1.0 - smoothstep(0.0, u_Bevel, edgeDist);

    vec2 refractOffset = norm * u_Refraction * bevelMask;

    // Chromatic aberration: sample R/G/B at slightly different offsets
    float r = sampleBg(u_Soft, u_Frost, u_FrostMix, v_Screen, refractOffset * (1.0 + u_Dispersion)).r;
    float g = sampleBg(u_Soft, u_Frost, u_FrostMix, v_Screen, refractOffset                       ).g;
    float b = sampleBg(u_Soft, u_Frost, u_FrostMix, v_Screen, refractOffset * (1.0 - u_Dispersion)).b;
    vec3 bg = vec3(r, g, b);

    // Saturation
    float luma = dot(bg, vec3(0.2126, 0.7152, 0.0722));
    bg = mix(vec3(luma), bg, u_Saturation);

    // Brightness cap
    float peak = max(bg.r, max(bg.g, bg.b));
    if (peak > u_MaxLuma) bg *= u_MaxLuma / peak;

    // Tint
    bg = mix(bg, u_Tint.rgb, u_Tint.a);

    // Specular bevel highlight — top-left bright streak
    float specAngle = dot(norm, normalize(vec2(-0.7, -0.7)));
    float specMask  = smoothstep(0.0, 1.0, specAngle) * bevelMask;
    bg += vec3(specMask * u_Highlight);

    fragColor = vec4(bg, inside * u_Opacity);
}
