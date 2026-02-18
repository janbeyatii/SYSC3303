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

    public enum FireState { PENDING, ASSIGNED, COMPLETED }
    public enum SchedulerState { IDLE, HAS_PENDING, DRONE_BUSY, WAITING_FOR_INCIDENT }

    private SchedulerState schedulerState = SchedulerState.IDLE;
    private final Map<String, FireState> fireStates = new HashMap<>();

    //waiting incidents of FIFO queue
    private final LinkedList<Job> queue = new LinkedList<>();
    // track assigned incidents
    private final Map<Integer, Job> inProgressByZone = new HashMap<>();
    private final List<SchedulerListener> listeners = new ArrayList<>();
    private final Map<Integer, String> droneStateById = new HashMap<>();
    private final Map<Integer, Integer> droneZoneById = new HashMap<>();

    @Override
    public synchronized void receiveIncident(Incident incident, IncidentCallback callback) {
        queue.addLast(new Job(incident, callback));
        fireStates.put(incidentKey(incident), FireState.PENDING);
        if(!inProgressByZone.isEmpty()){
            schedulerState = SchedulerState.DRONE_BUSY;
        } else if(queue.isEmpty()){
            schedulerState = SchedulerState.IDLE;
        } else {
            schedulerState = SchedulerState.HAS_PENDING;
        }
        fireLog("[Scheduler] Queued incident: " + incident);
        fireIncidentQueued(incident);
        notifyAll();
    }

    /**
     * this function has the drone asks for next incident and it blocks until one is available
     */
    public synchronized Incident requestWork(int droneId) {
        updateDroneState(droneId, "IDLE", null);
        while(queue.isEmpty()){
            schedulerState = SchedulerState.WAITING_FOR_INCIDENT;
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Job job = queue.removeFirst();
        inProgressByZone.clear();
        inProgressByZone.put(job.incident.getZoneId(), job);
        fireStates.put(incidentKey(job.incident), FireState.ASSIGNED);
        schedulerState = SchedulerState.DRONE_BUSY;
        fireLog("[Scheduler] Dispatched incident to Drone " + droneId + ": " + job.incident);
        fireIncidentDispatched(droneId, job.incident);
        return job.incident;
    }

    /**
     * this function has the drone report completion and scheduler routes it back using
     * callback thats stored
     */
    public synchronized void reportCompletion(int droneId, Incident incident) {
        Job job = inProgressByZone.remove(incident.getZoneId());
        fireStates.put(incidentKey(incident), FireState.COMPLETED);
        fireLog("[Scheduler] Completion from Drone " + droneId + ": " + incident);
        fireIncidentCompleted(droneId, incident);
        if (job != null && job.callback != null) {
            job.callback.onIncidentCompleted(job.incident);
        }
        if(queue.isEmpty()){
            schedulerState = SchedulerState.IDLE;
        } else {
            schedulerState = SchedulerState.HAS_PENDING;
            notifyAll();
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
        return inProgressByZone.isEmpty() ? 0 : 1;
    }

    public synchronized FireState getFireState(Incident incident){
        return fireStates.get(incidentKey(incident));
    }

    public synchronized SchedulerState getSchedulerState(){
        return schedulerState;
    }

    private String incidentKey(Incident i){
        return i.getTime() + "|" + i.getZoneId() + "|" + i.getEventType();
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

