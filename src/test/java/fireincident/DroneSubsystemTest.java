package fireincident;

import model.Incident;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DroneSubsystemTest {

    private Scheduler scheduler;

    @Before
    public void setUp() {
        scheduler = new Scheduler();
    }

    @After
    public void tearDown() {
        scheduler.shutdown();
    }

    @Test
    public void testMultipleDronesHandleMultipleIncidents() throws Exception {
        CountDownLatch completionLatch = new CountDownLatch(2);
        Set<Integer> dispatchedDrones = ConcurrentHashMap.newKeySet();
        scheduler.addListener(new SchedulerListener() {
            @Override
            public void onIncidentQueued(Incident incident) {
            }

            @Override
            public void onIncidentDispatched(int droneId, Incident incident) {
                dispatchedDrones.add(droneId);
            }

            @Override
            public void onIncidentCompleted(int droneId, Incident incident) {
            }

            @Override
            public void onDroneStateChanged(int droneId, String state, Integer zoneId) {
            }

            @Override
            public void onLog(String message) {
            }
        });

        Incident incident1 = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        Incident incident2 = new Incident("14:10:00", 7, "FIRE_DETECTED", 20);
        scheduler.receiveIncident(incident1, completed -> completionLatch.countDown());
        scheduler.receiveIncident(incident2, completed -> completionLatch.countDown());

        Thread drone1 = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        Thread drone2 = new Thread(new DroneSubsystem(2, scheduler, 0.001), "Drone-2");
        drone1.start();
        drone2.start();

        assertTrue(completionLatch.await(10, TimeUnit.SECONDS));
        assertEquals(0, scheduler.getQueueSize());
        assertEquals(0, scheduler.getInProgressCount());
        assertEquals(2, dispatchedDrones.size());

        drone1.interrupt();
        drone2.interrupt();
        drone1.join(1000);
        drone2.join(1000);
    }
}
