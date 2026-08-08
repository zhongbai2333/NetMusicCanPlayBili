#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// 为兼容 Iris，Y/U/V 使用原版风格的 Sampler0/1/2 名称。
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
    float u = texture(Sampler1, texCoord0).r;
    float v = texture(Sampler2, texCoord0).r;

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
    // BT.709 limited-range YUV420P(I420) 到 RGB 的近似转换。
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

    // 视频按全亮表面处理，避免光影包 pass 差异污染 YUV 颜色。
#ifdef PER_FACE_LIGHTING
    color *= (gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack) * ColorModulator;
#else
    color *= vertexColor * ColorModulator;
#endif

    // 不套雾效/透明 pass，避免只写深度却无可见颜色。
    fragColor = color;
}