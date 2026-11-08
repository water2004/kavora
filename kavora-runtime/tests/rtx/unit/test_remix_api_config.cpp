/*
 * Copyright (c) 2026 RTX Remix Minecraft contributors.
 * SPDX-License-Identifier: MIT
 */
#include <array>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>

#include "../../../src/dxvk/rtx_render/rtx_remix_api_config.h"
#include "../../../src/dxvk/rtx_render/rtx_option_manager.h"

namespace dxvk {
  Logger Logger::s_instance("test_remix_api_config.log");
}

namespace {
  using namespace dxvk;
  namespace api = dxvk::remixapi_config;

  void migrateTestPreset(DxvkDevice* device);

  struct Options {
    RTX_OPTION("rtx.apiconfigtest", int32_t, number, 10, "Test integer");
    RTX_OPTION_FLAG("rtx.apiconfigtest", bool, menu, false, RtxOptionFlags::NoSave, "Test menu");
    RTX_OPTION("rtx.apiconfigtest", float, amount, 10.0f, "Test blending");
    RTX_OPTION_ARGS("rtx.apiconfigtest", int32_t, preset, 0, "Test preset",
      args.onChangeCallback = migrateTestPreset);
    RTX_OPTION("rtx.apiconfigtest", int32_t, explicitValue, 0, "Explicit value");
    RTX_OPTION("rtx.apiconfigtest", int32_t, inheritedValue, 0, "Inherited value");
  };

  void migrateTestPreset(DxvkDevice* device) {
    if (!device || Options::preset() != 4) {
      return;
    }
    std::lock_guard<std::mutex> lock(RtxOptionImpl::getUpdateMutex());
    for (auto* option : { static_cast<RtxOptionImpl*>(&Options::explicitValue),
                          static_cast<RtxOptionImpl*>(&Options::inheritedValue) }) {
      option->moveLayerValue(RtxOptionLayer::getQualityLayer(), RtxOptionLayer::getUserLayer(),
        dxvk::RtxOptionManager::hasExplicitUserWrite(option));
    }
  }

  void require(bool condition, const char* message) {
    if (!condition) {
      throw std::runtime_error(message);
    }
  }

  void resolve() {
    dxvk::RtxOptionManager::applyPendingValues(nullptr, false);
  }

  void testValues() {
    require(api::setVariable("rtx.apiconfigtest.number", "19") == REMIXAPI_ERROR_CODE_SUCCESS, "queue static value before startup");
    require(api::setVariable("rtx.apiconfigtest.preset", "4") == REMIXAPI_ERROR_CODE_SUCCESS, "queue custom before startup");
    require(api::setVariable("rtx.apiconfigtest.explicitValue", "33") == REMIXAPI_ERROR_CODE_SUCCESS, "queue edit before startup");
    require(api::setVariable("rtx.apiconfigtest.number", "19oops") == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS, "reject partial numeric parse");
    auto info = remixapi_ConfigVariableResolvedInfo_init();
    info.key = "rtx.apiconfigtest.number";
    require(api::getResolved(&info) == REMIXAPI_ERROR_CODE_NOT_INITIALIZED, "read before startup");
    RtxOptionLayer::initializeSystemLayers();
    RtxOptionImpl::setInitialized(true);
    api::applyPendingVariables();
    Options::explicitValue.setDeferred(11, RtxOptionLayer::getQualityLayer());
    Options::inheritedValue.setDeferred(22, RtxOptionLayer::getQualityLayer());
    resolve();
    Options::preset.setDeferred(4, RtxOptionLayer::getUserLayer());
    dxvk::RtxOptionManager::applyPendingValues(reinterpret_cast<DxvkDevice*>(uintptr_t(1)), true);
    require(Options::explicitValue() == 33 && Options::inheritedValue() == 22,
      "startup custom preserves queued edit and inherits other preset values");
    require(Options::number() == 19, "apply queued static value");

    std::array<char, 8> output;
    output.fill('x');
    info.value = output.data();
    info.valueSize = 1;
    require(api::getResolved(&info) == REMIXAPI_ERROR_CODE_BUFFER_TOO_SMALL, "short buffer result");
    require(output[0] == 'x' && info.valueSize == 3, "short buffer unchanged and size includes NUL");
    info.valueSize = static_cast<uint32_t>(output.size());
    require(api::getResolved(&info) == REMIXAPI_ERROR_CODE_SUCCESS && std::string(output.data()) == "19", "resolved number");

    require(api::setVariable("rtx.apiconfigtest.menu", "True") == REMIXAPI_ERROR_CODE_SUCCESS, "set menu");
    resolve();
    require(Options::menu(), "menu true");
    Options::menu.setDeferred(false, RtxOptionLayer::getDerivedLayer());
    resolve();
    require(!Options::menu(), "runtime menu close wins after API write");
    const auto* menu = RtxOptionImpl::getOptionByFullName("rtx.apiconfigtest.menu");
    require(!menu->hasValueInLayer(RtxOptionLayer::getUserLayer()), "menu never written to user layer");

    DynamicOptionNamespace dynamic;
    dynamic.prefix = "rtx.apiconfigtest.dynamic.";
    dynamic.type = OptionType::Float;
    dynamic.defaultValue = "3.25";
    dynamic.flags = RtxOptionFlags::NoSave;
    require(dxvk::RtxOptionManager::registerDynamicNamespace(dynamic), "register dynamic namespace");
    info.key = "rtx.apiconfigtest.dynamic.unused";
    info.valueSize = static_cast<uint32_t>(output.size());
    require(api::getResolved(&info) == REMIXAPI_ERROR_CODE_SUCCESS && std::string(output.data()) == "3.25", "read uninstantiated default");
    info.key = "rtx.apiconfigtest.dynamic.bad..key";
    require(api::getResolved(&info) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS, "invalid dynamic key");
    require(api::setVariable("rtx.apiconfigtest.dynamic.value", "9") == REMIXAPI_ERROR_CODE_SUCCESS, "set dynamic NoSave");
    resolve();
    const auto* option = RtxOptionImpl::getOptionByFullName("rtx.apiconfigtest.dynamic.value");
    require(option && option->hasValueInLayer(RtxOptionLayer::getDerivedLayer()), "dynamic NoSave uses derived layer");
    require(!option->hasValueInLayer(RtxOptionLayer::getUserLayer()), "dynamic NoSave never masks runtime changes");
  }

  void testLayers() {
    const auto path = std::filesystem::temp_directory_path() / "remix_api_config_test.conf";
    {
      std::ofstream file(path);
      file << "rtx.apiconfigtest.amount = 30\n";
    }
    const std::string filename = path.u8string();
    remixapi_ConfigLayerHandle first = nullptr;
    require(api::acquireLayer(filename.c_str(), -1.0f, 0.0f, &first) == REMIXAPI_ERROR_CODE_SUCCESS, "acquire clamps negative priority safely");
    resolve();
    require(Options::amount() == 10.0f, "new layer disabled even with zero threshold");
    std::filesystem::remove(path);
    remixapi_ConfigLayerHandle second = nullptr;
    require(api::acquireLayer(filename.c_str(), 100.0f, 0.8f, &second) == REMIXAPI_ERROR_CODE_SUCCESS && second == first, "cached acquire does not reread deleted file");
    require(api::setLayerBlending(first, 0.5f, 0.8f) == REMIXAPI_ERROR_CODE_SUCCESS, "blend request");
    require(api::setLayerBlending(first, 0.25f, 0.6f) == REMIXAPI_ERROR_CODE_SUCCESS, "second blend request");
    resolve();
    require(Options::amount() == 20.0f, "multiple requests choose maximum strength");
    resolve();
    require(Options::amount() == 20.0f, "blend persists");
    require(api::releaseLayer(first) == REMIXAPI_ERROR_CODE_SUCCESS, "release one reference");
    resolve();
    require(Options::amount() == 20.0f, "remaining reference keeps layer");
    require(api::releaseLayer(second) == REMIXAPI_ERROR_CODE_SUCCESS, "release final reference");
    resolve();
    require(Options::amount() == 10.0f, "last release restores base");
    require(!api::validateLayer(first), "released handle invalid");
    require(api::releaseLayer(first) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS, "double release rejected");
    require(api::acquireLayer(filename.c_str(), 100.0f, 0.1f, &first) == REMIXAPI_ERROR_CODE_GENERAL_FAILURE, "missing file rejected");
    {
      std::ofstream file(path);
      file << "# Empty layer\n";
    }
    require(api::acquireLayer(filename.c_str(), 100.0f, 0.1f, &first) == REMIXAPI_ERROR_CODE_SUCCESS, "empty layer valid");
    require(api::setLayerBlending(first, std::numeric_limits<float>::quiet_NaN(), 0.0f) == REMIXAPI_ERROR_CODE_INVALID_ARGUMENTS, "NaN rejected");
    api::releaseAllLayers();
    require(!api::validateLayer(first), "shutdown releases layers");
    std::filesystem::remove(path);
  }

  void testPresetTransaction() {
    auto* device = reinterpret_cast<DxvkDevice*>(uintptr_t(1));
    const auto* quality = RtxOptionLayer::getQualityLayer();
    const auto* user = RtxOptionLayer::getUserLayer();
    Options::preset.setDeferred(0, user);
    dxvk::RtxOptionManager::applyPendingValues(device, false);
    Options::explicitValue.setDeferred(11, quality);
    Options::inheritedValue.setDeferred(22, quality);
    Options::inheritedValue.setDeferred(9, user);
    require(api::setVariable("rtx.apiconfigtest.preset", "4") == REMIXAPI_ERROR_CODE_SUCCESS, "set custom preset");
    require(api::setVariable("rtx.apiconfigtest.explicitValue", "33") == REMIXAPI_ERROR_CODE_SUCCESS, "set explicit value");
    dxvk::RtxOptionManager::applyPendingValues(device, false);
    require(Options::explicitValue() == 33, "same transaction preserves explicit user value");
    require(Options::inheritedValue() == 22, "custom inherits quality over older user value");
    require(!Options::explicitValue.hasValueInLayer(quality), "quality value removed after explicit edit");
    require(!Options::inheritedValue.hasValueInLayer(quality), "quality value removed after inheritance");

    Options::preset.setDeferred(0, user);
    dxvk::RtxOptionManager::applyPendingValues(device, false);
    Options::explicitValue.setDeferred(44, quality);
    Options::inheritedValue.setDeferred(55, quality);
    require(api::setVariable("rtx.apiconfigtest.preset", "4") == REMIXAPI_ERROR_CODE_SUCCESS, "set custom alone");
    dxvk::RtxOptionManager::applyPendingValues(device, false);
    require(Options::explicitValue() == 44 && Options::inheritedValue() == 55, "later custom inherits preset values");

  }
}

int main() {
  try {
    testValues();
    testLayers();
    testPresetTransaction();
    std::cout << "Remix API config tests passed\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
