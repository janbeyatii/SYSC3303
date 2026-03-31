package fireincident;

import model.Incident;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

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

            @Override
            public void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault) {

            }

            @Override
            public void onSimulationComplete() {
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

    @Test
    public void testDroneStuckFaultMarksDroneUnavailableAndRequeuesIncident() throws Exception {
        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 30,
                "DRONE_STUCK", "EVENT", "14:03:15|3|FIRE_DETECTED");
        scheduler.receiveIncident(incident, null);

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        waitForDroneState(1, DroneState.UNAVAILABLE.name(), 15000);

        assertEquals(DroneState.UNAVAILABLE.name(), scheduler.getDroneState(1));
        assertEquals(1, scheduler.getQueueSize());
        assertEquals(0, scheduler.getInProgressCount());
        assertEquals(Scheduler.FireState.PENDING, scheduler.getFireState(incident));

        drone.join(1000);
    }

    @Test
    public void testHardFaultShutsDownDroneAndAnotherDroneCompletesIncident() throws Exception {
        CountDownLatch completionLatch = new CountDownLatch(1);
        Incident incident = new Incident("14:05:30", 2, "FIRE_DETECTED", 30,
                "NOZZLE_JAM", "DRONE", "D1");
        scheduler.receiveIncident(incident, completed -> completionLatch.countDown());

        Thread drone1 = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        Thread drone2 = new Thread(new DroneSubsystem(2, scheduler, 0.001), "Drone-2");
        drone1.start();
        waitForDroneState(1, DroneState.OFFLINE.name(), 3000);
        drone2.start();

        assertTrue(completionLatch.await(5, TimeUnit.SECONDS));
        waitForDroneState(2, DroneState.IDLE.name(), 3000);
        assertEquals(DroneState.OFFLINE.name(), scheduler.getDroneState(1));
        assertEquals(DroneState.IDLE.name(), scheduler.getDroneState(2));
        assertEquals(Scheduler.FireState.COMPLETED, scheduler.getFireState(incident));

        drone1.join(1000);
        drone2.interrupt();
        drone2.join(1000);
    }

    @Test
    public void testDroneTargetedStuckFaultDuringReturnLeavesCompletedIncidentAndUnavailableDrone() throws Exception {
        CountDownLatch completionLatch = new CountDownLatch(1);
        Incident incident = new Incident("14:10:00", 1, "FIRE_DETECTED", 10,
                "DRONE_STUCK", "DRONE", "D1");
        scheduler.receiveIncident(incident, completed -> completionLatch.countDown());

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        assertTrue(completionLatch.await(5, TimeUnit.SECONDS));
        waitForDroneState(1, DroneState.UNAVAILABLE.name(), 3000);

        assertEquals(Scheduler.FireState.COMPLETED, scheduler.getFireState(incident));
        assertEquals(DroneState.UNAVAILABLE.name(), scheduler.getDroneState(1));

        drone.join(1000);
    }
    @Test
    public void testDroneStuckEventFault_stateSequenceIncludesFaulted() throws Exception {
        List<String> stateHistory = new ArrayList<>();

        scheduler.addListener(new NoOpListener() {
            @Override
            public void onDroneStateChanged(int droneId, String state, Integer zoneId) {
                if (droneId == 1) stateHistory.add(state);
            }
        });

        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 20,
                "DRONE_STUCK", "EVENT", "14:03:15|3|FIRE_DETECTED");
        scheduler.receiveIncident(incident, null);

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        waitForDroneState(1, DroneState.UNAVAILABLE.name(), 5000);


        assertTrue("EN_ROUTE state missing from history: " + stateHistory,
                stateHistory.contains(DroneState.EN_ROUTE.name()));
        assertTrue("FAULTED state missing from history: " + stateHistory,
                stateHistory.contains(DroneState.FAULTED.name()));
        assertTrue("UNAVAILABLE state missing from history: " + stateHistory,
                stateHistory.contains(DroneState.UNAVAILABLE.name()));

        assertTrue("UNAVAILABLE must appear after FAULTED",
                stateHistory.lastIndexOf(DroneState.UNAVAILABLE.name()) >
                        stateHistory.lastIndexOf(DroneState.FAULTED.name()));

        drone.join(1000);
    }
    @Test
    public void testNozzleJamHardFault_stateSequenceIncludesExtinguishingAndFaulted() throws Exception {
        List<String> stateHistory = new ArrayList<>();

        scheduler.addListener(new NoOpListener() {
            @Override
            public void onDroneStateChanged(int droneId, String state, Integer zoneId) {
                if (droneId == 1) stateHistory.add(state);
            }
        });

        Incident incident = new Incident("14:05:30", 2, "FIRE_DETECTED", 30,
                "NOZZLE_JAM", "DRONE", "D1");
        scheduler.receiveIncident(incident, null);

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        waitForDroneState(1, DroneState.OFFLINE.name(), 5000);

        assertTrue("EXTINGUISHING missing: " + stateHistory,
                stateHistory.contains(DroneState.EXTINGUISHING.name()));
        assertTrue("FAULTED missing: " + stateHistory,
                stateHistory.contains(DroneState.FAULTED.name()));
        assertTrue("OFFLINE missing: " + stateHistory,
                stateHistory.contains(DroneState.OFFLINE.name()));

        drone.join(1000);
    }
    @Test
    public void testDroneTargetedFault_doesNotAffectOtherDrone() throws Exception {
        CountDownLatch completionLatch = new CountDownLatch(1);

        // Fault targets D1 — only Drone 2 is started, so it should complete normally
        Incident incident = new Incident("14:05:30", 2, "FIRE_DETECTED", 30,
                "NOZZLE_JAM", "DRONE", "D1");
        scheduler.receiveIncident(incident, c -> completionLatch.countDown());

        Thread drone2 = new Thread(new DroneSubsystem(2, scheduler, 0.001), "Drone-2");
        drone2.start();

        assertTrue("Drone 2 should complete incident unaffected by D1 fault tag",
                completionLatch.await(8, TimeUnit.SECONDS));

        waitForDroneState(2, DroneState.IDLE.name(), 3000);
        assertEquals(DroneState.IDLE.name(), scheduler.getDroneState(2));
        assertEquals(Scheduler.FireState.COMPLETED, scheduler.getFireState(incident));

        drone2.interrupt();
        drone2.join(1000);
    }
    @Test
    public void testDroneStuckReturnFault_countsZeroAfterCompletion() throws Exception {
        CountDownLatch completionLatch = new CountDownLatch(1);
        Incident incident = new Incident("14:10:00", 1, "FIRE_DETECTED", 10,
                "DRONE_STUCK", "DRONE", "D1");
        scheduler.receiveIncident(incident, c -> completionLatch.countDown());

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        assertTrue(completionLatch.await(8, TimeUnit.SECONDS));
        waitForDroneState(1, DroneState.UNAVAILABLE.name(), 5000);

        assertEquals(0, scheduler.getQueueSize());
        assertEquals(0, scheduler.getInProgressCount());

        drone.join(1000);
    }
    @Test
    public void testNozzleJamHardFault_listenerNotified() throws Exception {
        CountDownLatch faultLatch = new CountDownLatch(1);
        AtomicReference<Integer> faultedDroneId = new AtomicReference<>(-1);

        scheduler.addListener(new NoOpListener() {
            @Override
            public void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault) {
                faultedDroneId.set(droneId);
                faultLatch.countDown();
            }
        });

        Incident incident = new Incident("14:05:30", 2, "FIRE_DETECTED", 30,
                "NOZZLE_JAM", "DRONE", "D1");
        scheduler.receiveIncident(incident, null);

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        assertTrue("onDroneFaultDetected not called within timeout",
                faultLatch.await(6, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(1), faultedDroneId.get());

        drone.join(1000);
    }
    @Test
    public void testDroneStuckSoftFault_listenerNotified() throws Exception {
        CountDownLatch faultLatch = new CountDownLatch(1);
        AtomicReference<Integer> faultedDroneId = new AtomicReference<>(-1);

        scheduler.addListener(new NoOpListener() {
            @Override
            public void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault) {
                faultedDroneId.set(droneId);
                faultLatch.countDown();
            }
        });

        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 30,
                "DRONE_STUCK", "EVENT", "14:03:15|3|FIRE_DETECTED");
        scheduler.receiveIncident(incident, null);

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        assertTrue("onDroneFaultDetected not called for soft fault",
                faultLatch.await(6, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(1), faultedDroneId.get());

        drone.join(1000);
    }
    @Test
    public void testPacketLossFault_doesNotCrashSystem() throws Exception {
        Incident incident = new Incident("14:07:00", 3, "FIRE_DETECTED", 20,
                "PACKET_LOSS", "EVENT", "14:07:00|3|FIRE_DETECTED");

        scheduler.receiveIncident(incident, null);

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();
        Thread.sleep(2000);
        assertNotNull(scheduler.getSchedulerState());

        drone.interrupt();
        drone.join(1000);
    }
    @Test
    public void testCorruptedMessageFault_doesNotCrashSystem() throws Exception {
        Incident incident = new Incident("14:09:15", 4, "FIRE_DETECTED", 30,
                "CORRUPTED_MESSAGE", "DRONE", "D1");

        scheduler.receiveIncident(incident, null);

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        Thread.sleep(2000);

        assertNotNull(scheduler.getSchedulerState());

        drone.interrupt();
        drone.join(1000);
    }
    private void waitForDroneState(int droneId, String expectedState, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (expectedState.equals(scheduler.getDroneState(droneId))) return;
            Thread.sleep(25);
        }
        assertEquals(expectedState, scheduler.getDroneState(droneId));
    }
    private static abstract class NoOpListener implements SchedulerListener {
        @Override public void onIncidentQueued(Incident i) {}
        @Override public void onIncidentDispatched(int d, Incident i) {}
        @Override public void onIncidentCompleted(int d, Incident i) {}
        @Override public void onDroneStateChanged(int d, String s, Integer z) {}
        @Override public void onLog(String m) {}
        @Override public void onDroneFaultDetected(int d, String m, boolean h) {}
        @Override public void onSimulationComplete() {}
    }
}
