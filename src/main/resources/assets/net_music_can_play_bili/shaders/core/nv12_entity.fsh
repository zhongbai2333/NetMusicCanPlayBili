#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// NV12: Sampler0=Y plane, Sampler1=UV plane（临时 RGBA8 承载，读取 .rg），Sampler2=占位。
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif

#ifndef EMISSIVE
in vec4 lightMapColor;
#endif

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    float y = texture(Sampler0, texCoord0).r;
    vec2 uv = texture(Sampler1, texCoord0).rg;
    float u = uv.r;
    float v = uv.g;

#ifdef BILI_YUV_DEBUG_CONSTANT
    fragColor = vec4(0.0, 1.0, 1.0, 1.0);
    return;
#endif

#ifdef BILI_YUV_DEBUG_PLANES
    fragColor = vec4(y, u, v, 1.0);
    return;
#endif

#ifdef BILI_YUV_DEBUG_Y_ONLY
    fragColor = vec4(y, y, y, 1.0);
    return;
#endif

#ifdef BILI_YUV_DEBUG_U_ONLY
    fragColor = vec4(u, u, u, 1.0);
    return;
#endif

#ifdef BILI_YUV_DEBUG_V_ONLY
    fragColor = vec4(v, v, v, 1.0);
    return;
#endif

    float yy = 1.16438356 * (y - 0.0625);
    float uu = u - 0.5;
    float vv = v - 0.5;
    vec3 rgb = vec3(
        yy + 1.79274107 * vv,
        yy - 0.21324861 * uu - 0.53290933 * vv,
        yy + 2.11240179 * uu
    );
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), 1.0);
    color *= ColorModulator;
    fragColor = color;
}