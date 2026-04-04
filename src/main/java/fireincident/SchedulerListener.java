package fireincident;

import model.DroneTelemetry;
import model.Incident;
import model.SimulationMetricsReport;

/**
 * Listener for scheduler events (incident queued, dispatched, completed;
 * drone state changed; log messages). Used by the GUI to update the display.
 */
public interface SchedulerListener {
    void onIncidentQueued(Incident incident);
    void onIncidentDispatched(int droneId, Incident incident);
    void onIncidentCompleted(int droneId, Incident incident);
    void onDroneStateChanged(int droneId, String state, Integer zoneId);

    /** Rich drone snapshot (agent, battery, distance); default no-op for tests. */
    default void onDroneTelemetryUpdated(DroneTelemetry telemetry) {
    }

    void onLog(String message);
    /** Called when all incidents are done and all drones at base; scheduler is shutting down. */
    /**
     * @param isHardFault {@code true} if the drone is retired ({@code OFFLINE}), {@code false} for soft ({@code UNAVAILABLE})
     */
    void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault);
    void onSimulationComplete();

    /** Iteration 5: final (or best-effort) performance snapshot when the run ends. */
    default void onSimulationMetrics(SimulationMetricsReport report) {
    }

}
