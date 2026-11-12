// ACES operator by Stephen Hill, MJP, David Neubelt
// The file is received from: https://github.com/TheRealMJP/BakingLab/blob/master/BakingLab/ACES.hlsl
// SWIPAT filed under: https://nvbugspro.nvidia.com/bug/5182738

//=================================================================================================
//
//  Baking Lab
//  by MJP and David Neubelt
//  http://mynameismjp.wordpress.com/
//
//  All code licensed under the MIT license
//
//=================================================================================================

// The code in this file was originally written by Stephen Hill (@self_shadow), who deserves all
// credit for coming up with this fit and implementing it. Buy him a beer next time you see him. :)

// sRGB => XYZ => D65_2_D60 => AP1 => RRT_SAT
static const float3x3 ACESInputMat =
{
    {0.59719, 0.35458, 0.04823},
    {0.07600, 0.90834, 0.01566},
    {0.02840, 0.13383, 0.83777}
};

// ODT_SAT => XYZ => D60_2_D65 => sRGB
static const float3x3 ACESOutputMat =
{
    { 1.60475, -0.53108, -0.07367},
    {-0.10208,  1.10813, -0.00605},
    {-0.00327, -0.07276,  1.07602}
};

static const float3 kAcesFitNumerator = float3(1.0f, 0.0245786f, -0.000090537f);
static const float3 kAcesFitDenominator = float3(0.983729f, 0.4329510f, 0.238081f);

float3 RRTAndODTFit(float3 v)
{
    float3 a = v * (v + kAcesFitNumerator.y) + kAcesFitNumerator.z;
    float3 b = v * (kAcesFitDenominator.x * v + kAcesFitDenominator.y) + kAcesFitDenominator.z;
    return a / b;
}

float3 ACESFitted(float3 color)
{
    color = mul(ACESInputMat, color);

    // Apply RRT and ODT
    color = RRTAndODTFit(color);

    color = mul(ACESOutputMat, color);

    // Clamp to [0, 1]
    color = saturate(color);

    return color;
}
