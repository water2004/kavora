# 开发与构建

从仓库根目录执行本文命令。面向 Windows x64 和 Java 25；ARM64 工具链留在源码中，目前不作为发布目标。

## 环境

- 64 位 JDK 25，设置 `JAVA_HOME`。
- Visual Studio C++ 桌面开发工具和 Windows SDK；构建脚本通过 `vswhere` 查找工具链。
- Meson 1.8.2 或更新版本、Ninja、Python 3.9 或更新版本。
- Vulkan SDK，以及本分支使用的 Slang 2026.20。
- Git 与网络访问，下载 Git 子模块、Packman 及 Maven 依赖。

```powershell
git submodule update --init --recursive
.\build-runtime.ps1 -SlangCompiler 'C:\工具\slang\bin\slangc.exe'
.\stage-runtime.ps1
.\gradlew.bat release
```

`build-runtime.ps1` 在 `kavora-runtime/_BuildMinecraft/` 配置 Release 构建，编译运行时和着色器，再使用 `scripts-common/stage-minecraft-runtime.ps1` 生成 `kavora-runtime/_output/`。下载内容保留在忽略的依赖缓存中。增量编译仍使用 `meson compile`。

本分支需要自定义 Remix C API **1.5.0**。不要替换成上游 0.x 二进制。生成的 `.runtime-provenance.json` 记录源码提交、工作区是否有修改、架构、构建类型、Tracy 状态和 DLL 的 SHA-256。来源中的 `water2004/dxvk-remix` 是该 API 分支的身份标识。

`stage-runtime.ps1` 先验证来源、PE 架构及 DLL 哈希，再复制完整输出到 `kavora/runtime/win64/`。`kavora-support.dll` 则由 `kavora-sdk` 单独编译到自身 `build/native/`，不写入 Packman 缓存。

## Java 工程

| 命令 | 作用 |
| --- | --- |
| `gradlew.bat build` | 构建 Java 工程并执行 JVM 检查 |
| `gradlew.bat test` | 主模组和远景附加包的 JVM 测试 |
| `gradlew.bat :kavora-sdk:build` | 独立 Java SDK 和源码 JAR |
| `gradlew.bat :kavora:compileGametestJava` | 编译客户端 GameTest |
| `gradlew.bat :kavora:runClient` | 在独立开发目录启动客户端 |
| `gradlew.bat release` | 校验并收集两个发布 JAR 到 `dist/` |

主模组通过 Gradle 项目依赖使用 Java SDK，并将其类直接放进主模组 JAR。远景附加包只编译依赖主模组的扩展接口，不嵌入主模组。Sodium、Fabric API 和 Fabric Loader 始终是外部依赖。

`build` 不等于完整发行包。主模组的完整 JAR 由 `:kavora:packageJar` 生成，`verifyPackageJar` 检查运行时、资源、来源记录和许可证是否齐全。`release` 不启动游戏，也不自动运行 GPU 测试。

运行客户端时需要 `--enable-native-access=ALL-UNNAMED`，Gradle 开发配置已包含。Java 构建会编译 Windows 支持库；暂存同一目录的 DLL 前必须退出该客户端，避免锁定文件或改写它正在读取的配置。

## 调试与维护

- 游戏状态采集、Fabric 和 Sodium 集成：`kavora/src/`。
- 原生渲染、C API 和 Slang：`kavora-runtime/src/`、`kavora-runtime/public/include/`。
- FFM 布局、调用描述和生命周期：`kavora-sdk/src/`。
- 远景存储、选层和网格：`kavora-horizon/core/`、`kavora-horizon/common/`。

改变 API 时，同时更新运行时头文件、SDK 头文件、布局、函数描述和调用方。仅修改版本字符串不能证明 ABI 一致。`kavora-sdk/ffm-test/` 保留布局和 mock-runtime 合约检查。

可选的开发启动器和分析工具留在 `kavora/tools/`，不参与普通构建。Prism 工作流使用正常登录账户；也可以直接使用 Gradle `runClient`。这些工具中的相对路径以 `kavora/` 为基准。

JVM 测试不能验证 Vulkan 设备共享、呈现、光照或帧生成是否正确。涉及渲染行为的修改需要单独运行 GameTest 或实际场景，并记录画质、原生帧时间、内存与显存变化。相关方法见[渲染诊断](RENDERING_DIAGNOSTICS.md)和[调优](TUNING.md)。