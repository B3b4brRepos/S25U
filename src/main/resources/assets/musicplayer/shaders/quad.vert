#version 150
in vec2 a_Pos;
in vec2 a_UV;
out vec2 uv;
void main() {
    gl_Position = vec4(a_Pos, 0.0, 1.0);
    uv = a_UV;
}
