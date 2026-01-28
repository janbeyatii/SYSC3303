package fireincident;
import model.Incident;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

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

    @Override
    public synchronized void receiveIncident(Incident incident, IncidentCallback callback) {
        queue.addLast(new Job(incident, callback));
        notifyAll();
    }
    public synchronized Incident requestWork(int droneId) {
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
        return job.incident;
    }
    public synchronized void reportCompletion(int droneId, Incident incident) {
        Job job = inProgressByZone.remove(incident.getZoneId());
        if (job != null && job.callback != null) {
            job.callback.onIncidentCompleted(job.incident);
        }
    }
}
