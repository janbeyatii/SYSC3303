@echo off
echo Building tests...

echo Building main code first...
call build.bat
if %ERRORLEVEL% NEQ 0 exit /b 1

set JUNIT_LIB=lib\junit-4.13.2.jar
set HAMCREST_LIB=lib\hamcrest-core-1.3.jar

if not exist "%JUNIT_LIB%" (
    echo JUnit not found. Place junit-4.13.2.jar and hamcrest-core-1.3.jar in lib/
    echo See lib/README.txt for download links.
    exit /b 1
)

set CP=bin;%JUNIT_LIB%;%HAMCREST_LIB%

javac -cp "%CP%" -d bin ^
  src/test/java/model/IncidentTest.java ^
  src/test/java/fireincident/SchedulerTest.java ^
  src/test/java/fireincident/FireIncidentSubsystemTest.java ^
  src/test/java/fireincident/DroneSubsystemTest.java

if %ERRORLEVEL% EQU 0 (
    echo Test build successful.
) else (
    echo Test build failed.
    exit /b 1
)
