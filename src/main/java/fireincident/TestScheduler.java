package fireincident;

import model.Incident;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub implementation of {@link SchedulerInterface} for testing the Fire
 * Incident Subsystem in isolation. Receives an incident and immediately
 * invokes the callback; no real scheduling or drone interaction.
 */
public class TestScheduler implements SchedulerInterface {
    @Override
    public void receiveIncident(Incident incident, IncidentCallback callback){
        System.out.println("[Test Scheduler] Received incident: " + incident);
        System.out.println("[Test Scheduler] Simulating incident completion.");
        if (callback != null){
            callback.onIncidentCompleted(incident);
        }
    }

    public static void main(String[] args) throws Exception{
        Scheduler scheduler = new Scheduler();

        List<Incident> completed = new ArrayList<>();
        IncidentCallback cb = incident -> {
            System.out.println("[TEST] Callback completed: " + incident);
            completed.add(incident);
        };

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        Incident i1 = new Incident("00:00:01", 3, "FIRE", 10);
        Incident i2 = new Incident("00:00:02", 1, "FIRE", 20);
        Incident i3 = new Incident("00:00:03", 2, "FIRE", 30);

        scheduler.receiveIncident(i1, cb);
        scheduler.receiveIncident(i2, cb);
        scheduler.receiveIncident(i3, cb);

        if(scheduler.getFireState(i1) != Scheduler.FireState.PENDING) throw new RuntimeException("i1 not PENDING");
        if(scheduler.getFireState(i2) != Scheduler.FireState.PENDING) throw new RuntimeException("i2 not PENDING");
        if(scheduler.getFireState(i3) != Scheduler.FireState.PENDING) throw new RuntimeException("i3 not PENDING");

        long start = System.currentTimeMillis();
        while(completed.size() < 3 && (System.currentTimeMillis() - start) < 8000){
            Thread.sleep(50);
        }

        if(completed.size() != 3) throw new RuntimeException("not all incidents completed in time");

        if(completed.get(0) != i1) throw new RuntimeException("not FIFO first");
        if(completed.get(1) != i2) throw new RuntimeException("not FIFO second");
        if(completed.get(2) != i3) throw new RuntimeException("not FIFO third");

        if(scheduler.getFireState(i1) != Scheduler.FireState.COMPLETED) throw new RuntimeException("i1 not COMPLETED");
        if(scheduler.getFireState(i2) != Scheduler.FireState.COMPLETED) throw new RuntimeException("i2 not COMPLETED");
        if(scheduler.getFireState(i3) != Scheduler.FireState.COMPLETED) throw new RuntimeException("i3 not COMPLETED");

        System.out.println("[TEST] PASS");
        drone.interrupt();
    }
}

