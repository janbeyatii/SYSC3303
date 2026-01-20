@echo off
echo Building Fire Incident Subsystem...

if not exist bin mkdir bin

javac -d bin -sourcepath src/main/java src/main/java/app/*.java src/main/java/fireincident/*.java src/main/java/model/*.java

if %ERRORLEVEL% EQU 0 (
    echo Build successful!
    echo Run with: java -cp bin app.Main
) else (
    echo Build failed!
    exit /b 1
)
