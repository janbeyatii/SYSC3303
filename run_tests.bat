@echo off
if not exist bin\model\IncidentTest.class (
    echo Tests not built. Running build_tests.bat first...
    call build_tests.bat
)

set JUNIT_LIB=lib\junit-4.13.2.jar
set HAMCREST_LIB=lib\hamcrest-core-1.3.jar
set CP=bin;%JUNIT_LIB%;%HAMCREST_LIB%

java -cp "%CP%" org.junit.runner.JUnitCore ^
  model.IncidentTest ^
  fireincident.SchedulerTest ^
  fireincident.FireIncidentSubsystemTest ^
  fireincident.DroneSubsystemTest ^
  fireincident.IntegrationTest

echo.
echo Exit code: %ERRORLEVEL%
