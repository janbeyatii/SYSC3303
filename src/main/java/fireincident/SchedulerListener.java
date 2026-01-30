package fireincident;
import model.Incident;

public interface SchedulerListener{
    void onIncidentQueued(Incident incident);
    void onIncidentDispatched(int droneId, Incident incident);
    void onIncidentCompleted(int droneId, Incident incident);
    void onDroneStateChanged(int droneId, String state, Integer zoneId);
    void onLog(String message);
}
