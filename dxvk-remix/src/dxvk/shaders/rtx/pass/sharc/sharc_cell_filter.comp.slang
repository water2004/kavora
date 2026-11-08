/*
* Copyright (c) 2026, GBwater. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/

#include "sharc_binding_indices.h"
#include "rtx/pass/sharc/sharc_integrator_utilities.slangh"
#include "rtx/pass/raytrace_args.h"
#include "sharc_cell_filter.slangh"

layout(binding = SHARC_BINDING_CONSTANTS_INPUT)
ConstantBuffer<RaytraceArgs> cb;
layout(binding = SHARC_BINDING_HASH_ENTRIES_INPUT_OUTPUT)
RWStructuredBuffer<uint64_t> SharcHashEntriesBuffer;
layout(binding = SHARC_BINDING_ACCUMULATION_OUTPUT)
RWStructuredBuffer<SharcAccumulationData> SharcAccumulationBuffer;
layout(binding = SHARC_BINDING_RESOLVED_INPUT_OUTPUT)
RWStructuredBuffer<SharcPackedData> SharcResolvedBuffer;
layout(binding = SHARC_BINDING_CELL_GUIDANCE)
StructuredBuffer<uint4> SharcCellGuidance;
layout(binding = SHARC_BINDING_CELL_HISTORY)
StructuredBuffer<uint2> SharcCellHistory;
layout(binding = SHARC_BINDING_CELL_FILTERED)
RWStructuredBuffer<uint2> SharcCellFiltered;

layout(binding = SHARC_BINDING_CELL_WORK)
StructuredBuffer<uint> SharcCellWork;

[shader("compute")]
[numthreads(SHARC_COMPUTE_LINEAR_BLOCK_SIZE, 1, 1)]
void main(uint3 threadId : SV_DispatchThreadID) {
  if (threadId.x >= SharcCellWork[0]) {
    return;
  }
  uint index = SharcCellWork[4 + threadId.x];
  uint4 guidance = SharcCellGuidance[index];
  if ((guidance.w & 3u) != SharcCellFilter::kThin) {
    return;
  }
  uint64_t key = SharcHashEntriesBuffer[index];
  if (!SharcCellFilter::eligible(guidance, key)) {
    return;
  }
  SharcVoxelData center = SharcGetVoxelData(SharcResolvedBuffer, index);
  if (center.accumulatedSampleNum <= 0.f) {
    SharcCellFiltered[index] = uint2(0);
    return;
  }
  bool temporal = cb.sharcArgs.cellFilterMode == 2 && cb.sharcArgs.cellFilterFeedback > 0.f;
  uint2 history = SharcCellHistory[index];
  bool historyValid = SharcCellFilter::validHistory(history, guidance);
  // Stale cells keep their lighting until new samples arrive, with a staggered refresh.
  if (temporal && center.staleFrameNum && ((index + cb.frameIdx) & 15u) && historyValid) {
    SharcCellFiltered[index] = history;
    return;
  }
  float3 centerRadiance = center.accumulatedRadiance.radiance;
  float3 normal = SharcCellFilter::unpackNormal(guidance.z);
  float centerWeight = 2.f * SharcCellFilter::confidence(center);
  float3 spatialSum = centerRadiance * centerWeight;
  float spatialWeight = centerWeight;
  float3 historySum = 0.f;
  float historyWeight = 0.f;
  if (temporal && historyValid) {
    historySum = SharcCellFilter::unpackRadiance(history) * centerWeight;
    historyWeight = centerWeight;
  }
  SharcParameters params = Sharc::createSharcParameters(cb.sharcArgs);
  for (uint neighbor = 0; neighbor < 6; neighbor++) {
    int3 offset = SharcCellFilter::neighborOffset(index, cb.frameIdx, neighbor);
    uint64_t neighborKey = SharcCellFilter::offsetKey(key, offset);
    uint neighborIndex, bucketOffset;
    uint baseSlot = HashGridGetBaseSlot(neighborKey, params.hashGridData.capacity);
    if (!HashGridFind(params.hashGridData, neighborKey, baseSlot, HASH_GRID_HASH_MAP_BUCKET_SIZE, neighborIndex, bucketOffset)) {
      continue;
    }
    uint4 neighborGuidance = SharcCellGuidance[neighborIndex];
    if (!SharcCellFilter::eligible(neighborGuidance, neighborKey)) {
      continue;
    }
    SharcVoxelData cell = SharcGetVoxelData(SharcResolvedBuffer, neighborIndex);
    if (cell.accumulatedSampleNum <= 0.f) {
      continue;
    }
    float weight = SharcCellFilter::neighborWeight(normal, SharcCellFilter::unpackNormal(neighborGuidance.z), offset,
      cb.sharcArgs.cellFilterNormalCosine) * SharcCellFilter::confidence(cell);
    spatialSum += cell.accumulatedRadiance.radiance * weight;
    spatialWeight += weight;
    uint2 neighborHistory = temporal ? SharcCellHistory[neighborIndex] : uint2(0);
    if (temporal && SharcCellFilter::validHistory(neighborHistory, neighborGuidance)) {
      float temporalWeight = weight * cb.sharcArgs.cellFilterSpatialStrength;
      historySum += SharcCellFilter::unpackRadiance(neighborHistory) * temporalWeight;
      historyWeight += temporalWeight;
    }
  }
  float3 radiance = spatialWeight > 0.f
    ? lerp(centerRadiance, spatialSum / spatialWeight, cb.sharcArgs.cellFilterSpatialStrength) : centerRadiance;
  if (temporal && historyWeight > 0.f) {
    float3 previousRadiance = historySum / historyWeight;
    float currentLuminance = SharcLuma(radiance);
    float previousLuminance = SharcLuma(previousRadiance);
    // Reject history when the spatially filtered lighting changes, in either direction.
    // Small sampling fluctuations keep the configured smoothing; an old sunlit cell
    // must not keep illuminating foliage after its fresh samples have become dark.
    float relativeChange = abs(currentLuminance - previousLuminance) /
      max(max(currentLuminance, previousLuminance), 1.e-5f);
    float feedback = cb.sharcArgs.cellFilterFeedback * (1.f - smoothstep(0.1f, 0.5f, relativeChange));
    radiance = lerp(radiance, previousRadiance, feedback);
  }
  SharcCellFiltered[index] = SharcCellFilter::packRadiance(radiance);
}
