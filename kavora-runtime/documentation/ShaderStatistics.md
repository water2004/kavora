# Pipeline executable statistics

Launch the target with `RTX_PIPELINE_STATISTICS=1` in its process environment,
before Remix creates the Vulkan device. This requests
`VK_KHR_pipeline_executable_properties` when the device supports it and captures
statistics for DXVK compute and ray tracing pipelines. It is disabled by default.
It does not instrument pipelines created internally by libraries such as NGX.

The runtime queries statistics once after compiling each pipeline. It does not
read counters back during rendering. `PipelineExecutable` records identify the
shader key, executable index, stages and subgroup size. `PipelineStatistics`
records repeat the key and index on every line so that results from concurrent
compiler threads remain attributable. Names, formats, values and descriptions
are reported as supplied by the driver.

Statistics availability and meaning depend on the implementation. A pipeline
may expose no executables or no statistics. Check the driver descriptions before
interpreting units. In particular, do not interpret an implausible local-memory
value as measured spilling or silently truncate it to a smaller integer. The
original values belong with the driver version and the corresponding shader
bytecode.

Use these records alongside an Nsight GPU Trace of the actual workload. A high
register count alone does not identify an expensive instruction or quantify the
benefit of a refactor. Verify performance with statistics disabled, recording the
runtime provenance, shader variants, effective settings and scene for each run.

The Vulkan specification describes the
[executable query interface](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_pipeline_executable_properties.html)
and the
[statistics capture requirement](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetPipelineExecutableStatisticsKHR.html).
