This is the Fire Incident Subsystem for our SYSC3303 project. It reads fire incident
data from a CSV file, converts each line into Incident objects, and sends them off
to the scheduler. It also receives notifications back when incidents are completed.

The subsystem reads through the CSV line by line (skipping the header), creates
Incident objects for each row, and forwards them to the scheduler interface.

HOW TO RUN:
1. using VSCode
   ./build.bat (compiles the code)
   ./run.bat (runs compiled code)

2. using InteliJ IDEA
   run 'Main.java'

3. We are using the sample event CSV file provided to us from the course page. 

CSV FILE FORMAT:
The CSV should look like this:
Time,Zone ID,Event type,Severity
14:03:15,3,FIRE_DETECTED,High
14:10:00,7,DRONE_REQUEST,Moderate

Fields:
* Time: When the incident happened (string)
* Zone ID: Which zone (integer)
* Event type: What happened (string, like "FIRE_DETECTED" or "DRONE_REQUEST")
* Severity: How bad it is - can be a number (1-5) or word like "High", "Moderate", "Low"

The severity gets converted: High=5, Moderate=3, Low=1

ITERATION 1 STATUS:

Jan
* CSV File Reading: Reads fire incidents from CSV file
* Line Parsing: Parses each CSV line into structured data
*  Data Objects: Creates Incident objects with time, zoneId, eventType, severity
* Sending to Scheduler: Sends incident objects to scheduler via SchedulerInterface
* Completion Notifications: Receives callback notifications when scheduler completes incidents
* Input Validation: Handles header rows, empty lines, and parsing errors

Other Components:
------------------------------------
* Scheduler Subsystem: Only a TestScheduler exists for testing communication. This is a placeholder to make the Fire Incident Subsystem work without the real scheduler.