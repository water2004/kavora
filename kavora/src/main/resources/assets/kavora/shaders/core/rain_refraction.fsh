// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D RemixDepth;
layout(std140) uniform WeatherMatrices { mat4 ModelViewMat; mat4 ProjMat; vec2 InverseViewportSize; };

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec3 viewPosition;

layout(location = 0) out vec4 fragColor;

vec2 projectedUv(vec3 direction) {
    vec4 clip = ProjMat * vec4(direction, 0.0);
    float safeW = clip.w < 0.0
        ? -max(abs(clip.w), 0.0001)
        : max(abs(clip.w), 0.0001);
    return clip.xy / safeW * 0.5 + 0.5;
}

void main() {
    // Derivatives require all four lanes, including lanes outside the drop mask.
    vec3 positionDx = dFdx(viewPosition);
    vec3 positionDy = dFdy(viewPosition);
    vec2 uvDx = dFdx(texCoord);
    vec2 uvDy = dFdy(texCoord);
    float determinant = uvDx.x * uvDy.y - uvDy.x * uvDx.y;
    float rainAlpha = texture(Sampler0, texCoord).a;
    float coverage = rainAlpha * vertexColor.a;
    if (coverage <= 0.01) discard;

    vec2 framebufferSize = vec2(textureSize(Sampler1, 0));
    vec2 screenUv = gl_FragCoord.xy * InverseViewportSize;
    ivec2 depthSize = textureSize(RemixDepth, 0);
    ivec2 depthPixel = clamp(ivec2(screenUv * vec2(depthSize)), ivec2(0), depthSize - 1);
    if (gl_FragCoord.z > texelFetch(RemixDepth, depthPixel, 0).r) discard;

    vec3 u = (positionDx * uvDy.y - positionDy * uvDx.y) * sign(determinant);
    vec3 v = (positionDy * uvDx.x - positionDx * uvDy.x) * sign(determinant);
    vec3 sheet = cross(u, v);
    if (dot(sheet, sheet) == 0.0) discard;
    vec3 tangent = normalize(u);
    vec3 bitangent = normalize(v);
    vec3 incident = normalize(viewPosition);
    vec3 sheetNormal = normalize(sheet);
    sheetNormal = faceforward(sheetNormal, incident, sheetNormal);

    float profileX = clamp(texCoord.x, 0.0, 1.0) * 2.0 - 1.0;
    float profileZ = sqrt(max(0.0, 1.0 - profileX * profileX));
    vec2 rainTexel = 1.0 / vec2(textureSize(Sampler0, 0));
    float alphaBefore = textureGrad(Sampler0, texCoord - vec2(0.0, rainTexel.y), uvDx, uvDy).a;
    float alphaAfter = textureGrad(Sampler0, texCoord + vec2(0.0, rainTexel.y), uvDx, uvDy).a;
    float verticalSlope = clamp(
        (alphaAfter - alphaBefore) * 2.0,
        -0.45,
        0.45);

    vec3 lensNormal = normalize(
        tangent * profileX
        + bitangent * verticalSlope
        + sheetNormal * profileZ);
    lensNormal = faceforward(lensNormal, incident, lensNormal);

    const float AIR_TO_WATER_IOR = 1.0 / 1.333;
    vec3 refractedDirection = refract(incident, lensNormal, AIR_TO_WATER_IOR);

    vec2 angularOffset = projectedUv(refractedDirection)
        - projectedUv(incident);
    vec2 offsetPixels = clamp(
        angularOffset * (0.08 / InverseViewportSize),
        vec2(-12.0),
        vec2(12.0));
    offsetPixels *= coverage;
    // Clamp to texel centres so a repeating rain sampler cannot wrap the screen.
    vec2 inset = 0.5 / framebufferSize;
    vec2 refractedUv = clamp(
        screenUv + offsetPixels * InverseViewportSize,
        inset,
        1.0 - inset);
    vec3 sourceColor = textureLod(Sampler1, screenUv, 0.0).rgb;
    vec3 refractedColor = textureLod(Sampler1, refractedUv, 0.0).rgb;

    float normalDotView = clamp(dot(-incident, lensNormal), 0.0, 1.0);
    const float WATER_F0 = 0.02037;
    float fresnel = WATER_F0
        + (1.0 - WATER_F0) * pow(1.0 - normalDotView, 5.0);
    // A screen snapshot can resolve nearby transmission. This drops its contribution at grazing
    // angles, where more light comes from behind the viewer.
    fragColor = vec4(mix(sourceColor, refractedColor, coverage * (1.0 - fresnel)), 1.0);
}
