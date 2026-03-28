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
    @Test
    public void processIncidents_iter4DroneStuckEventFile() throws IOException {
        File csv = folder.newFile("iter4_fault_drone_stuck_event.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:03:15,3,FIRE_DETECTED,High,DRONE_STUCK,EVENT,14:03:15|3|FIRE_DETECTED\n");
            w.write("14:10:00,7,DRONE_REQUEST,Moderate,NONE,NONE,NONE\n");
        }
        new FireIncidentSubsystem(csv.getPath(), mockScheduler).processIncidents();

        assertEquals(2, receivedIncidents.size());

        Incident faulted = receivedIncidents.get(0);
        assertEquals("DRONE_STUCK", faulted.getFaultType());
        assertEquals("EVENT", faulted.getFaultTargetType());
        assertEquals(faulted.getKey(), faulted.getFaultTargetId());

        Incident clean = receivedIncidents.get(1);
        assertEquals("NONE", clean.getFaultType());
        assertEquals("NONE", clean.getFaultTargetType());
        assertEquals("NONE", clean.getFaultTargetId());
    }

    @Test
    public void processIncidents_iter4NozzleJamDroneFile() throws IOException {
        File csv = folder.newFile("iter4_fault_nozzle_jam_drone.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:05:30,2,FIRE_DETECTED,30,NOZZLE_JAM,DRONE,D1\n");
            w.write("14:12:45,5,DRONE_REQUEST,20,NONE,NONE,NONE\n");
        }
        new FireIncidentSubsystem(csv.getPath(), mockScheduler).processIncidents();

        assertEquals(2, receivedIncidents.size());
        Incident faulted = receivedIncidents.get(0);
        assertEquals("NOZZLE_JAM", faulted.getFaultType());
        assertEquals("DRONE", faulted.getFaultTargetType());
        assertEquals("D1", faulted.getFaultTargetId());
        assertEquals(30, faulted.getSeverity());
    }

    @Test
    public void processIncidents_iter4PacketLossEventFile() throws IOException {
        File csv = folder.newFile("iter4_fault_packet_loss_event.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:07:00,6,FIRE_DETECTED,Moderate,PACKET_LOSS,EVENT,14:07:00|6|FIRE_DETECTED\n");
            w.write("14:20:00,8,DRONE_REQUEST,Low,NONE,NONE,NONE\n");
        }
        new FireIncidentSubsystem(csv.getPath(), mockScheduler).processIncidents();

        assertEquals(2, receivedIncidents.size());
        assertEquals("PACKET_LOSS", receivedIncidents.get(0).getFaultType());
        assertEquals("EVENT", receivedIncidents.get(0).getFaultTargetType());
        assertEquals(20, receivedIncidents.get(0).getSeverity());
        assertEquals(10, receivedIncidents.get(1).getSeverity());
    }

    @Test
    public void processIncidents_iter4CorruptedMessageDroneFile() throws IOException {
        File csv = folder.newFile("iter4_fault_corrupted_message_drone.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:09:15,4,FIRE_DETECTED,High,CORRUPTED_MESSAGE,DRONE,D3\n");
            w.write("14:18:30,9,DRONE_REQUEST,Moderate,NONE,NONE,NONE\n");
        }
        new FireIncidentSubsystem(csv.getPath(), mockScheduler).processIncidents();

        assertEquals(2, receivedIncidents.size());
        assertEquals("CORRUPTED_MESSAGE", receivedIncidents.get(0).getFaultType());
        assertEquals("DRONE", receivedIncidents.get(0).getFaultTargetType());
        assertEquals("D3", receivedIncidents.get(0).getFaultTargetId());
    }

    @Test
    public void processIncidents_iter4MixedFaultFile() throws IOException {
        File csv = folder.newFile("iter4_fault_mixed.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:01:00,1,FIRE_DETECTED,10,NONE,NONE,NONE\n");
            w.write("14:03:15,3,FIRE_DETECTED,High,DRONE_STUCK,EVENT,14:03:15|3|FIRE_DETECTED\n");
            w.write("14:05:30,2,FIRE_DETECTED,30,NOZZLE_JAM,DRONE,D1\n");
            w.write("14:07:00,6,FIRE_DETECTED,Moderate,PACKET_LOSS,EVENT,14:07:00|6|FIRE_DETECTED\n");
            w.write("14:09:15,4,FIRE_DETECTED,High,CORRUPTED_MESSAGE,DRONE,D3\n");
        }
        new FireIncidentSubsystem(csv.getPath(), mockScheduler).processIncidents();

        assertEquals(5, receivedIncidents.size());
        assertEquals("NONE", receivedIncidents.get(0).getFaultType());
        assertEquals("DRONE_STUCK", receivedIncidents.get(1).getFaultType());
        assertEquals("NOZZLE_JAM", receivedIncidents.get(2).getFaultType());
        assertEquals("PACKET_LOSS", receivedIncidents.get(3).getFaultType());
        assertEquals("CORRUPTED_MESSAGE", receivedIncidents.get(4).getFaultType());
    }

    @Test
    public void processIncidents_noneFaultTargetTypeClearsFaultTargetId() throws IOException {
        File csv = folder.newFile("none_target.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:00:00,1,FIRE_DETECTED,Low,DRONE_STUCK,NONE,D1\n");
        }
        new FireIncidentSubsystem(csv.getPath(), mockScheduler).processIncidents();

        assertEquals(1, receivedIncidents.size());
        assertEquals("NONE", receivedIncidents.get(0).getFaultTargetId());
    }

    @Test
    public void processIncidents_faultFieldsNormalisedToUpperCase() throws IOException {
        File csv = folder.newFile("lowercase_fault.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:03:15,3,FIRE_DETECTED,High,drone_stuck,event,14:03:15|3|FIRE_DETECTED\n");
        }
        new FireIncidentSubsystem(csv.getPath(), mockScheduler).processIncidents();

        assertEquals(1, receivedIncidents.size());
        assertEquals("DRONE_STUCK", receivedIncidents.get(0).getFaultType());
        assertEquals("EVENT", receivedIncidents.get(0).getFaultTargetType());
    }

    @Test
    public void processIncidents_invalidFaultTargetTypeSkipsLine() throws IOException {
        File csv = folder.newFile("bad_target_type.csv");
        try (FileWriter w = new FileWriter(csv)) {
            w.write("Time,Zone ID,Event type,Severity,Fault Type,Fault Target Type,Fault Target ID\n");
            w.write("14:03:15,3,FIRE_DETECTED,High,DRONE_STUCK,ZONE,3\n");
            w.write("14:10:00,7,DRONE_REQUEST,Low,NONE,NONE,NONE\n");
        }
        new FireIncidentSubsystem(csv.getPath(), mockScheduler).processIncidents();

        assertEquals(1, receivedIncidents.size());
        assertEquals("14:10:00", receivedIncidents.get(0).getTime());
    }

}
