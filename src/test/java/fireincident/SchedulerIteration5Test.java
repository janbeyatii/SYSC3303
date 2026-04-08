package fireincident;

import model.Incident;
import model.SimulationMetricsReport;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class SchedulerIteration5Test {

    private Scheduler scheduler;

    @Before
    public void setUp() {
        scheduler = new Scheduler(false); // In-process mode (no UDP)
    }

    @Test
    public void testIncidentQueueingAndCompletion() {
        Incident incident1 = new Incident("2023-10-01T10:00:00", 1, "FIRE_DETECTED", 10);
        Incident incident2 = new Incident("2023-10-01T10:05:00", 2, "FIRE_DETECTED", 20);

        scheduler.receiveIncident(incident1, null);
        scheduler.receiveIncident(incident2, null);

        assertEquals("Both incidents should be queued.", 2, scheduler.getQueueSize());

        scheduler.requestWork(1); // Drone 1 handles incident1
        assertEquals("One incident should remain in the queue.", 1, scheduler.getQueueSize());

        scheduler.reportCompletion(1, incident1);
        assertEquals("One incident should still be queued after completing incident1.", 1, scheduler.getQueueSize());
        scheduler.requestWork(2); // Drone 2 handles incident2
        scheduler.reportCompletion(2, incident2);
        assertEquals("All incidents should be completed.", 0, scheduler.getQueueSize());
    }

    @Test
    public void testDroneStateUpdates() {
        scheduler.updateDroneState(1, DroneState.IDLE.name(), 0);
        scheduler.updateDroneState(2, DroneState.EN_ROUTE.name(), 1);

        assertEquals("Drone 1 should be IDLE.", DroneState.IDLE.name(), scheduler.getDroneState(1));
        assertEquals("Drone 2 should be EN_ROUTE.", DroneState.EN_ROUTE.name(), scheduler.getDroneState(2));
    }

    @Test
    public void testSimulationMetrics() {
        Incident incident1 = new Incident("2023-10-01T10:00:00", 1, "FIRE_DETECTED", 10);
        Incident incident2 = new Incident("2023-10-01T10:05:00", 2, "FIRE_DETECTED", 20);

        scheduler.receiveIncident(incident1, null);
        scheduler.receiveIncident(incident2, null);

        scheduler.requestWork(1); // Drone 1 handles incident1
        scheduler.reportCompletion(1, incident1);

        scheduler.requestWork(2); // Drone 2 handles incident2
        scheduler.reportCompletion(2, incident2);

        scheduler.signalNoMoreIncidents();

        SimulationMetricsReport report = scheduler.getSimulationMetricsReport();
        assertEquals("Run-complete path should record completed incidents.", 2, report.incidentsCompleted());
        assertTrue("Mission wall time should be set when metrics finalize.", report.missionWallMs() >= 0);
    }

    @Test
    public void testDroneFaultHandling() {
        Incident incident = new Incident("2023-10-01T10:00:00", 1, "FIRE_DETECTED", 10);
        scheduler.receiveIncident(incident, null);

        scheduler.requestWork(1); // Drone 1 handles the incident
        scheduler.onDroneFaultDetected(1, "Nozzle failure", true);

        assertEquals("Drone 1 should be marked as OFFLINE.", "OFFLINE", scheduler.getDroneState(1));
        assertEquals("Incident should be re-queued after fault.", 1, scheduler.getQueueSize());
    }
}