This is the Fire Incident Subsystem for our SYSC3303 project. It reads fire incident
data from a CSV file, converts each line into Incident objects, and sends them off
to the scheduler. It also receives notifications back when incidents are completed.

The subsystem reads through the CSV line by line (skipping the header), creates
Incident objects for each row, and forwards them to the scheduler interface.
The subsystem was extended to support fault injection through input files, 
allowing simulation of fault conditions such as drone failures and communication issues.

HOW TO RUN:

**1. Using Terminal**
Use .bat files
```bash
./build.bat    # compiles the code
./run.bat      # runs compiled code
./build_tests.bat   # compile tests
./run_tests.bat     # run all unit tests
./run_distributed.bat [incidentCsvPath]  # starts Scheduler, Drones, and Fire Incident as separate UDP processes
```

**2. Using IntelliJ IDEA:**
Run `Main.java`

**3. Three separate processes (per Iteration 3 spec):**
- **Process 1 – Scheduler**: Run `Main` or `SchedulerMain` (spawns drones from config, shows GUI)
- **Process 2 – Fire Incident**: When you click Start, the GUI spawns `FireIncidentMain` as a separate process
- **Process 3..N – Drones**: Spawned at startup by Main/SchedulerMain from config

**4. Config (data/config.properties):**
- numDrones: Number of drones at startup (default: 10)
- droneTimeScale: Simulation speed (0.01 = 100× faster)
- schedulerHost, schedulerPort: Where Fire Incident and drones connect

We are using the sample event CSV file provided to us from the course page. 

INPUT FILE FORMAT (supports both):
- Legacy comma-separated (CSV): Time,Zone ID,Event type,Severity
- Legacy whitespace-separated (per spec): time zoneId eventType severity
- Fault-aware comma-separated (CSV): Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID
- Fault-aware whitespace-separated: time zoneId eventType severity faultType faultTargetType faultTargetId

Example CSV:
Time,Zone ID,Event type,Severity
14:03:15,3,FIRE_DETECTED,High
14:10:00,7,DRONE_REQUEST,Moderate

Example whitespace: 14:03:15 3 FIRE_DETECTED High

Fault-aware CSV example:
Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID
14:03:15,3,FIRE_DETECTED,High,DRONE_STUCK,EVENT,14:03:15|3|FIRE_DETECTED
14:10:00,7,DRONE_REQUEST,Moderate,NOZZLE_JAM,DRONE,D2

Fault-aware whitespace example:
14:12:00 5 FIRE_DETECTED High PACKET_LOSS EVENT 14:12:00|5|FIRE_DETECTED

Fields:
* Time: When the incident happened (string)
* Zone ID: Which zone (integer)
* Event type: What happened (string, like "FIRE_DETECTED" or "DRONE_REQUEST")
* Severity: Amount of water/foam needed (Low=10 L, Moderate=20 L, High=30 L) Use words "High", "Moderate", "Low" or litres 10, 20, 30.
* Fault Type: Optional fault to inject. Valid values: NONE, DRONE_STUCK, NOZZLE_JAM, PACKET_LOSS, CORRUPTED_MESSAGE.
* Fault Target Type: Optional target scope for the fault. Valid values: NONE, EVENT, DRONE.
* Fault Target ID: Identifier of the target event or drone. Use NONE when no target is set.

Severity is stored as litres: High=30, Moderate=20, Low=10
Fault defaults for legacy 4-field input: Fault Type=NONE, Fault Target Type=NONE, Fault Target ID=NONE.

ZONE COORDINATES FILE (data/zones.csv):
Zones are rectangular shapes with coordinates in meters. Travel time and distance-based drone selection use this file.
Format: Zone ID,x1,y1,x2,y2 (header row, then one zone per line)
* Zone 0 = Base (where drones start and return)
* Zones 1-9 = Fire zones in a 3×3 grid
* Distance between zones = Euclidean distance between rectangle centers

## Tests

- **IncidentTest** – constructor/getters, toString, severity as litres (10, 20, 30)
- **SchedulerTest** – fire states (PENDING, ASSIGNED, COMPLETED), scheduler states (IDLE, HAS_PENDING, DRONE_BUSY), queue/in-progress counts,fault handling (soft vs hard faults), re-queue logic, and handling of UNAVAILABLE and OFFLINE drones
- **FireIncidentSubsystemTest** – reads CSV and sends to scheduler, severity as words or numbers, skips bad lines and empty files, parses fault-aware input (fault type, target type, target id), and handles mixed fault scenarios
- **DroneSubsystemTest** – drone states (
- IDLE, EN_ROUTE, EXTINGUISHING, RETURNING), partial agent use, multiple incidents in a row, fault handling including DRONE_STUCK and NOZZLE_JAM, state transitions (FAULTED, UNAVAILABLE, OFFLINE), incident re-queue and reassignment, listener notifications, and stability under packet loss and corrupted message scenarios
- **IntegrationTest** – full run with scheduler + drone + fire subsystem; multiple incidents finish in order and callbacks fire, using UDP communication between distributed processes

Test files used for Iteration 4 include:
- iter4_fault_drone_stuck_event.csv
- iter4_fault_nozzle_jam_drone.csv
- iter4_fault_packet_loss_event.csv
- iter4_fault_corrupted_message_drone.csv 
- iter4_fault_mixed.csv
