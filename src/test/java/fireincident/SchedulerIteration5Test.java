package fireincident;

import model.DroneTelemetry;
import model.Incident;
import model.SimulationMetricsReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerIteration5Test {

    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new Scheduler(false); // In-process mode (no UDP)
    }

    @Test
    void testIncidentQueueingAndCompletion() {
        Incident incident1 = new Incident("2023-10-01T10:00:00", 1, "FIRE_DETECTED", 10);
        Incident incident2 = new Incident("2023-10-01T10:05:00", 2, "FIRE_DETECTED", 20);

        scheduler.receiveIncident(incident1, null);
        scheduler.receiveIncident(incident2, null);

        assertEquals(2, scheduler.getQueueSize(), "Both incidents should be queued.");

        scheduler.requestWork(1); // Drone 1 handles incident1
        assertEquals(1, scheduler.getQueueSize(), "One incident should remain in the queue.");

        scheduler.reportCompletion(1, incident1);
        assertEquals(0, scheduler.getQueueSize(), "All incidents should be completed.");
    }

    @Test
    void testDroneStateUpdates() {
        scheduler.updateDroneState(1, "IDLE", 0);
        scheduler.updateDroneState(2, "EN_ROUTE", 1);

        assertEquals("IDLE", scheduler.getDroneState(1), "Drone 1 should be IDLE.");
        assertEquals("EN_ROUTE", scheduler.getDroneState(2), "Drone 2 should be EN_ROUTE.");
    }

    @Test
    void testSimulationMetrics() {
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
        assertEquals(2, report.incidentsCompleted(), "Two incidents should be completed.");
        assertTrue(report.missionWallMs() > 0, "Mission time should be greater than 0.");
    }

    @Test
    void testDroneFaultHandling() {
        Incident incident = new Incident("2023-10-01T10:00:00", 1, "FIRE_DETECTED", 10);
        scheduler.receiveIncident(incident, null);

        scheduler.requestWork(1); // Drone 1 handles the incident
        scheduler.onDroneFaultDetected(1, "Nozzle failure", true);

        assertEquals("OFFLINE", scheduler.getDroneState(1), "Drone 1 should be marked as OFFLINE.");
        assertEquals(1, scheduler.getQueueSize(), "Incident should be re-queued after fault.");
    }
}