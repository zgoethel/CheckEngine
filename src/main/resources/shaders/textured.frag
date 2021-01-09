#version 120

uniform sampler2D tex;

varying vec2 tex_coord;
varying vec4 color;

void main()
{
    vec4 frag_color = texture2D(tex, tex_coord) * color;

    gl_FragColor = frag_color;
}