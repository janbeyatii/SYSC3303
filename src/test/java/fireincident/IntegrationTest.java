package fireincident;

import model.Incident;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class IntegrationTest {

    @Test
    public void testFullSystemIntegration() throws InterruptedException {
        Scheduler scheduler = new Scheduler();
        CountDownLatch completionLatch = new CountDownLatch(3);

        // Start a drone
        Thread droneThread = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        droneThread.start();

        // Add incidents
        Incident incident1 = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        Incident incident2 = new Incident("14:10:00", 7, "FIRE_DETECTED", 20);
        Incident incident3 = new Incident("14:15:00", 5, "FIRE_DETECTED", 10);

        scheduler.receiveIncident(incident1, completed -> completionLatch.countDown());
        scheduler.receiveIncident(incident2, completed -> completionLatch.countDown());
        scheduler.receiveIncident(incident3, completed -> completionLatch.countDown());

        // Wait for all incidents to complete
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS));

        // Verify all incidents are completed
        assertEquals(0, scheduler.getQueueSize());
        assertEquals(0, scheduler.getInProgressCount());

        droneThread.interrupt();
        droneThread.join(1000);
    }
}