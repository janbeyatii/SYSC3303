package fireincident;

import model.Incident;
import org.junit.Test;
import org.junit.Before;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class SchedulerTest {

    private Scheduler scheduler;

    @Before
    public void setUp() {
        scheduler = new Scheduler();
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
}