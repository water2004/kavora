/* SPDX-License-Identifier: MIT */
#include "../../../src/util/com/com_pointer.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>

namespace {
  void require(bool value, const char* pMessage) {
    if (!value) {
      fprintf(stderr, "FAIL: %s\n", pMessage);
      exit(1);
    }
  }

  // Keeps storage alive so the test can inspect the last Release without use-after-free.
  struct QueriedObject {
    uint32_t references = 1;
    uint32_t adds = 0;
    uint32_t releases = 0;
    uint32_t peakReferences = 1;

    ULONG AddRef() {
      adds++;
      references++;
      if (references > peakReferences) {
        peakReferences = references;
      }
      return references;
    }

    ULONG Release() {
      require(references > 0, "COM output reference must never over-release its caller");
      releases++;
      return --references;
    }

    HRESULT getParameters(uint32_t mode) const {
      if (mode == 2) {
        throw std::runtime_error("injected parameter failure");
      }
      return mode == 1 ? E_FAIL : S_OK;
    }
  };

  HRESULT queryOutput(QueriedObject& source, QueriedObject** output) {
    *output = dxvk::ref(&source);
    return S_OK;
  }

  HRESULT readParameters(QueriedObject& source, uint32_t mode) {
    dxvk::Com<QueriedObject> output;
    HRESULT status = queryOutput(source, &output);
    if (FAILED(status) || output == nullptr) {
      return E_FAIL;
    }
    return output->getParameters(mode);
  }

  void testQueriedOutputScopes() {
    QueriedObject swapchain;
    constexpr uint32_t kQueries = 10000;
    uint32_t exceptions = 0;
    for (uint32_t i = 0; i < kQueries; i++) {
      uint32_t mode = i % 3;
      try {
        HRESULT status = readParameters(swapchain, mode);
        require(mode == 0 ? SUCCEEDED(status) : FAILED(status), "parameter HRESULT must survive the scoped output");
      } catch (const std::runtime_error&) {
        require(mode == 2, "only injected parameter failure may throw");
        exceptions++;
      }
      require(swapchain.references == 1, "every frame, error return and exception must release the queried output");
    }
    require(exceptions == kQueries / 3, "exception path must run within the bounded loop");
    require(swapchain.adds == kQueries && swapchain.releases == kQueries && swapchain.peakReferences == 2,
      "queried COM objects must not accumulate one reference per frame");
    require(swapchain.Release() == 0, "original caller still owns its reference after all queried scopes");
  }

  void testCreationOutputScopes() {
    QueriedObject parent;
    QueriedObject device;
    {
      // Creation supplies an existing owned reference through the output pointer.
      dxvk::Com<QueriedObject> parentOutput;
      *&parentOutput = &parent;
      try {
        dxvk::Com<QueriedObject> deviceOutput;
        *&deviceOutput = &device;
        throw std::runtime_error("injected registration failure");
      } catch (const std::runtime_error&) {
        require(device.references == 0 && device.adds == 0 && device.releases == 1,
          "failed startup must release its created device exactly once");
        require(parent.references == 1, "parent remains alive while the outer startup scope exists");
      }
    }
    require(parent.references == 0 && parent.adds == 0 && parent.releases == 1,
      "startup must adopt creation output without adding a second reference");
  }
}

int main() {
  testQueriedOutputScopes();
  testCreationOutputScopes();
  printf("Queried COM output and startup temporary ownership tests passed.\n");
  return 0;
}
