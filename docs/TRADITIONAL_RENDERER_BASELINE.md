# 传统 Vulkan 渲染管线基线

2026-11-04。CLAS/PTLAS 实验已暂停，三个仓库均在
`codex/clas-ptlas-experimental` 保留完整实现；当前开发分支为
`codex/traditional-renderer`，使用普通 Vulkan BLAS/TLAS。

## 来源与验证

- Native clean 提交：`116cd44ab6980066da8abdc46fe74efafc2cb1d3`。
- 主模组代码提交：`da958cc`；远景附加包：`1c82cf4`。
- DLL SHA-256：`a964241a8958844ebadb2372fe45bf578126ebaa66acafea0f998cfd4510ce55`。
- 保留了通用呈现、同步、提交失败处理、AS 内存访问分类及空场景修复。
- Native 构建、主模组完整 `build test`、附加包 `build test` 通过。
- 同一运行时通过 Gradle 开发客户端完成进世界与下述计时；截图可见完整地形、PBR 水岸、
  反射和透射。本轮未启用 Vulkan validation，不表示全部时序问题已排除。

## 新基线

`traditional-review-baseline`，RTX 5060 Laptop，驱动 621.01，零雾构想 v5003。
地面水边相机，近景距离 16、模拟距离 12；1920×1080 原生输入和输出、DLAA、RR，
生成帧数为零。900 帧预热后记录 360 帧；Tracy 开启，未锁频，世界 tick 和云仍在演化。
单轮基线不构成改造收益的因果证明，也不能与旧实验分支的时序混为同一实现。

| 指标 | ms |
|---|---:|
| 平均整帧，10.64 FPS | 94.01 |
| P1 帧时间 | 122.17 |
| InjectRTX | 90.498 |
| Gbuffer Raytracing | 49.296 |
| Primary Rays | 24.520 |
| PSR | 23.738 |
| Integrate Raytracing | 16.220 |
| Ray Reconstruction | 13.245 |
| Volumetrics | 4.989 |
| Build Scene | 1.605 |
| buildTLAS | 0.889 |

GPU zone 存在包含关系，不应相加。Primary/PSR 包含追踪及命中处理，不是纯 RT core 时间。
benchmark 使用 `System.exit`，退出时跳过正常 renderer shutdown 的残留提示不能直接
视为运行中的泄漏。本次没有 Nsight GPU Trace 或 Shader Profiler 的硬件报告。

## 评审资料

`build/reports/DXVK-Remix-Traditional-Source.zip` 包含实际工作区的完整 `src/`、
头文件、可用依赖源码/接口、构建及 shader 变体生成脚本、配置和版本记录。
源码采集包含本地 diff 与未跟踪清单，不只依赖 HEAD archive；此轮 native 工作区为 clean。
包含本次新的 profile，没有重复旧 CLAS/PTLAS 审计和探针数据。

本机原始证据：`.codex/validation/traditional-review-20261104/`，
运行记录位于 `.codex/validation/cluster-storage-20261104/benchmarks/traditional-review-baseline/`。
包内 `verify-package.py` 可用 Python 标准库复核文件哈希、provenance 和主要 GPU 时序摘要。
