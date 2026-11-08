@echo off
setlocal
if "%JAVA_HOME%"=="" (
  echo JAVA_HOME must point to JDK 25 or newer.
  exit /b 1
)
if "%VSCMD_ARG_TGT_ARCH%"=="" (
  set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
  for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath`) do call "%%i\VC\Auxiliary\Build\vcvars64.bat" ^>nul
  if errorlevel 1 exit /b 1
)
set "ROOT=%~dp0..\.."
set "OUT=%TEMP%\rtx-remix-ffm-contract-%RANDOM%"
mkdir "%OUT%"
python "%~dp0generate_layouts.py" --check
if errorlevel 1 exit /b 1
cl /nologo /TC /std:c11 /W4 /WX /I"%ROOT%\rtxremix-java\native\include" "%~dp0layout_probe.c" /Fo"%OUT%\layout_probe.obj" /Fe"%OUT%\layout_probe.exe"
if errorlevel 1 exit /b 1
"%OUT%\layout_probe.exe" > "%OUT%\layout-contract.txt"
"%JAVA_HOME%\bin\javac.exe" -d "%OUT%" "%ROOT%\rtxremix-java\src\com\nvidia\rtxremix\RemixLayouts.java" "%~dp0RemixLayoutContractTest.java"
if errorlevel 1 exit /b 1
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "%OUT%" com.nvidia.rtxremix.RemixLayoutContractTest "%OUT%\layout-contract.txt"
exit /b %ERRORLEVEL%
