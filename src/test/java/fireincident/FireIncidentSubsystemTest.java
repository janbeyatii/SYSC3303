package fireincident;

import model.Incident;
import org.junit.Test;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class FireIncidentSubsystemTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private List<Incident> receivedIncidents;
    private List<Incident> completedIncidents;
    private SchedulerInterface mockScheduler;

    @Before
    public void setUp() {
        receivedIncidents = new ArrayList<>();
        completedIncidents = new ArrayList<>();
        mockScheduler = new SchedulerInterface() {
            @Override
            public void receiveIncident(Incident incident, IncidentCallback callback) {
                receivedIncidents.add(incident);
                // Simulate completion so callback is exercised
                if (callback != null) {
                    callback.onIncidentCompleted(incident);
                }
            }
        };
    }

    @Test
    public void processIncidentsReadsCsvAndSendsToScheduler() throws IOException {
        File csv = folder.newFile("incidents.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity\n");
            w.write("14:03:15,3,FIRE_DETECTED,High\n");
            w.write("14:10:00,7,DRONE_REQUEST,Moderate\n");
        }

        FireIncidentSubsystem fis = new FireIncidentSubsystem(csv.getPath(), mockScheduler);
        fis.processIncidents();

        assertEquals(2, receivedIncidents.size());
        Incident first = receivedIncidents.get(0);
        assertEquals("14:03:15", first.getTime());
        assertEquals(3, first.getZoneId());
        assertEquals("FIRE_DETECTED", first.getEventType());
        assertEquals(30, first.getSeverity()); // High = 30 L

        Incident second = receivedIncidents.get(1);
        assertEquals("14:10:00", second.getTime());
        assertEquals(7, second.getZoneId());
        assertEquals("DRONE_REQUEST", second.getEventType());
        assertEquals(20, second.getSeverity()); // Moderate = 20 L
    }

    @Test
    public void processIncidentsAcceptsLitresInCsv() throws IOException {
        File csv = folder.newFile("incidents.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity\n");
            w.write("12:00:00,1,FIRE_DETECTED,10\n"); // Low as litres
        }

        FireIncidentSubsystem fis = new FireIncidentSubsystem(csv.getPath(), mockScheduler);
        fis.processIncidents();

        assertEquals(1, receivedIncidents.size());
        assertEquals(10, receivedIncidents.get(0).getSeverity());
    }

    @Test
    public void onIncidentCompletedCalledByMock() throws IOException {
        completedIncidents.clear();
        SchedulerInterface withCallback = (incident, callback) -> {
            receivedIncidents.add(incident);
            if (callback != null) {
                callback.onIncidentCompleted(incident);
            }
        };
        FireIncidentSubsystem fis = new FireIncidentSubsystem(
                createCsvWithOneLine(), withCallback) {
            @Override
            public void onIncidentCompleted(Incident incident) {
                completedIncidents.add(incident);
            }
        };
        fis.processIncidents();
        assertEquals(1, completedIncidents.size());
    }

    private String createCsvWithOneLine() throws IOException {
        File csv = folder.newFile("one.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity\n");
            w.write("14:03:15,3,FIRE_DETECTED,High\n");
        }
        return csv.getPath();
    }
}
