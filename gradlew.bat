@rem
@rem Gradle startup script for Windows generated for JARVIS Mobile (Gradle 8.11.1)
@rem
@if "%DEBUG%"=="" @echo off
setlocal enabledelayedexpansion

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
    set JAVA_EXE=%JAVA_HOME%/bin/java.exe
) else (
    set JAVA_EXE=java.exe
)

if not exist "%CLASSPATH%" (
    where gradle >nul 2>nul
    if !ERRORLEVEL! EQU 0 (
        gradle -p "%APP_HOME%" %*
        exit /b !ERRORLEVEL!
    )
    powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar' -OutFile '%CLASSPATH%'"
)

"%JAVA_EXE%" -Xmx64m -Xms64m %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
endlocal