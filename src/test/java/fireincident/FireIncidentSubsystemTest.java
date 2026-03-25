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
    private SchedulerInterface mockScheduler;

    @Before
    public void setUp() {
        receivedIncidents = new ArrayList<>();
        mockScheduler = new SchedulerInterface() {
            @Override
            public void receiveIncident(Incident incident, IncidentCallback callback) {
                receivedIncidents.add(incident);
                if (callback != null) {
                    callback.onIncidentCompleted(incident);
                }
            }
            @Override
            public void signalNoMoreIncidents() { }
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
        assertEquals("NONE", first.getFaultType());
        assertEquals("NONE", first.getFaultTargetType());
        assertEquals("NONE", first.getFaultTargetId());

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
    public void processIncidentsHandlesInvalidLinesGracefully() throws IOException {
        File csv = folder.newFile("incidents.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity\n");
            w.write("14:03:15,3,FIRE_DETECTED,High\n");
            w.write("Invalid,Line,Here\n"); // Invalid line
            w.write("14:10:00,7,DRONE_REQUEST,Moderate\n");
        }

        FireIncidentSubsystem fis = new FireIncidentSubsystem(csv.getPath(), mockScheduler);
        fis.processIncidents();

        assertEquals(2, receivedIncidents.size());
        assertEquals("14:03:15", receivedIncidents.get(0).getTime());
        assertEquals("14:10:00", receivedIncidents.get(1).getTime());
    }

    @Test
    public void processIncidentsHandlesEmptyCsv() throws IOException {
        File csv = folder.newFile("empty.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity\n"); // Header only
        }

        FireIncidentSubsystem fis = new FireIncidentSubsystem(csv.getPath(), mockScheduler);
        fis.processIncidents();

        assertTrue(receivedIncidents.isEmpty());
    }

    @Test
    public void processIncidentsParsesEventTargetedFaultFields() throws IOException {
        File csv = folder.newFile("incidents_fault_event.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:03:15,3,FIRE_DETECTED,High,DRONE_STUCK,EVENT,14:03:15|3|FIRE_DETECTED\n");
        }

        FireIncidentSubsystem fis = new FireIncidentSubsystem(csv.getPath(), mockScheduler);
        fis.processIncidents();

        assertEquals(1, receivedIncidents.size());
        Incident incident = receivedIncidents.get(0);
        assertEquals("DRONE_STUCK", incident.getFaultType());
        assertEquals("EVENT", incident.getFaultTargetType());
        assertEquals("14:03:15|3|FIRE_DETECTED", incident.getFaultTargetId());
    }

    @Test
    public void processIncidentsParsesDroneTargetedFaultFields() throws IOException {
        File csv = folder.newFile("incidents_fault_drone.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:10:00,7,DRONE_REQUEST,Moderate,NOZZLE_JAM,DRONE,D2\n");
        }

        FireIncidentSubsystem fis = new FireIncidentSubsystem(csv.getPath(), mockScheduler);
        fis.processIncidents();

        assertEquals(1, receivedIncidents.size());
        Incident incident = receivedIncidents.get(0);
        assertEquals("NOZZLE_JAM", incident.getFaultType());
        assertEquals("DRONE", incident.getFaultTargetType());
        assertEquals("D2", incident.getFaultTargetId());
    }

    @Test
    public void processIncidentsSkipsInvalidFaultTypeLines() throws IOException {
        File csv = folder.newFile("incidents_fault_invalid.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:03:15,3,FIRE_DETECTED,High,BOGUS_FAULT,EVENT,14:03:15|3|FIRE_DETECTED\n");
            w.write("14:10:00,7,DRONE_REQUEST,Moderate,NONE,NONE,NONE\n");
        }

        FireIncidentSubsystem fis = new FireIncidentSubsystem(csv.getPath(), mockScheduler);
        fis.processIncidents();

        assertEquals(1, receivedIncidents.size());
        assertEquals("14:10:00", receivedIncidents.get(0).getTime());
    }

}
