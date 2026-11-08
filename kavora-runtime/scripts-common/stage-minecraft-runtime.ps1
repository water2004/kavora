param(
  [Parameter(Mandatory = $true)][string]$BuildDirectory,
  [ValidateSet('win64', 'arm64')][string]$Architecture = 'win64'
)
$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $sourceRoot $BuildDirectory))
if (-not $buildRoot.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'The build directory must be inside this source checkout.'
}
$outputRoot = Join-Path $sourceRoot '_output'
$builtDll = Join-Path $buildRoot 'src/d3d9/d3d9.dll'
if (-not (Test-Path -LiteralPath $builtDll -PathType Leaf)) {
  throw 'Build the d3d9 target successfully before staging the Minecraft runtime.'
}
& meson install -C $buildRoot --no-rebuild --tags output
if ($LASTEXITCODE) {
  throw 'Meson runtime installation failed.'
}
$stagedDll = Join-Path $outputRoot 'd3d9.dll'
if ((Get-FileHash -LiteralPath $builtDll).Hash -ne (Get-FileHash -LiteralPath $stagedDll).Hash) {
  throw 'Staged d3d9.dll differs from this build output.'
}

# Verify the actual DLL's custom API before giving it source-build provenance.
if (-not ('MinecraftRemix.RuntimeProbe' -as [type])) {
  Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace MinecraftRemix {
  public static class RuntimeProbe {
    [DllImport("kernel32", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr LoadLibraryExW(string path, IntPtr file, uint flags);
    [DllImport("kernel32", CharSet = CharSet.Ansi)]
    static extern IntPtr GetProcAddress(IntPtr module, string name);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int Initialize(IntPtr info, IntPtr output);
    [StructLayout(LayoutKind.Sequential)]
    struct Info { public int type; public IntPtr next; public ulong version; }
    public static void Verify(string path) {
      IntPtr module = LoadLibraryExW(path, IntPtr.Zero, 0x100u | 0x1000u);
      if (module == IntPtr.Zero) throw new InvalidOperationException("Cannot load staged runtime: " + Marshal.GetLastWin32Error());
      IntPtr info = Marshal.AllocHGlobal(Marshal.SizeOf<Info>());
      IntPtr table = Marshal.AllocHGlobal(44 * IntPtr.Size);
      try {
        IntPtr symbol = GetProcAddress(module, "remixapi_InitializeLibrary");
        if (symbol == IntPtr.Zero) throw new InvalidOperationException("Missing Remix initialization export.");
        var initialize = Marshal.GetDelegateForFunctionPointer<Initialize>(symbol);
        var request = new Info { type = 1, version = (1UL << 48) | (5UL << 16) };
        Marshal.StructureToPtr(request, info, false);
        int status = initialize(info, table);
        if (status != 0) throw new InvalidOperationException("Custom 1.5.0 runtime initialization failed: " + status);
        for (int i = 0; i < 44; i++) {
          if (Marshal.ReadIntPtr(table, i * IntPtr.Size) == IntPtr.Zero)
            throw new InvalidOperationException("Missing custom runtime API slot " + i);
        }
        request.version = 16UL << 16;
        Marshal.StructureToPtr(request, info, false);
        if (initialize(info, table) == 0)
          throw new InvalidOperationException("Runtime incorrectly accepts the incompatible NVIDIA 0.16 ABI.");
      } finally {
        Marshal.FreeHGlobal(table);
        Marshal.FreeHGlobal(info);
        // Tracy's Windows runtime is process-lived. Unloading it calls ExitProcess.
        // Retain this reference until the verification process exits normally.
      }
    }
  }
}
'@
}
if ($Architecture -eq 'arm64' -and [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'Arm64') {
  throw 'Verify and stage the ARM64 DLL on an ARM64 host before issuing runtime provenance.'
}
[MinecraftRemix.RuntimeProbe]::Verify($stagedDll)
$sourceCommit = (& git -C $sourceRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -or $sourceCommit -notmatch '^[0-9a-f]{40}$') {
  throw 'Cannot identify the source revision.'
}
$dirty = [bool](& git -C $sourceRoot status --porcelain --untracked-files=normal)
$buildOptions = Get-Content -LiteralPath (Join-Path $buildRoot 'meson-info/intro-buildoptions.json') -Raw | ConvertFrom-Json
$buildType = ($buildOptions | Where-Object name -eq 'buildtype').value
$tracyEnabled = ($buildOptions | Where-Object name -eq 'enable_tracy').value
$marker = [ordered]@{
  fork = 'water2004/dxvk-remix'
  apiVersion = '1.5.0'
  architecture = $Architecture
  sourceCommit = $sourceCommit
  sourceDirty = $dirty
  buildType = $buildType
  tracyEnabled = $tracyEnabled
  dllSha256 = (Get-FileHash -LiteralPath $stagedDll -Algorithm SHA256).Hash.ToLowerInvariant()
}
[IO.File]::WriteAllText((Join-Path $outputRoot '.runtime-provenance.json'),
  ($marker | ConvertTo-Json) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Output "Verified custom source runtime staged at $outputRoot"
