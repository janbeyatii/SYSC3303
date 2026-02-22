This is the Fire Incident Subsystem for our SYSC3303 project. It reads fire incident
data from a CSV file, converts each line into Incident objects, and sends them off
to the scheduler. It also receives notifications back when incidents are completed.

The subsystem reads through the CSV line by line (skipping the header), creates
Incident objects for each row, and forwards them to the scheduler interface.

HOW TO RUN:

**1. Using Terminal**
Use .bat files
```bash
./build.bat    # compiles the code
./run.bat      # runs compiled code
./build_tests.bat   # compile tests
./run_tests.bat     # run all unit tests
```

**2. Using IntelliJ IDEA:**
Run `Main.java`

4. We are using the sample event CSV file provided to us from the course page. 

CSV FILE FORMAT:
The CSV should look like this:
Time,Zone ID,Event type,Severity
14:03:15,3,FIRE_DETECTED,High
14:10:00,7,DRONE_REQUEST,Moderate

Fields:
* Time: When the incident happened (string)
* Zone ID: Which zone (integer)
* Event type: What happened (string, like "FIRE_DETECTED" or "DRONE_REQUEST")
* Severity: Amount of water/foam needed (Low=10 L, Moderate=20 L, High=30 L) Use words "High", "Moderate", "Low" or litres 10, 20, 30.

Severity is stored as litres: High=30, Moderate=20, Low=10

## Tests

- **IncidentTest** – constructor/getters, toString, severity as litres (10, 20, 30)
- **SchedulerTest** – fire states (PENDING, ASSIGNED, COMPLETED), scheduler states (IDLE, HAS_PENDING, DRONE_BUSY), queue/in-progress counts
- **FireIncidentSubsystemTest** – reads CSV and sends to scheduler, severity as words or numbers, skips bad lines and empty files
- **DroneSubsystemTest** – drone states (IDLE, EN_ROUTE, EXTINGUISHING, RETURNING), partial agent use, multiple incidents in a row
- **IntegrationTest** – full run with scheduler + drone + fire subsystem; multiple incidents finish in order and callbacks fire