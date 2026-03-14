package fireincident;

import model.Incident;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;

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

        Method selectBestDrone = Scheduler.class.getDeclaredMethod("selectBestDrone", int.class);
        selectBestDrone.setAccessible(true);

        assertEquals(1, ((Integer) selectBestDrone.invoke(scheduler, 3)).intValue());
        assertEquals(2, ((Integer) selectBestDrone.invoke(scheduler, 9)).intValue());
    }
}
