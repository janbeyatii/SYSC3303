package fireincident;

import model.DroneTelemetry;
import model.Incident;

/**
 * Channel through which a drone communicates with the scheduler.
 * (Iteration 3 – Task 3: Independent Drone Execution only.)
 * Implementations may be in-process (for tests/single JVM) or UDP (distributed).
 */
public interface IDroneSchedulerChannel {
    /** Block until work is available; returns the assigned incident or null if interrupted. */
    Incident requestWork(int droneId);

    /** Request work from current zone with remaining agent (for multi-zone routing per spec). */
    default Incident requestWork(int droneId, int currentZone, int agentRemaining) {
        return requestWork(droneId);
    }

    /** Next incident in queue without removing it; null if queue empty. */
    Incident peekNextIncident();

    void reportArrival(int droneId, Incident incident);

    void reportCompletion(int droneId, Incident incident);

    void reportReturnToBase(int droneId);

    void updateDroneState(int droneId, String state, Integer zoneId);

    /** Full telemetry over UDP channel; default falls back to basic state only. */
    default void updateDroneTelemetry(DroneTelemetry t) {
        updateDroneState(t.droneId(), t.state(), t.zoneId());
    }
}
