package fireincident;

import model.Incident;

/**
 * Listener for scheduler events (incident queued, dispatched, completed;
 * drone state changed; log messages). Used by the GUI to update the display.
 */
public interface SchedulerListener {
    void onIncidentQueued(Incident incident);
    void onIncidentDispatched(int droneId, Incident incident);
    void onIncidentCompleted(int droneId, Incident incident);
    void onDroneStateChanged(int droneId, String state, Integer zoneId);
    void onLog(String message);
}
