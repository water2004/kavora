# Celestial-disk transmittance fast path

This branch adds a conservative fast path to `airTransmittanceToLight`.
For small celestial emitters (`angularRadius <= 0.01 rad)), sufficiently elevated
light directions (`cosine >= 0.45`), and a disk wholly above the local horizon,
the four-node Gauss-Legendre disk integral is replaced by one central LUT
transmittance lookup. The original finite-disk quadrature remains active near
the horizon, for partially occulted disks, and for larger emitters.

The change does not replace path tracing, cloud transport, air extinction,
phase functions, or the finite-disk treatment where angular variation is
important. It only approximates the smooth angular average of the air
transmittance LUT in the bounded regime.

## CPU validation

Run:

```sh
python3 -m unittest kavora-runtime/tests/rtx/unit/test_air_disk_approximation.py -v
```

The test compares the candidate central-ray value against the existing
four-point disk quadrature. The LUT is replaced by an independently integrated
spherical atmosphere with Rayleigh and Mie exponential density profiles. It
covers six altitudes (0–40 km), 81 light cosines, and solar/lunar angular-radius
fixtures (0.00465, 0.009 and 0.01 rad). It also asserts that horizon-adjacent,
partially visible, and larger-emitter cases do not enter the fast path.

The measured maximum error in the 1,458 fast-path cases uses a spherical atmosphere with independently normalized Rayleigh (8 km) and Mie (1.2 km) exponential profiles:

- Maximum absolute transmittance error: `2.238397e-05`
- Maximum relative transmittance error: `5.963259e-05` (0.0060%)

The numerical test is a model-based CPU bound, not a GPU shader execution or
full Minecraft image acceptance test. A CPU-rendered transmittance A/B preview
is generated separately for visual inspection. Native Slang compilation,
Vulkan readback, RTX frame timing, and full path-traced cloud/fog captures
remain required before claiming production acceptance.

## Expected work reduction

For every eligible active light sample, the transmittance texture lookups
inside the disk integral drop from four to one (75% fewer lookups for that
subroutine). The number of affected samples depends on sun elevation, emitter
configuration, and the actual distribution of air integration samples. This
does not imply a 75% reduction in total atmosphere cost or frame time.

## Exact per-ray phase hoisting

The atmosphere integrator also computes the Rayleigh and Mie phase terms once
per ray and reuses them for all air cells. For a straight ray, the dot product
between its direction and each directional light is invariant along the ray;
the phase functions therefore do not depend on the sample position. The
previous implementation evaluated both phase functions, including the Mie
`pow(..., 1.5)`, again for every air cell. The new `AirRayPhase` value is
stored in the transient `AtmosphereRay` and reused even when the same ray
is integrated as multiple clipped ranges. It does not alter shared
constant-buffer layouts or persistent state. The original four-argument
`sampleAirMedium` entry point remains as a compatibility wrapper.

This is an exact algebraic hoist: it changes neither the phase-function
approximation nor the air integration grid. It removes repeated per-cell phase
math; it does not reduce the number of air cells or the transmittance/scattering
LUT samples.

## Exact cloud light-grid reuse

The cloud light-cache query now has an internal `cloudCachedLightDepthInGrid`
entry point. `cloudLowLightCascadeDepth` already constructs the light-space
grid to determine its cell footprint; it now passes that same grid to the
prefix lookup instead of reconstructing the light basis, normalization and
bounds for each optical-depth interval. The original public helper remains
and constructs a grid for standalone callers.

A regression entry point, `reusedGrid`, compares the reused-grid lookup with
the compatibility wrapper on the same input. The corresponding
`test_cloud_lighting.py` case checks exact equality of the result and both
validity flags. This optimization changes no sampling, interpolation, cache
domain, or optical-depth arithmetic.

The SlangPy/Vulkan contract test could not be executed in this container
because SlangPy and a Vulkan device/ICD are unavailable. The source-level
contract has been added for the repository's supported shader-test
environment; native validation remains pending.

The near-cascade grid is also shared between cascade containment and the
subsequent near-cache query. When a far cascade is blended, each cascade grid
is constructed once and passed through the depth query. The contract shader
compares both the reused prefix result and containment decision against the
original wrappers.
