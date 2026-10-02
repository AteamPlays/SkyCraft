@echo off
setlocal
cd /d "%~dp0..\neoforge"
set "OLD_JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS%"
set "JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Dskycraft.realSkyrim=true"
call ..\fabric\gradlew.bat runClient
set "JAVA_TOOL_OPTIONS=%OLD_JAVA_TOOL_OPTIONS%"
endlocal
