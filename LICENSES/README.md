# 许可范围

这是包含多个组件的源码仓库。根目录的 `LICENSE` 是索引，各组件与文件原有的许可声明继续适用。不要删除原始版权头，也不要用一个根目录许可证覆盖所有代码和二进制。

| 范围 | 许可 | 原始文本与说明 |
| --- | --- | --- |
| Minecraft 集成主体 | Apache-2.0 | [主模组 LICENSE](../kavora/LICENSE) |
| Java 25 FFM SDK 和 Windows 支持库 | Apache-2.0 | [SDK LICENSE](../kavora-sdk/LICENSE) |
| 复制的 NVIDIA C API 头文件 | MIT | [头文件声明](../kavora-sdk/native/include/remix_c.h)、[MIT 全文](MIT-NVIDIA.txt) |
| DXVK 原始代码 | zlib/libpng | [运行时 LICENSE](../kavora-runtime/LICENSE) |
| NVIDIA Remix 原生主体 | MIT | [运行时 LICENSE-MIT](../kavora-runtime/LICENSE-MIT) |
| Iris 派生界面代码与控件纹理 | LGPL-3.0-only | [原始声明及修改说明](../kavora/src/main/resources/META-INF/licenses/iris/NOTICE.txt) |
| Caustica-Horizon 末地与月亮着色器适配 | LGPL-3.0-or-later | [来源及修改说明](../kavora/packaging/licenses/Caustica-Horizon-End.txt) |
| Community Edition 云、材质和光源适配 | Apache-2.0 | [云](../kavora/packaging/licenses/RTX-Remix-CE-Clouds.txt)、[材质及光源](../kavora/packaging/licenses/RTX-Remix-CE-Materials.txt) |
| Kavora Horizon | LGPL-3.0-only | [LGPL](../kavora-horizon/LICENSE.LESSER.txt)、[保留的 GPL 文本](../kavora-horizon/LICENSE.txt) |
| 远景的独立材质描述代码 | Apache-2.0 | [原始文本](../kavora-horizon/common/src/main/resources/LICENSE-APACHE-2.0.txt) |
| 远景内嵌的 RocksDB | 选择 Apache-2.0 许可 | [RocksDB 和 LevelDB 声明](../kavora-horizon/licenses/) |
| NASA 银河和月面数据 | NASA 公有领域数据及各自署名 | [银河](../kavora/packaging/licenses/NASA-Deep-Star-Maps.txt)、[月面](../kavora/packaging/licenses/NASA-CGI-Moon.txt) |
| DLSS、NRC、NRD 等 NVIDIA SDK 二进制 | NVIDIA 各组件的 SDK 条款 | [运行时第三方文本](../kavora/packaging/licenses/runtime/)、[总清单](../THIRD_PARTY_NOTICES.md) |
| 其他原生依赖 | 各依赖的原始许可 | [DXVK Remix 第三方清单](../kavora-runtime/ThirdPartyLicenses.txt)及随包文本 |

通用许可证全文集中在本目录：

- [Apache-2.0](Apache-2.0.txt)
- [MIT（NVIDIA）](MIT-NVIDIA.txt)
- [zlib/libpng（DXVK）](Zlib-DXVK.txt)
- [LGPLv3](LGPL-3.0.txt)
- [GPLv3](GPL-3.0.txt)

## 二进制发布

`Kavora.jar` 中保留主模组、SDK、Iris 界面和原生运行时的许可证及署名。`Kavora-horizon.jar` 单独保留其 LGPL/GPL、Apache 和 RocksDB/LevelDB 文本。构建会检查必需文本，不能只复制 DLL 而丢弃通知文件。

源码发布应包含对应版本的修改后界面与着色器。发布源码与二进制使用同一提交或标签，包含修改后的文件、构建入口及固定依赖版本。Java 源码 JAR 可用于查看和修改 Java 层；完整原生源码在 `kavora-runtime/` 中，不能以 Java 源码 JAR 代替。修改后的组件可以按开发指南重建和替换，无签名限制。

NVIDIA SDK 的二进制使用和分发仍遵循它们的原始条款；公开可下载不表示它们自动成为 Apache、MIT 或 GPL 组件。本说明记录许可范围与来源，不重新授予第三方权利。