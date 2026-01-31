package fireincident;

import model.Incident;
import org.junit.Test;
import org.junit.Before;

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

    @Test
    public void receiveIncidentIncreasesQueueSize() {
        assertEquals(0, scheduler.getQueueSize());
        scheduler.receiveIncident(new Incident("14:03:15", 3, "FIRE_DETECTED", 30), null);
        assertEquals(1, scheduler.getQueueSize());
        scheduler.receiveIncident(new Incident("14:10:00", 7, "DRONE_REQUEST", 20), null);
        assertEquals(2, scheduler.getQueueSize());
    }

    @Test
    public void requestWorkReturnsQueuedIncident() throws InterruptedException {
        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        scheduler.receiveIncident(incident, null);

        AtomicReference<Incident> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            Incident work = scheduler.requestWork(1);
            received.set(work);
            done.countDown();
        });
        worker.start();

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertSame(incident, received.get());
        assertEquals(0, scheduler.getQueueSize());
        assertEquals(1, scheduler.getInProgressCount());
    }

    @Test
    public void reportCompletionInvokesCallback() throws InterruptedException {
        Incident incident = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        CountDownLatch callbackFired = new CountDownLatch(1);
        AtomicReference<Incident> completed = new AtomicReference<>();
        IncidentCallback callback = completedIncident -> {
            completed.set(completedIncident);
            callbackFired.countDown();
        };

        scheduler.receiveIncident(incident, callback);
        Incident work = scheduler.requestWork(1);
        assertNotNull(work);
        scheduler.reportCompletion(1, incident);

        assertTrue(callbackFired.await(1, TimeUnit.SECONDS));
        assertSame(incident, completed.get());
        assertEquals(0, scheduler.getInProgressCount());
    }

    @Test
    public void getQueueSizeAndInProgressCount() {
        scheduler.receiveIncident(new Incident("14:03:15", 3, "FIRE_DETECTED", 30), null);
        assertEquals(1, scheduler.getQueueSize());
        assertEquals(0, scheduler.getInProgressCount());

        scheduler.requestWork(1);
        assertEquals(0, scheduler.getQueueSize());
        assertEquals(1, scheduler.getInProgressCount());

        scheduler.reportCompletion(1, new Incident("14:03:15", 3, "FIRE_DETECTED", 30));
        assertEquals(0, scheduler.getInProgressCount());
    }
}
