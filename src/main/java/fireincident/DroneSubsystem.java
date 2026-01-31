package fireincident;

import model.Incident;

public class DroneSubsystem implements Runnable {
    private static final double CRUISE_SPEED = 10.0; // m/s
    private static final int TAKEOFF_TIME = 8;       // seconds
    private static final int LANDING_TIME = 10;      // seconds
    private static final int ACCEL_TIME = 3;         // seconds
    private static final int DECEL_TIME = 3;         // seconds
    private static final double RELEASE_RATE = 190.0 / 60.0; // L/s

    private final int droneId;
    private final Scheduler scheduler;

    public DroneSubsystem(int droneId, Scheduler scheduler) {
        this.droneId = droneId;
        this.scheduler = scheduler;
    }

    @Override
    public void run() {
        scheduler.updateDroneState(droneId, "IDLE", null);
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = scheduler.requestWork(droneId);
            if (incident == null) break;

            System.out.println("[Drone " + droneId + "] Assigned incident: " + incident);

            try {
                // Simulate travel to the incident
                double travelTime = calculateTravelTime(incident.getZoneId());
                System.out.println("[Drone " + droneId + "] Traveling to incident. Time: " + travelTime + " seconds");
                scheduler.updateDroneState(droneId, "EN_ROUTE", incident.getZoneId());
                Thread.sleep((long) (travelTime * 1000));

                // Simulate extinguishing the fire
                double extinguishTime = calculateExtinguishTime(incident.getSeverity());
                System.out.println("[Drone " + droneId + "] Extinguishing fire. Time: " + extinguishTime + " seconds");
                scheduler.updateDroneState(droneId, "DROPPING_AGENT", incident.getZoneId());
                Thread.sleep((long) (extinguishTime * 1000));

                // Simulate return to base
                System.out.println("[Drone " + droneId + "] Returning to base.");
                scheduler.updateDroneState(droneId, "RETURNING", incident.getZoneId());
                Thread.sleep((long) (travelTime * 1000));

                scheduler.updateDroneState(droneId, "IDLE", null);

                // Notify the scheduler of completion
                scheduler.reportCompletion(droneId, incident);
                System.out.println("[Drone " + droneId + "] Incident completed: " + incident);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private double calculateTravelTime(int zoneId) {
        // Assume each zone is 100 meters away from the previous one
        double distance = zoneId * 100.0; // meters
        double cruiseTime = distance / CRUISE_SPEED; // time at cruise speed
        return TAKEOFF_TIME + ACCEL_TIME + cruiseTime + DECEL_TIME + LANDING_TIME;
    }

    private double calculateExtinguishTime(int litresRequired) {
        // Severity is stored as litres (Low=10, Moderate=20, High=30 per spec)
        return litresRequired / RELEASE_RATE; // time to release water (seconds)
    }
}