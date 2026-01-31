package fireincident;

import model.Incident;

/**
 * Stub implementation of {@link SchedulerInterface} for testing the Fire
 * Incident Subsystem in isolation. Receives an incident and immediately
 * invokes the callback; no real scheduling or drone interaction.
 */
public class TestScheduler implements SchedulerInterface {
    @Override
    public void receiveIncident(Incident incident, IncidentCallback callback) {
        System.out.println("[Test Scheduler] Received incident: " + incident);
        System.out.println("[Test Scheduler] Simulating incident completion.");
        if (callback != null) {
            callback.onIncidentCompleted(incident);
        }
    }
}
