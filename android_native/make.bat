@echo off
rem ============================================================================
rem  make.bat: pp-coach-tracker tests and APK from Windows.
rem
rem    make.bat            tests + debug APK (default)
rem    make.bat test       JVM tests only
rem    make.bat debug      debug APK only
rem    make.bat release    release APK (signed if keystore.properties exists)
rem    make.bat keystore   creates the release key and keystore.properties (once)
rem    make.bat install    installs the debug APK on the connected device/emulator
rem    make.bat clean      deletes build\
rem ============================================================================
setlocal
cd /d "%~dp0"
set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=all"

if /i "%TARGET%"=="test" goto :test
if /i "%TARGET%"=="debug" goto :debug
if /i "%TARGET%"=="release" goto :release
if /i "%TARGET%"=="install" goto :install
if /i "%TARGET%"=="keystore" goto :keystore
if /i "%TARGET%"=="clean" goto :clean
if /i "%TARGET%"=="all" goto :all
echo Unknown target "%TARGET%". Use: test, debug, release, keystore, install, clean
exit /b 1

:all
call gradlew.bat :app:testDebugUnitTest :app:assembleDebug || goto :failed
echo.
echo   Tests green. APK: app\build\outputs\apk\debug\app-debug.apk
exit /b 0

:test
call gradlew.bat :app:testDebugUnitTest || goto :failed
echo   Tests green. Report: app\build\reports\tests\testDebugUnitTest\index.html
exit /b 0

:debug
call gradlew.bat :app:assembleDebug || goto :failed
echo   APK: app\build\outputs\apk\debug\app-debug.apk
exit /b 0

:release
if not exist keystore.properties echo   No keystore.properties: the release APK will be unsigned. Run make.bat keystore first.
call gradlew.bat :app:assembleRelease || goto :failed
echo   APK: app\build\outputs\apk\release\
exit /b 0

:keystore
powershell -NoProfile -ExecutionPolicy Bypass -File tools\keystore.ps1 || exit /b 1
exit /b 0

:install
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if defined ANDROID_HOME set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk || goto :failed
exit /b 0

:clean
call gradlew.bat clean
exit /b 0

:failed
echo.
echo   Failed: the Gradle error is above.
exit /b 1
