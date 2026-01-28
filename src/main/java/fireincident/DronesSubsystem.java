package fireincident;
import model.Incident;

public class DroneSubsystem implements Runnable {
    private final int droneId;
    private final Scheduler scheduler;
    public DroneSubsystem(int droneId, Scheduler scheduler) {
        this.droneId = droneId;
        this.scheduler = scheduler;
    }
    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            Incident incident = scheduler.requestWork(droneId);
            if (incident == null) break;
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            scheduler.reportCompletion(droneId, incident);
        }
    }
}

