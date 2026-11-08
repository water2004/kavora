# 安装与使用

安装步骤和依赖版本见[项目首页](../README.md#安装)。安装发布版 `Kavora.jar`，不要把开发用的轻量 JAR 当完整发行包。

Sodium 和 Fabric API 独立安装。Kavora Horizon 为可选附加包。资源包照常放入实例的 `resourcepacks/`，不需要把 SDK 或 DLL 手动放入启动器目录。

游戏使用 Vulkan。视觉设置、画质预设和独立性能选项见[设置](SETTINGS.md)。首次解压、管线编译和区块流式加载可能产生等待；遇到卡死时保留游戏日志和原生崩溃信息，诊断步骤见[渲染诊断](RENDERING_DIAGNOSTICS.md)。

运行时文件、作者配置及提取规则见[调优](TUNING.md)。更换主模组时使用完整新 JAR，不要混用旧 DLL。