package fireincident;

import model.Incident;
import org.junit.Test;
import org.junit.Before;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class DroneSubsystemTest {

    private Scheduler scheduler;
    private CountDownLatch completionLatch;

    @Before
    public void setUp() {
        scheduler = new Scheduler();
        completionLatch = new CountDownLatch(1);
    }

    @Test
    public void testDroneStateTransitions() throws InterruptedException {
        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        scheduler.receiveIncident(incident, completed -> completionLatch.countDown());

        Thread droneThread = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        droneThread.start();

        // Wait for the incident to complete
        assertTrue(completionLatch.await(15, TimeUnit.SECONDS));

        // Verify state transitions
        assertEquals(Scheduler.SchedulerState.IDLE, scheduler.getSchedulerState()); // Compare with enum, not String
        droneThread.interrupt();
        droneThread.join(1000);
    }

    @Test
    public void testPartialAgentUsage() throws InterruptedException {
        Incident incident1 = new Incident("14:03:15", 3, "FIRE_DETECTED", 80);
        Incident incident2 = new Incident("14:10:00", 4, "FIRE_DETECTED", 50);

        CountDownLatch latch = new CountDownLatch(2); // Adjust latch for two incidents
        scheduler.receiveIncident(incident1, completed -> latch.countDown());
        scheduler.receiveIncident(incident2, completed -> latch.countDown());

        Thread droneThread = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        droneThread.start();

        // Wait for both incidents to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS));

        // Verify that the drone handled partial agent usage
        assertEquals(0, scheduler.getQueueSize());
        droneThread.interrupt();
        droneThread.join(1000);
    }
}