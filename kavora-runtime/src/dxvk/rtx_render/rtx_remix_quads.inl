/* SPDX-License-Identifier: MIT */

  bool validQuadSurface(const remixapi_MeshQuadSurface& source) {
    if (!source.vertices || !source.materials || !source.quadCount || !source.materialCount
        || source.quadCount > dxvk::kQuadIndexVertexCount / 4 || source.materialCount > source.quadCount) {
      return false;
    }
    const auto* pVertices = static_cast<const uint32_t*>(source.vertices);
    for (uint32_t vertex = 0; vertex < source.quadCount * 4; vertex++) {
      if (pVertices[vertex * 4 + 3] >= source.materialCount
          || pVertices[vertex * 4 + 3] != pVertices[(vertex & ~3u) * 4 + 3]) {
        return false;
      }
      for (uint32_t axis = 0; axis < 3; axis++) {
        float coordinate;
        memcpy(&coordinate, pVertices + vertex * 4 + axis, sizeof(coordinate));
        if (!std::isfinite(coordinate)) {
          return false;
        }
      }
    }
    for (uint32_t material = 0; material < source.materialCount; material++) {
      const auto& entry = source.materials[material];
      if (entry.axis > 2) {
        return false;
      }
      for (uint32_t component = 0; component < 2; component++) {
        if (!std::isfinite(entry.uvOrigin[component]) || !std::isfinite(entry.uvS[component])
            || !std::isfinite(entry.uvT[component])) {
          return false;
        }
      }
    }
    return true;
  }

  dxvk::RasterGeometry allocateQuadSurfaceGeometry(dxvk::D3D9DeviceEx* device, const remixapi_MeshQuadSurface& source) {
    static_assert(sizeof(remixapi_QuadMaterial) == 40);
    const size_t vertexBytes = size_t(source.quadCount) * 48;
    const size_t indexBytes = size_t(source.quadCount) * sizeof(uint32_t);
    const size_t materialBytes = size_t(source.materialCount) * sizeof(remixapi_QuadMaterial);
    dxvk::DxvkBufferCreateInfo info;
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
      | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
    info.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
      | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
    info.access = VK_ACCESS_TRANSFER_WRITE_BIT;
    info.size = dxvk::align(vertexBytes + indexBytes + materialBytes, dxvk::CACHE_LINE_SIZE);
    auto buffer = device->GetDXVKDevice()->createBuffer(info,
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
      dxvk::DxvkMemoryStats::Category::RTXBuffer, "Remix API quad buffer");
    const dxvk::DxvkBufferSlice vertexSlice(buffer);
    auto* pData = static_cast<uint8_t*>(vertexSlice.mapPtr(0));
    const auto* pSource = static_cast<const uint8_t*>(source.vertices);
    dxvk::AxisAlignedBoundingBox bounds;
    for (uint32_t vertex = 0; vertex < source.quadCount * 4; vertex++) {
      memcpy(pData + vertex * 12, pSource + vertex * 16, 12);
      const auto* pPosition = reinterpret_cast<const float*>(pSource + vertex * 16);
      for (uint32_t axis = 0; axis < 3; axis++) {
        bounds.minPos[axis] = std::min(bounds.minPos[axis], pPosition[axis]);
        bounds.maxPos[axis] = std::max(bounds.maxPos[axis], pPosition[axis]);
      }
      if ((vertex & 3) == 0) {
        memcpy(pData + vertexBytes + (vertex / 4) * sizeof(uint32_t), pSource + vertex * 16 + 12, sizeof(uint32_t));
      }
    }
    memcpy(pData + vertexBytes + indexBytes, source.materials, materialBytes);
    const auto pattern = dxvk::QuadIndexPattern::TriangleFan;
    const dxvk::DxvkBufferSlice indices(device->GetDXVKDevice()->getCommon()->metaGeometryUtils().getQuadIndexSource(pattern));
    remixapi_MeshInfoSurfaceTriangles surface {};
    surface.material = source.material;
    surface.vertices_count = source.quadCount * 4;
    surface.indices_count = source.quadCount * 6;
    auto geometry = makeSurfaceGeometry(surface, vertexSlice, indices, {}, {}, 12, VK_INDEX_TYPE_UINT16);
    geometry.normalBuffer = {};
    geometry.texcoordBuffer = {};
    geometry.texcoordBoundsBuffer = {};
    geometry.color0Buffer = {};
    geometry.quadAttributes = true;
    geometry.quadMaterialOffset = uint32_t(vertexBytes + indexBytes);
    geometry.boundingBox = bounds;
    geometry.quadMaxUvTileSize = dxvk::RtxGeometryUtils::computeMaxUVTileSize(geometry, dxvk::Matrix4());
    geometry.sharedQuadIndexPattern = pattern;
    return geometry;
  }

  void uploadQuadSurfaceGeometry(dxvk::DxvkContext* ctx, dxvk::RasterGeometry& geometry) {
    auto info = geometry.positionBuffer.buffer()->info();
    auto buffer = ctx->getDevice()->createBuffer(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
      dxvk::DxvkMemoryStats::Category::RTXAccelerationStructure, "Remix immutable quad geometry");
    ctx->copyBuffer(buffer, 0, geometry.positionBuffer.buffer(), 0, info.size);
    geometry.positionBuffer = dxvk::RasterBuffer { dxvk::DxvkBufferSlice(buffer), 0, 12, VK_FORMAT_R32G32B32_SFLOAT };
  }
