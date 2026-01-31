package fireincident;

import model.Incident;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Scheduler (server): receives incidents from the Fire Incident Subsystem,
 * queues them, and dispatches work to drones via {@link #requestWork(int)}.
 * When a drone reports completion, the scheduler notifies the original
 * {@link IncidentCallback} and listeners.
 */
public class Scheduler implements SchedulerInterface {
    private static class Job {
        final Incident incident;
        final IncidentCallback callback;
        Job(Incident incident, IncidentCallback callback) {
            this.incident = incident;
            this.callback = callback;
        }
    }
    private final LinkedList<Job> queue = new LinkedList<>();
    private final Map<Integer, Job> inProgressByZone = new HashMap<>();
    private final List<SchedulerListener> listeners = new ArrayList<>();
    private final Map<Integer, String> droneStateById = new HashMap<>();
    private final Map<Integer, Integer> droneZoneById = new HashMap<>();

    @Override
    public synchronized void receiveIncident(Incident incident, IncidentCallback callback) {
        queue.addLast(new Job(incident, callback));
        fireLog("[Scheduler] Queued incident: " + incident);
        fireIncidentQueued(incident);
        notifyAll();
    }
    public synchronized Incident requestWork(int droneId) {
        updateDroneState(droneId, "IDLE", null);
        while (queue.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Job job = queue.removeFirst();
        inProgressByZone.put(job.incident.getZoneId(), job);
        fireLog("[Scheduler] Dispatched incident to Drone " + droneId + ": " + job.incident);
        fireIncidentDispatched(droneId, job.incident);
        return job.incident;
    }
    public synchronized void reportCompletion(int droneId, Incident incident) {
        Job job = inProgressByZone.remove(incident.getZoneId());
        fireLog("[Scheduler] Completion from Drone " + droneId + ": " + incident);
        fireIncidentCompleted(droneId, incident);
        if (job != null && job.callback != null) {
            job.callback.onIncidentCompleted(job.incident);
        }
    }
    public synchronized void addListener(SchedulerListener listener){
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    public synchronized void updateDroneState(int droneId, String state, Integer zoneId){
        if (state != null) droneStateById.put(droneId, state);
        if (zoneId != null) droneZoneById.put(droneId, zoneId);

        fireDroneStateChanged(droneId, state, zoneId);
    }

    public synchronized int getQueueSize(){
        return queue.size();
    }
    public synchronized int getInProgressCount(){
        return inProgressByZone.size();
    }

    //Helper classes
    private void fireIncidentQueued(Incident incident){
        for (SchedulerListener l : listeners) l.onIncidentQueued(incident);
    }

    private void fireIncidentDispatched(int droneId, Incident incident){
        for (SchedulerListener l : listeners) l.onIncidentDispatched(droneId, incident);
    }

    private void fireIncidentCompleted(int droneId, Incident incident){
        for (SchedulerListener l : listeners) l.onIncidentCompleted(droneId, incident);
    }

    private void fireDroneStateChanged(int droneId, String state, Integer zoneId){
        for (SchedulerListener l : listeners) l.onDroneStateChanged(droneId, state, zoneId);
    }

    private void fireLog(String message){
        for (SchedulerListener l : listeners) l.onLog(message);
    }
}
