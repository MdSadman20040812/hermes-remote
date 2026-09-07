@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
set "ANDROID_HOME=D:\Android\SDK"
set "GRADLE_HOME=D:\Apps\gradle-8.9"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%GRADLE_HOME%\bin;%PATH%"

echo [build] JAVA_HOME=%JAVA_HOME%
echo [build] ANDROID_HOME=%ANDROID_HOME%
echo [build] GRADLE_HOME=%GRADLE_HOME%

cd /d D:\HermesMobile
echo [build] Cleaning...
"%GRADLE_HOME%\bin\gradle.bat" clean --console=plain
echo [build] Assembling debug APK...
"%GRADLE_HOME%\bin\gradle.bat" assembleDebug --console=plain
echo [build] Done. APK should be at app\build\outputs\apk\debug\app-debug.apk
endlocal
