#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

// NV12：Sampler0=Y 平面，Sampler1=UV 平面（临时 RGBA8 承载，读取 .rg），Sampler2=占位。
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) in vec4 lightMapColor;
#endif

layout(location = 6) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

void main() {
    float y = texture(Sampler0, texCoord0).r;
    vec2 uv = texture(Sampler1, texCoord0).rg;
    float u = uv.r;
    float v = uv.g;

    vec4 color;
#ifdef BILI_YUV_DEBUG_CONSTANT
    color = vec4(0.0, 1.0, 1.0, 1.0);

#elif defined(BILI_YUV_DEBUG_PLANES)
    color = vec4(y, u, v, 1.0);

#elif defined(BILI_YUV_DEBUG_Y_ONLY)
    color = vec4(y, y, y, 1.0);

#elif defined(BILI_YUV_DEBUG_U_ONLY)
    color = vec4(u, u, u, 1.0);

#elif defined(BILI_YUV_DEBUG_V_ONLY)
    color = vec4(v, v, v, 1.0);

#else
    float yy = 1.16438356 * (y - 0.0625);
    float uu = u - 0.5;
    float vv = v - 0.5;
    vec3 rgb = vec3(
        yy + 1.79274107 * vv,
        yy - 0.21324861 * uu - 0.53290933 * vv,
        yy + 2.11240179 * uu
    );
    color = vec4(clamp(rgb, 0.0, 1.0), 1.0);
#endif
#ifdef PER_FACE_LIGHTING
    color *= (gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack) * ColorModulator;
#else
    color *= vertexColor * ColorModulator;
#endif
#ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
#else
#ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
#endif
    fragColor = color;
#endif
}