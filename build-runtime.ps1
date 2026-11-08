param(
    [Parameter(Mandatory = $true)][string]$SlangCompiler,
    [switch]$Profiling
)
$ErrorActionPreference = 'Stop'
$runtimeRoot = Join-Path $PSScriptRoot 'dxvk-remix'
$compilerPath = (Resolve-Path -LiteralPath $SlangCompiler).Path
Push-Location $PSScriptRoot
try {
    & git submodule update --init --recursive
    if ($LASTEXITCODE) { throw 'Cannot prepare the pinned source dependencies.' }
    Push-Location $runtimeRoot
    try {
        . ./build_common.ps1
        SetupBuild -BuildArch x64
        $tracy = $Profiling.IsPresent.ToString().ToLowerInvariant()
        & meson setup _BuildMinecraft --buildtype release --backend ninja `
            '-Ddownload_apics=false' "-Denable_tracy=$tracy" '-Dtracy_on_demand=true' `
            "-Dslang_compiler_path=$compilerPath"
        if ($LASTEXITCODE) { throw 'Meson configuration failed.' }
        & meson compile -C _BuildMinecraft
        if ($LASTEXITCODE) { throw 'Native compilation failed.' }
        & powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts-common/stage-minecraft-runtime.ps1 `
            -BuildDirectory _BuildMinecraft -Architecture win64
        if ($LASTEXITCODE) { throw 'Runtime API verification or output staging failed.' }
    } finally { Pop-Location }
} finally { Pop-Location }
