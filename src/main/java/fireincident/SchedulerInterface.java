package fireincident;

import model.Incident;

/**
 * Interface implemented by the Scheduler. The Fire Incident Subsystem sends
 * incidents through this interface; the callback is invoked when the incident
 * has been completed (e.g. by a drone).
 */
public interface SchedulerInterface {
    void receiveIncident(Incident incident, IncidentCallback callback);
}
