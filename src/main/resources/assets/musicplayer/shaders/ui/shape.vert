#version 150
in vec2 a_Pos;
in vec2 a_UV;
uniform vec2 u_ScreenSize;
uniform mat4 u_Transform;
out vec2 v_Position;
void main() {
    v_Position  = a_Pos;
    vec4 vert   = u_Transform * vec4(a_Pos, 0.0, 1.0);
    vec2 pos    = vert.xy / u_ScreenSize * 2.0 - 1.0;
    gl_Position = vec4(pos.x, -pos.y, vert.z, 1.0);
}
