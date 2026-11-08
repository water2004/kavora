# RTX Remix Minecraft 集成

Fabric 26.3 主模组，负责 Sodium 地形捕获、实体与粒子、材质资源、游戏生命周期，以及视觉和性能设置。

运行时在 [dxvk-remix](../dxvk-remix/)，FFM SDK 在 [rtxremix-java](../rtxremix-java/)，可选远景在 [Remix Horizons](../remix-horizons/)。

从仓库根目录执行 `gradlew.bat :rtx-remix-minecraft:build`；完整安装包执行 `gradlew.bat release`。普通 Java 构建依赖已暂存的匹配运行时，详见[开发指南](../docs/GETTING_STARTED.md)。

主体代码为 Apache-2.0；Iris 派生界面保留 LGPL-3.0-only。运行时二进制和第三方适配的许可见[仓库许可说明](../LICENSES/README.md)。`packaging/` 保存发行配置、运行时资源和必须随包分发的通知；不包含安装器。