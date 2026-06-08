#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// 为了兼容 Iris，Y/U/V 绑定到原版风格的 sampler 名称。
// SamplerY/SamplerU/SamplerV 这类自定义名称在原版/Sodium 下可用，但 Iris shaderpack 程序重映射
// 可能会按 TEXTURED/entity 系列 sampler 名称校验此 pipeline；如果当前程序/绑定表里没有
// Sampler1/Sampler2，就会崩溃。
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

    // BT.709 limited-range YUV420P (I420) -> 线性 RGB 近似转换。
    float yy = 1.16438356 * (y - 0.0625);
    float uu = u - 0.5;
    float vv = v - 0.5;
    vec3 rgb = vec3(
        yy + 1.79274107 * vv,
        yy - 0.21324861 * uu - 0.53290933 * vv,
        yy + 2.11240179 * uu
    );
    vec4 color = vec4(clamp(rgb, 0.0, 1.0), 1.0);

    // 把视频视为自发光/全亮表面。Iris shaderpack 兼容模式下，我们会刻意旁路此 pipeline 的
    // shaderpack 替换程序；如果最终颜色继续混入原版 entity 的光照/逐面 varying，当这些 varying
    // 不再匹配 Iris 当前 pass 布局时，诊断 YUV 色条可能被污染成红/黑色。
    color *= ColorModulator;

    // 保持视频表面不透明，并与 shaderpack 雾效/延迟透明 pass 解耦。如果在 Iris custom-geometry
    // 捕获路径下对这里应用雾效，四边形可能变成完全透明的深度遮挡物：深度/层状态被写入，
    // 但颜色目标没有任何可见贡献。
    fragColor = color;
}