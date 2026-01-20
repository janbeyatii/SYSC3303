@echo off
if not exist bin (
    echo Bin directory not found. Running build first...
    call build.bat
)

if "%1"=="" (
    echo Running with default CSV file: data/Sample_event_file.csv
    java -cp bin app.Main data/Sample_event_file.csv
) else (
    echo Running with CSV file: %1
    java -cp bin app.Main %1
)
