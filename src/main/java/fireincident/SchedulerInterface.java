package fireincident;

import model.Incident;

/**
 * Interface that scheduler needs to implement.
 * The Fire Incident Subsystem uses this to send incidents to scheduler.
 * The callback lets the scheduler notify when it's done.
 */
public interface SchedulerInterface {
    void receiveIncident(Incident incident, IncidentCallback callback);
}
