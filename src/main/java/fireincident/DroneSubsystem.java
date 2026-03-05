package fireincident;

import model.Incident;

/**
 * Drone subsystem (client): runs in its own thread, requests work from the
 * scheduler via {@link IDroneSchedulerChannel}, simulates travel, dropping agent,
 * and return, then reports completion. The channel may be in-process or UDP.
 */
public class DroneSubsystem implements Runnable {
    private static final double CRUISE_SPEED = 10.0; // m/s
    private static final int TAKEOFF_TIME = 8;       // seconds
    private static final int LANDING_TIME = 10;      // seconds
    private static final int ACCEL_TIME = 3;         // seconds
    private static final int DECEL_TIME = 3;         // seconds
    private static final double NOZZLE_OPEN_TIME = 0.5; // seconds 
    private static final double NOZZLE_CLOSE_TIME = 0.5; // seconds 
    private static final double RELEASE_RATE = 190.0 / 60.0; // L/s
    private static final double MAX_AGENT = 100;
    private static final double MAX_BATTERY = 900;
    private int agentRemaining = (int) MAX_AGENT;
    private double batteryRemaining = MAX_BATTERY;
    
    private final int droneId;
    private final IDroneSchedulerChannel channel;
    /** Scale factor for sleep times (1.0 = real time; use &lt; 1.0 in tests to run fast). */
    private final double timeScale;

    public DroneSubsystem(int droneId, IDroneSchedulerChannel channel) {
        this(droneId, channel, 1.0);
    }

    /**
     * Constructor for tests: use timeScale &lt; 1.0 so simulation completes quickly.
     * @param timeScale 1.0 = real time; e.g. 0.001 for tests
     */
    public DroneSubsystem(int droneId, IDroneSchedulerChannel channel, double timeScale) {
        this.droneId = droneId;
        this.channel = channel;
        this.timeScale = timeScale <= 0 ? 1.0 : timeScale;
    }

    /** Convenience constructor using in-process channel (e.g. for existing tests). */
    public DroneSubsystem(int droneId, Scheduler scheduler) {
        this(droneId, new InProcessDroneChannel(scheduler), 1.0);
    }

    /** Convenience constructor using in-process channel (e.g. for existing tests). */
    public DroneSubsystem(int droneId, Scheduler scheduler, double timeScale) {
        this(droneId, new InProcessDroneChannel(scheduler), timeScale);
    }

    @Override
    public void run() {
        channel.updateDroneState(droneId, DroneState.IDLE.name(), null);
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = channel.requestWork(droneId);
            if (incident == null) break;

            System.out.println("[Drone " + droneId + "] Assigned incident: " + incident);

            try {
                // Simulate travel to the incident
                double travelTime = calculateTravelTime(incident.getZoneId());
                System.out.println("[Drone " + droneId + "] Traveling to incident. Time: " + travelTime + " seconds");
                channel.updateDroneState(droneId, DroneState.EN_ROUTE.name(), incident.getZoneId());
          
                useBattery(travelTime);
                sleepSeconds(travelTime);
                channel.reportArrival(droneId, incident);
                
                // Simulate extinguishing the fire
                int litresNeeded = incident.getSeverity();               
                int litresUsed = Math.min(litresNeeded, agentRemaining);   
                agentRemaining -= litresUsed;                             
                double extinguishTime = calculateExtinguishTime(litresUsed);
                System.out.println("[Drone " + droneId + "] Extinguishing fire. Used: "
                        + litresUsed + "L. Time:" + extinguishTime + "seconds");
                channel.updateDroneState(droneId, DroneState.EXTINGUISHING.name(), incident.getZoneId());
                
                useBattery(extinguishTime);
                sleepSeconds(extinguishTime);

                channel.reportCompletion(droneId, incident);
                System.out.println("[Drone " + droneId + "] Incident completed: " + incident);

                // If there is another task in the queue we can service (enough agent and battery), go there next; else return to base.
                Incident next = channel.peekNextIncident();
                if (next != null && canServiceWithCurrentCapacity(next)) {
                    System.out.println("[Drone " + droneId + "] Continuing to next zone (agent=" + agentRemaining + "L, battery=" + (int) batteryRemaining + "s)");
                    // Don't return to base; loop will call requestWork() and get 'next'
                } else {
                    // No serviceable task in queue: return to base and refill
                    double returnTime = calculateTravelTime(incident.getZoneId());
                    System.out.println("[Drone " + droneId + "] Returning to base. Time: " + returnTime + " seconds");
                    channel.updateDroneState(droneId, DroneState.RETURNING.name(), null);
                    useBattery(returnTime);
                    sleepSeconds(returnTime);
                    channel.reportReturnToBase(droneId);
                    agentRemaining = (int) MAX_AGENT;
                    batteryRemaining = MAX_BATTERY;
                    channel.updateDroneState(droneId, DroneState.IDLE.name(), null);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * True if this drone has enough agent and battery to service the given incident
     * (travel to zone + extinguish + return to base) without refilling.
     */
    private boolean canServiceWithCurrentCapacity(Incident next) {
        if (agentRemaining < next.getSeverity()) return false;
        double toZone = calculateTravelTime(next.getZoneId());
        double extinguish = calculateExtinguishTime(next.getSeverity());
        double backToBase = calculateTravelTime(next.getZoneId());
        return batteryRemaining >= toZone + extinguish + backToBase;
    }

    private double calculateTravelTime(int zoneId) {
        // Assume each zone is 100 meters away from the previous one
        double distance = zoneId * 100.0; // meters
        double cruiseTime = distance / CRUISE_SPEED; // time at cruise speed
        return TAKEOFF_TIME + ACCEL_TIME + cruiseTime + DECEL_TIME + LANDING_TIME;
    }

    private double calculateExtinguishTime(int litresUsed) {
        // Severity is stored as litres (Low=10, Moderate=20, High=30 per spec)
        return NOZZLE_OPEN_TIME + (litresUsed / RELEASE_RATE) + NOZZLE_CLOSE_TIME;
    }

    private void useBattery(double seconds){
        batteryRemaining = Math.max(0, batteryRemaining - seconds);   
    }

    private void sleepSeconds(double seconds) throws InterruptedException {
        long ms = Math.max(1, (long) (seconds * 1000 * timeScale));
        Thread.sleep(ms);
    }
}
