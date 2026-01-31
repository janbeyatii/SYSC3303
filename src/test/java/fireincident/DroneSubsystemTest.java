package fireincident;

import model.Incident;
import org.junit.Test;
import org.junit.Before;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class DroneSubsystemTest {

    private Scheduler scheduler;
    private CountDownLatch completionLatch;
    private AtomicInteger completionsReceived;

    @Before
    public void setUp() {
        scheduler = new Scheduler();
        completionLatch = new CountDownLatch(1);
        completionsReceived = new AtomicInteger(0);
    }

    @Test
    public void droneReceivesWorkAndReportsCompletion() throws InterruptedException {
        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        scheduler.receiveIncident(incident, completed -> {
            completionsReceived.incrementAndGet();
            completionLatch.countDown();
        });

        Thread droneThread = new Thread(new DroneSubsystem(99, scheduler, 0.001), "Drone-99");
        droneThread.start();

        assertTrue("Callback should be invoked when drone completes", completionLatch.await(15, TimeUnit.SECONDS));
        assertEquals(1, completionsReceived.get());

        droneThread.interrupt();
        droneThread.join(1000);
    }

    @Test
    public void schedulerDispatchesIncidentToDrone() throws InterruptedException {
        Incident incident = new Incident("14:10:00", 7, "DRONE_REQUEST", 20);
        scheduler.receiveIncident(incident, completed -> completionLatch.countDown());

        Thread droneThread = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        droneThread.start();

        assertTrue(completionLatch.await(15, TimeUnit.SECONDS));
        droneThread.interrupt();
        droneThread.join(1000);
    }
}
