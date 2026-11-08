# RTX Remix for Minecraft

为 Minecraft 26.3 Fabric 提供基于 NVIDIA RTX Remix 的光线追踪渲染。

本项目由 NVIDIA 的 Minecraft 集成移植而来，使用 Vulkan 和 Java 25 FFM。游戏与运行时共用一个 Vulkan 设备，地形由 Sodium 捕获，近景、远景、实体和粒子进入同一套光追场景。

这是社区维护的移植项目，与 NVIDIA、Mojang 和 Microsoft 没有隶属关系。不是官方 Minecraft 产品。

## 功能

- 光追光照、反射和透射，支持 LabPBR 资源包。
- 体积云、雾和散射；主世界昼夜天空，以及独立的末地天空。
- NRC、SHaRC 等间接光照模式；DLSS 超分、DLAA 和受设备支持的帧生成。
- Iris 风格的视觉设置界面。画质档位是可编辑的参数预设，DLSS 和性能设置独立。
- 可选的 **Remix Horizons** 远景附加包，记录已见过的地形，并在同一光追管线中显示。

## 安装

目前面向 **Windows x64、Minecraft 26.3、Java 25**。需要支持 Vulkan 光追的 NVIDIA RTX 显卡及相应驱动；帧生成的可用倍率由设备和运行时检测决定。

在 Fabric 实例的 `mods` 目录放入：

| 文件 | 说明 |
| --- | --- |
| `RTX-Remix.jar` | 主模组，发布版包含匹配的原生运行时 |
| Sodium 0.9.2 | 必需，独立安装 |
| Fabric API 0.165.0+26.3 | 必需，独立安装 |
| `Remix-Horizons.jar` | 可选，远景附加包 |

Fabric Loader 需要 0.20.1 或更新版本。启动参数加入 `--enable-native-access=ALL-UNNAMED`。使用 Vulkan 后端；本项目不提供 OpenGL 回退。

直接安装 JAR 即可，不需要安装器，也不需要另装上游 Remix SDK。请勿混装旧版主模组或其他版本的 Remix DLL。Iris 光影包不由本项目加载；Voxy 不受支持，Remix Horizons 也不能与 Distant Horizons 同装。

首次启动会解压运行时。进世界时还需要编译管线、上传材质和构建场景，复杂资源包及远景会增加内存和显存需求。

## 设置与资源包

视频设置由 Sodium 提供。视觉效果入口中可以调整光照、天空、云、雾、水和材质；DLSS、帧生成与性能选项留在 Sodium 页面。默认间接光照为 NRC，也可以单独选择 SHaRC。

主模组自带基础材质补充。额外的 LabPBR 资源包按普通资源包安装，优先级高于内置材质。详见[设置说明](docs/SETTINGS.md)和[资源包说明](docs/RESOURCE_PACKS.md)。

Remix Horizons 只记录客户端实际收到的区块，不生成未探索地形。远景距离和细节在其 Sodium 设置页调整。详见[远景说明](docs/FAR_TERRAIN.md)。

## 源码结构

```text
rtx-remix-minecraft/   Fabric 主模组、Sodium 集成、设置界面与资源
dxvk-remix/           修改后的原生运行时、Vulkan 渲染与 Slang 着色器
rtxremix-java/        独立 Java 25 FFM SDK、C API 头文件与进程支持库
remix-horizons/       可选远景附加包、体素缓存与网格调度
docs/                安装、开发与诊断文档
LICENSES/            许可范围说明和通用许可证全文
```

Java 工程使用根目录的 Gradle Wrapper 统一构建。原生运行时使用 Meson，第三方源码依赖由 Git 子模块固定版本。克隆时请使用 `git clone --recurse-submodules`；已有克隆运行 `git submodule update --init --recursive`。

## 从源码构建

准备 Java 25、Visual Studio C++ 工具链、Meson、Ninja 和 Slang 2026.20。原生构建还需要 Windows/Vulkan SDK，首次构建会下载固定版本的运行时依赖。

在仓库根目录运行：

```powershell
# 构建运行时并生成 API / DLL 来源记录
.\build-runtime.ps1 -SlangCompiler '你的 Slang 目录\bin\slangc.exe'

# 将完成的运行时暂存给主模组
.\stage-runtime.ps1

# 构建并收集两个可安装的 JAR
.\gradlew.bat release
```

输出为 `dist/RTX-Remix.jar` 和 `dist/Remix-Horizons.jar`。Sodium 和 Fabric API 不会嵌入它们。主模组的普通 `build` 输出是供开发使用的轻量 JAR，发布请使用 `release`。

编译、测试和启动的单独命令，以及原生依赖和来源校验，见[开发指南](docs/GETTING_STARTED.md)。构建不自动启动游戏。请先关闭从本仓库启动的客户端，再重新暂存 DLL 或运行时配置。

## 许可与致谢

本仓库包含适用不同许可证的组件，**不能将整个仓库及附带 SDK 二进制统一视为 Apache 或 MIT 项目**。各文件原有的版权和许可证声明均保留。

- Minecraft 集成和 Java FFM SDK 的主体为 Apache-2.0。
- DXVK 为 zlib/libpng，Remix 原生主体为 MIT；其中的第三方适配保留原许可。
- Iris 界面适配为 LGPL-3.0-only，Caustica-Horizon 着色器适配为 LGPL-3.0-or-later。
- Remix Horizons 为 LGPL-3.0-only；其中独立材质描述代码保留 Apache-2.0。
- NVIDIA SDK 二进制、NASA 数据及其他依赖分别适用它们的条款。

完整范围见[许可说明](LICENSES/README.md)及[第三方清单](THIRD_PARTY_NOTICES.md)。感谢 NVIDIA RTX Remix、DXVK、Sodium、Iris、RTX Remix Minecraft Community Edition、Caustica-Horizon 和这些项目的贡献者。Voxy 仅作为远景架构研究的参考，其代码和资源未包含在本项目中。