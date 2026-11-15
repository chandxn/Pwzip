@rem Official Gradle startup script for Windows (Gradle 8.6)
@if "%DEBUG%"=="" @echo off
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set WRAPPER_JAR="%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"
if exist %WRAPPER_JAR% (
    java.exe -classpath %WRAPPER_JAR% org.gradle.wrapper.GradleWrapperMain %*
    exit /b %ERRORLEVEL%
)
where gradle >nul 2>nul
if %ERRORLEVEL% equ 0 (
    gradle %*
    exit /b %ERRORLEVEL%
)
echo ERROR: Missing gradle-wrapper.jar and 'gradle' is not in PATH. 1>&2
exit /b 1
