param([string]$Source = (Join-Path $PSScriptRoot 'dxvk-remix/_output'))
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath $Source).Path
$metadata = Join-Path $sourceRoot '.runtime-provenance.json'
$dll = Join-Path $sourceRoot 'd3d9.dll'
$provenance = Get-Content -LiteralPath $metadata -Raw | ConvertFrom-Json
if ($provenance.fork -ne 'water2004/dxvk-remix' -or $provenance.apiVersion -ne '1.5.0' -or
    $provenance.architecture -ne 'win64' -or $provenance.sourceCommit -notmatch '^[0-9a-f]{40}$' -or
    $provenance.sourceDirty -isnot [bool]) { throw 'Expected a source-built custom Remix 1.5.0 win64 runtime.' }
if ((Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash.ToLowerInvariant() -ne $provenance.dllSha256) {
    throw 'The runtime DLL does not match its build provenance.'
}
$stream = [IO.File]::OpenRead($dll)
try {
    $reader = [IO.BinaryReader]::new($stream)
    if ($reader.ReadUInt16() -ne 0x5a4d) { throw 'Invalid PE executable.' }
    $stream.Position = 0x3c
    $peOffset = $reader.ReadUInt32()
    $stream.Position = $peOffset
    if ($reader.ReadUInt32() -ne 0x4550 -or $reader.ReadUInt16() -ne 0x8664) {
        throw 'The runtime must be a Windows x64 PE executable.'
    }
} finally { $stream.Dispose() }
$stagingRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'rtx-remix-minecraft/remix-runtime'))
$targetRoot = Join-Path $stagingRoot 'win64'
if ($sourceRoot -eq $targetRoot -or $sourceRoot.StartsWith($targetRoot + [IO.Path]::DirectorySeparatorChar)) {
    throw 'The runtime output must be outside its staging destination.'
}
foreach ($path in @($stagingRoot, $targetRoot)) {
    if (Test-Path -LiteralPath $path) {
        if ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Refusing to write through a linked staging directory: $path"
        }
    }
}
New-Item -ItemType Directory -Force -Path $targetRoot | Out-Null
Get-ChildItem -LiteralPath $sourceRoot -Force | Copy-Item -Destination $targetRoot -Recurse -Force
$sentinel = "source: $sourceRoot`nstaged_at_utc: $([DateTime]::UtcNow.ToString('o'))`n"
[IO.File]::WriteAllText((Join-Path $targetRoot '.local-sdk-source'), $sentinel, [Text.UTF8Encoding]::new($false))
Write-Output "Validated and staged runtime: $targetRoot"
