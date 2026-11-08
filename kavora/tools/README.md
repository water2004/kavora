# 开发工具

这里保留可重复使用的 SDK 暂存、签入 Prism 的开发启动器、场景基准和 Tracy/JFR 分析工具。普通 Gradle 构建和发布 JAR 不依赖这里的 Python 环境。

在 `kavora/` 目录执行 `tools\\kavora.bat --help`。入口使用 uv，Python 和工具版本由 `pyproject.toml`、`.python-version`、`uv.lock` 固定。相对路径以 `kavora/` 为基准。

```powershell
# 也可以在仓库根目录使用较轻的 stage-runtime.ps1
.\\tools\\kavora.bat sdk local --arch win64 --path ..\\kavora-runtime\\_output
.\\tools\\kavora.bat sdk status --arch win64
.\\tools\\kavora.bat prism signin
.\\tools\\kavora.bat launch
```

启动器使用已安装的 Prism 和正常登录的 Minecraft 账户。它不是发行安装器，不是运行主模组的必要条件。Java SDK 使用 FFM，不构建 JNI 桥接。Prism 开发实例的版本和 JVM 默认值位于 `../packaging/launcher-defaults.json`，需与主模组 Gradle 开发参数保持一致。

- [启动器](docs/LAUNCHER.md)
- [基准与帧时间](docs/BENCHMARKING.md)
- [场景夹具](docs/BENCHMARK_FIXTURES.md)
- [性能分析](docs/PROFILING.md)

结果、临时下载、Python 虚拟环境和本机配置都被 Git 忽略。场景素材和用户资源包不属于本仓库源码，不能将下载缓存直接提交为发行资源。