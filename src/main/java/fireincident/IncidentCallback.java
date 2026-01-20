package fireincident;

import model.Incident;

/**
 * Interface for getting notified when scheduler finishes handling incident.
 */
public interface IncidentCallback {
    void onIncidentCompleted(Incident incident);
}
