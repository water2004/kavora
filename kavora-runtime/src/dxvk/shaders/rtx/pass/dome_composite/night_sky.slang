/* SPDX-License-Identifier: MIT */
module dome_night_sky;

import "rtx/utility/lat_long_projection.slang";

float3 nightSkyDirection(float4 rotation, float4 intensity, float3 direction)
{
  // The host's celestial rotation is Rx(tilt) Ry(-pi/2) Rx(starAngle).
  float3 view = intensity.w > 0 ? direction.xzy : direction;
  const float3 tilt = float3(view.x, rotation.z * view.y + rotation.w * view.z,
    -rotation.w * view.y + rotation.z * view.z);
  const float3 orbital = float3(tilt.z, tilt.y, -tilt.x);
  return float3(orbital.x, rotation.x * orbital.y + rotation.y * orbital.z,
    -rotation.y * orbital.y + rotation.x * orbital.z);
}

float3 galaxyDirection(float3 local)
{
  const float3 galacticPole = normalize(float3(-0.86f, -0.29f, -0.41f));
  const float3 galacticSide = normalize(cross(float3(0.44f, -0.83f, -0.34f), galacticPole));
  const float3 galacticCenter = cross(galacticPole, galacticSide);
  return float3(dot(local, galacticSide), dot(local, galacticCenter), dot(local, galacticPole));
}

float2 nightMapGradient(float2 origin, float2 neighbor)
{
  float2 difference = neighbor - origin;
  difference.x -= round(difference.x);
  return difference;
}

public float3 nightSkyRadiance(float4 rotation, float4 intensity, float4 meteorHead[2], float4 meteorTail[2],
  Sampler2D<float> starMap, Sampler2D<float3> galaxyMap, float3 direction, float3 directionX, float3 directionY)
{
  if (intensity.z <= 0) return 0;
  const float3 local = nightSkyDirection(rotation, intensity, direction);
  const float3 localX = nightSkyDirection(rotation, intensity, directionX);
  const float3 localY = nightSkyDirection(rotation, intensity, directionY);
  const float2 starUV = cartesianDirectionToLatLongSphere(local);
  const float stars = starMap.SampleGrad(starUV,
    nightMapGradient(starUV, cartesianDirectionToLatLongSphere(localX)),
    nightMapGradient(starUV, cartesianDirectionToLatLongSphere(localY)));
  const float2 galaxyUV = cartesianDirectionToLatLongSphere(galaxyDirection(local));
  const float3 galaxy = galaxyMap.SampleGrad(galaxyUV,
    nightMapGradient(galaxyUV, cartesianDirectionToLatLongSphere(galaxyDirection(localX))),
    nightMapGradient(galaxyUV, cartesianDirectionToLatLongSphere(galaxyDirection(localY))));
  const float footprint = max(length(directionX - direction), length(directionY - direction));
  const float3 view = intensity.w > 0 ? direction.xzy : direction;
  float3 radiance = stars * intensity.x * float3(0.94f, 0.97f, 1.0f)
    + galaxy * intensity.y * 0.08f;
  for (uint i = 0; i < 2; i++)
  {
    if (meteorHead[i].w <= 0) continue;
    const float3 head = meteorHead[i].xyz;
    const float3 tail = meteorTail[i].xyz;
    const float3 segment = head - tail;
    const float along = clamp(dot(view - tail, segment) / max(dot(segment, segment), 1e-10f), 0.0f, 1.0f);
    const float3 nearest = normalize(tail + along * segment);
    const float angularDistance = length(view - nearest);
    const float width = max(0.001f, footprint * 0.6f);
    const float profile = exp(-angularDistance * angularDistance / (2.0f * width * width));
    radiance += meteorHead[i].w * profile * (0.1f + 0.9f * along * along)
      * float3(0.75f, 0.88f, 1.0f);
  }
  return radiance * intensity.z;
}
