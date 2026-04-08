@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM Launches Scheduler, Drone(s), and Fire Incident as separate processes.
REM Usage:
REM   run_distributed.bat [incidentCsvPath]
REM Example:
REM   run_distributed.bat data/final_event_file_w26.csv

set "INCIDENT_CSV=%~1"
if "%INCIDENT_CSV%"=="" set "INCIDENT_CSV=data/final_event_file_w26.csv"

set "CONFIG_FILE=data/config.properties"
set "NUM_DRONES=10"
set "SCHED_HOST=127.0.0.1"
set "SCHED_PORT=5000"
set "DRONE_TIME_SCALE=0.01"
set "AGENT_CAPACITY=100"

if exist "%CONFIG_FILE%" (
  for /f "usebackq tokens=1,* delims==" %%A in ("%CONFIG_FILE%") do (
    set "K=%%A"
    set "V=%%B"
    if /I "!K!"=="numDrones" set "NUM_DRONES=!V!"
    if /I "!K!"=="schedulerHost" set "SCHED_HOST=!V!"
    if /I "!K!"=="schedulerPort" set "SCHED_PORT=!V!"
    if /I "!K!"=="droneTimeScale" set "DRONE_TIME_SCALE=!V!"
    if /I "!K!"=="agentCapacity" set "AGENT_CAPACITY=!V!"
  )
)

echo [run_distributed] Building classes and dependency classpath...
call mvn -q -DskipTests compile dependency:build-classpath "-Dmdep.outputFile=target/maven-classpath.txt" "-DincludeScope=compile"
if errorlevel 1 (
  echo [run_distributed] Build failed. Aborting.
  exit /b 1
)

REM SchedulerMain needs FlatLaf and other deps on the classpath, not only target/classes.
set "CP=target/classes"
if exist "target/test-classes" set "CP=target/classes;target/test-classes"
if exist "target\maven-classpath.txt" (
  for /f "usebackq delims=" %%a in ("target\maven-classpath.txt") do set "DEP_CP=%%a"
  set "CP=!CP!;!DEP_CP!"
)

echo [run_distributed] Starting Scheduler + GUI (SKIP_DRONE_LAUNCHER: drones started below)...
set "SKIP_DRONE_LAUNCHER=1"
start "SchedulerMain" cmd /k java -cp "%CP%" app.SchedulerMain "%INCIDENT_CSV%"

REM Small delay so scheduler socket is ready before drones connect.
timeout /t 1 /nobreak >nul

echo [run_distributed] Starting %NUM_DRONES% Drone process(es)...
for /l %%I in (1,1,%NUM_DRONES%) do (
  start "Drone %%I" cmd /k java -cp "%CP%" app.DroneMain %%I %SCHED_HOST% %SCHED_PORT% %DRONE_TIME_SCALE% %AGENT_CAPACITY%
)

REM Small delay so at least one drone is online before incidents begin.
timeout /t 1 /nobreak >nul

echo [run_distributed] Starting Fire Incident process...
start "FireIncidentMain" cmd /k java -cp "%CP%" app.FireIncidentMain "%INCIDENT_CSV%" %SCHED_HOST% %SCHED_PORT%

echo.
echo [run_distributed] All subsystems launched as separate programs.
echo [run_distributed] CSV: %INCIDENT_CSV%
echo [run_distributed] Host/Port: %SCHED_HOST%:%SCHED_PORT%
echo [run_distributed] Drones: %NUM_DRONES%

endlocal
