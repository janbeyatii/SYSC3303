package fireincident;

import model.Incident;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SchedulerTest {

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
    public void testFireStateTransitions() {
        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        scheduler.receiveIncident(incident, null);

        assertEquals(Scheduler.FireState.PENDING, scheduler.getFireState(incident));

        scheduler.requestWork(1);
        assertEquals(Scheduler.FireState.ASSIGNED, scheduler.getFireState(incident));

        scheduler.reportCompletion(1, incident);
        assertEquals(Scheduler.FireState.COMPLETED, scheduler.getFireState(incident));
    }

    @Test
    public void testSchedulerStateMachine() {
        assertEquals(Scheduler.SchedulerState.IDLE, scheduler.getSchedulerState());

        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        scheduler.receiveIncident(incident, null);
        assertEquals(Scheduler.SchedulerState.HAS_PENDING, scheduler.getSchedulerState());

        scheduler.requestWork(1);
        assertEquals(Scheduler.SchedulerState.DRONE_BUSY, scheduler.getSchedulerState());

        scheduler.reportCompletion(1, incident);
        assertEquals(Scheduler.SchedulerState.IDLE, scheduler.getSchedulerState());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSchedulerSelectsClosestIdleDroneForZone() throws Exception {
        scheduler.updateDroneState(1, DroneState.IDLE.name(), 2);
        scheduler.updateDroneState(2, DroneState.IDLE.name(), 8);

        Field idleDronesField = Scheduler.class.getDeclaredField("idleDrones");
        idleDronesField.setAccessible(true);
        LinkedList<Integer> idleDrones = (LinkedList<Integer>) idleDronesField.get(scheduler);
        idleDrones.add(1);
        idleDrones.add(2);

        Method selectBestPushDrone = Scheduler.class.getDeclaredMethod("selectBestPushDrone", int.class);
        selectBestPushDrone.setAccessible(true);

        assertEquals(1, ((Integer) selectBestPushDrone.invoke(scheduler, 3)).intValue());
        assertEquals(2, ((Integer) selectBestPushDrone.invoke(scheduler, 9)).intValue());
    }
    @Test
    public void testHardFaultSetsDroneOffline() {
        scheduler.updateDroneState(1, DroneState.EN_ROUTE.name(), 3);

        scheduler.onDroneFaultDetected(1, "Nozzle jammed on Drone 1", true);

        assertEquals(DroneState.OFFLINE.name(), scheduler.getDroneState(1));
    }
    @Test
    public void testSoftFaultSetsDroneUnavailable() {
        scheduler.updateDroneState(1, DroneState.EN_ROUTE.name(), 3);

        scheduler.onDroneFaultDetected(1, "Drone 1 stuck mid-flight", false);

        assertEquals(DroneState.UNAVAILABLE.name(), scheduler.getDroneState(1));
    }
    @Test
    public void testUnavailableStateRequeuesInProgressIncident() throws Exception {
        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 20,
                "DRONE_STUCK", "EVENT", "14:03:15|3|FIRE_DETECTED");
        scheduler.receiveIncident(incident, null);

        // Drone 1 picks up the work
        Thread worker = new Thread(() -> scheduler.requestWork(1), "worker");
        worker.start();
        worker.join(2000);

        assertEquals(Scheduler.FireState.ASSIGNED, scheduler.getFireState(incident));
        assertEquals(0, scheduler.getQueueSize());

        // Trigger re-queue path via UNAVAILABLE state
        scheduler.updateDroneState(1, DroneState.UNAVAILABLE.name(), null);

        assertEquals(Scheduler.FireState.PENDING, scheduler.getFireState(incident));
        assertEquals(1, scheduler.getQueueSize());
        assertEquals(0, scheduler.getInProgressCount());
    }
    @Test
    public void testOfflineDroneNotDispatchedToNewIncident() throws InterruptedException {
        scheduler.updateDroneState(1, DroneState.IDLE.name(), 0);
        scheduler.onDroneFaultDetected(1, "Nozzle jammed", true);
        assertEquals(DroneState.OFFLINE.name(), scheduler.getDroneState(1));

        scheduler.updateDroneState(2, DroneState.IDLE.name(), 0);

        CountDownLatch dispatched = new CountDownLatch(1);
        AtomicReference<Integer> dispatchedTo = new AtomicReference<>(-1);

        scheduler.addListener(new NoOpListener() {
            @Override
            public void onIncidentDispatched(int droneId, Incident incident) {
                dispatchedTo.set(droneId);
                dispatched.countDown();
            }
        });

        Incident incident = new Incident("14:00:00", 1, "FIRE_DETECTED", 10);
        scheduler.receiveIncident(incident, null);
        Thread.sleep(200);
        if (dispatchedTo.get() != -1) {
            assertNotEquals("OFFLINE drone must not receive work", Integer.valueOf(1), dispatchedTo.get());
        }
        assertEquals(DroneState.OFFLINE.name(), scheduler.getDroneState(1));
    }
    @Test
    public void testUnavailableDroneNotDispatchedToNewIncident() throws InterruptedException {
        scheduler.updateDroneState(1, DroneState.IDLE.name(), 0);
        scheduler.onDroneFaultDetected(1, "Stuck mid-flight", false);
        assertEquals(DroneState.UNAVAILABLE.name(), scheduler.getDroneState(1));

        Incident incident = new Incident("14:00:00", 2, "FIRE_DETECTED", 10);
        scheduler.receiveIncident(incident, null);

        Thread.sleep(200);
        assertEquals(DroneState.UNAVAILABLE.name(), scheduler.getDroneState(1));
    }
    @Test
    public void testFaultNotifiesListeners() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> notifiedId = new AtomicReference<>(-1);
        AtomicReference<String> notifiedMsg = new AtomicReference<>("");

        scheduler.addListener(new NoOpListener() {
            @Override
            public void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault) {
                notifiedId.set(droneId);
                notifiedMsg.set(faultMessage);
                latch.countDown();
            }
        });

        scheduler.onDroneFaultDetected(3, "Nozzle stuck open", true);

        assertTrue("Listener not notified within 1s", latch.await(1, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(3), notifiedId.get());
        assertEquals("Nozzle stuck open", notifiedMsg.get());
    }
    @Test
    public void testTwoFaultsEachNotifyListener() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);

        scheduler.addListener(new NoOpListener() {
            @Override
            public void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault) {
                latch.countDown();
            }
        });

        scheduler.onDroneFaultDetected(1, "Stuck", false);
        scheduler.onDroneFaultDetected(2, "Nozzle jammed", true);

        assertTrue("Expected 2 fault notifications", latch.await(1, TimeUnit.SECONDS));
    }
    @Test
    public void testQueueAndInProgressZeroAfterNormalCompletion() throws Exception {
        Incident incident = new Incident("14:00:00", 1, "FIRE_DETECTED", 10);
        CountDownLatch latch = new CountDownLatch(1);
        scheduler.receiveIncident(incident, c -> latch.countDown());

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        assertTrue(latch.await(8, TimeUnit.SECONDS));
        waitForState(1, DroneState.IDLE.name(), 3000);

        assertEquals(0, scheduler.getQueueSize());
        assertEquals(0, scheduler.getInProgressCount());

        drone.interrupt();
        drone.join(1000);
    }
    @Test
    public void testSoftFaultInProgressDropsToZeroAndQueueRisesToOne() throws Exception {
        Incident incident = new Incident("14:05:00", 3, "FIRE_DETECTED", 20,
                "DRONE_STUCK", "EVENT", "14:05:00|3|FIRE_DETECTED");
        scheduler.receiveIncident(incident, null);

        Thread drone = new Thread(new DroneSubsystem(1, scheduler, 0.001), "Drone-1");
        drone.start();

        waitForState(1, DroneState.UNAVAILABLE.name(), 5000);

        assertEquals(0, scheduler.getInProgressCount());
        assertEquals(1, scheduler.getQueueSize());

        drone.join(1000);
    }
    private void waitForState(int droneId, String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(scheduler.getDroneState(droneId))) return;
            Thread.sleep(25);
        }
        assertEquals(expected, scheduler.getDroneState(droneId));
    }
//helpers
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

