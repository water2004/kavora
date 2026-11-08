@echo off
setlocal EnableDelayedExpansion
if "%JAVA_HOME%"=="" exit /b 1
if "%VSCMD_ARG_TGT_ARCH%"=="" (
  set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
  for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath`) do call "%%i\VC\Auxiliary\Build\vcvars64.bat" ^>nul
  if errorlevel 1 exit /b 1
)
set "ROOT=%~dp0..\.."
set "OUT=%TEMP%\rtx-remix-ffm-bridge-%RANDOM%"
mkdir "%OUT%"
cl /nologo /TC /utf-8 /std:c11 /LD /I"%ROOT%\rtxremix-java\native\include" "%~dp0mock_runtime.c" /Fo"%OUT%\mock_runtime.obj" /Fe"%OUT%\mock_runtime.dll" /link /IMPLIB:"%OUT%\mock_runtime.lib"
if errorlevel 1 exit /b 1
if "%JOML_JAR%"=="" for /r "%USERPROFILE%\.gradle\caches\modules-2\files-2.1\org.joml\joml" %%f in (joml-*.jar) do set "JOML_JAR=%%f"
if "%JOML_JAR%"=="" (
  echo Set JOML_JAR to the JOML jar used by the mod.
  exit /b 1
)
for %%f in ("%ROOT%\rtxremix-java\src\com\nvidia\rtxremix\*.java") do (
  set "SOURCE=%%f"
  echo "!SOURCE:\=/!" >> "%OUT%\sources.txt"
)
set "SOURCE=%~dp0RemixBridgeContractTest.java"
echo "!SOURCE:\=/!" >> "%OUT%\sources.txt"
set "SOURCE=%~dp0RemixRuntimeLifecycleTest.java"
echo "!SOURCE:\=/!" >> "%OUT%\sources.txt"
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 -cp "%JOML_JAR%" -d "%OUT%" @"%OUT%\sources.txt"
if errorlevel 1 exit /b 1
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "%OUT%;%JOML_JAR%" com.nvidia.rtxremix.RemixBridgeContractTest "%OUT%\mock_runtime.dll"
if errorlevel 1 exit /b 1
"%JAVA_HOME%\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "%OUT%;%JOML_JAR%" com.nvidia.rtxremix.RemixRuntimeLifecycleTest "%OUT%\mock_runtime.dll"
exit /b %ERRORLEVEL%
