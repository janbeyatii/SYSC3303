package fireincident;

import model.Incident;

/**
 * In-process implementation of {@link IDroneSchedulerChannel} that delegates
 * to a {@link Scheduler}. Used for single-JVM runs and tests.
 */
public class InProcessDroneChannel implements IDroneSchedulerChannel {
    private final Scheduler scheduler;

    public InProcessDroneChannel(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Incident requestWork(int droneId) {
        return scheduler.requestWork(droneId);
    }
    @Override
    public Incident peekNextIncident() {
        return scheduler.peekNextIncident();
    }

    @Override
    public void reportArrival(int droneId, Incident incident) {
        scheduler.reportArrival(droneId, incident);
    }

    @Override
    public void reportCompletion(int droneId, Incident incident) {
        scheduler.reportCompletion(droneId, incident);
    }

    @Override
    public void reportReturnToBase(int droneId) {
        scheduler.reportReturnToBase(droneId);
    }

    @Override
    public void updateDroneState(int droneId, String state, Integer zoneId) {
        scheduler.updateDroneState(droneId, state, zoneId);
    }

}
