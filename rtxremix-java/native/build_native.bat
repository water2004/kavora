:: SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
:: SPDX-License-Identifier: Apache-2.0
@echo off
setlocal enabledelayedexpansion

REM Build the small crash-handler C ABI helper. Remix calls use Java FFM directly.
REM Outputs stay outside remix-runtime, whose directories can be shared junctions.
set "TARGET_ARCH=%~1"
if "%TARGET_ARCH%"=="" set "TARGET_ARCH=x64"
if /I "%TARGET_ARCH%"=="x64" (
    set "VCARCH=x64"
    set "OUT_ARCH=win64"
) else if /I "%TARGET_ARCH%"=="arm64" (
    set "VCARCH=x64_arm64"
    set "OUT_ARCH=arm64"
) else (
    echo ERROR: Unknown architecture: %TARGET_ARCH%. Use x64 or arm64.
    exit /b 1
)

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
    echo ERROR: vswhere.exe not found. Install Visual Studio C++ tools.
    exit /b 1
)
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -property installationPath`) do set "VS_PATH=%%i"
if not defined VS_PATH (
    echo ERROR: No Visual Studio installation found.
    exit /b 1
)
call "%VS_PATH%\VC\Auxiliary\Build\vcvarsall.bat" %VCARCH% >nul 2>&1
if errorlevel 1 (
    echo ERROR: vcvarsall.bat failed. Install the C++ tools and target architecture libraries.
    exit /b 1
)

set "OUT_DIR=%~dp0..\..\build\native\%OUT_ARCH%"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
echo Compiling rtxremix-support.dll for %OUT_ARCH% ...
cl /nologo /O2 /Zi /LD /EHsc /std:c++17 /D_CRT_SECURE_NO_WARNINGS ^
   "%~dp0crash_support.cpp" ^
   /Fe"%OUT_DIR%\rtxremix-support.dll" ^
   /Fo"%OUT_DIR%\rtxremix-support.obj" ^
   /Fd"%OUT_DIR%\rtxremix-support-compile.pdb" ^
   /link /nologo /DEBUG /PDB:"%OUT_DIR%\rtxremix-support.pdb" dbghelp.lib
if errorlevel 1 exit /b 1
echo Built %OUT_DIR%\rtxremix-support.dll
endlocal
