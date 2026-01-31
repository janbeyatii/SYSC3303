package fireincident;

import model.Incident;

/**
 * Callback used when the scheduler (or a drone) has finished handling an
 * incident. The Fire Incident Subsystem implements this to receive
 * completion notifications.
 */
public interface IncidentCallback {
    void onIncidentCompleted(Incident incident);
}
