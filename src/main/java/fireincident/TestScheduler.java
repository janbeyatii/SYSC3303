package fireincident;

import model.Incident;

/**
 * Test version of scheduler for Iteration 1.
 * Just prints what it receives and immediately calls back to say it's done.
 * This lets us test Fire Incident Subsystem without waiting for real scheduler.
 * Will be replaced with actual scheduling logic in later iterations.
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
