package fireincident.udp;

import model.Incident;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UDPMessageWireTest {

    @Test
    public void incidentReport_roundTripsFaultTargetWithPipes() {
        String key = "14:03:15|3|FIRE_DETECTED";
        Incident inc = new Incident("14:03:15", 3, "FIRE_DETECTED", 30,
                "DRONE_STUCK", "EVENT", key);
        String wire = UDPMessage.incidentReport(inc).toString();
        UDPMessage parsed = UDPMessage.fromString(wire);
        assertEquals(MessageType.INCIDENT_REPORT, parsed.getType());
        Incident rebuilt = new Incident(
                parsed.getField(0),
                Integer.parseInt(parsed.getField(1)),
                parsed.getField(2),
                Integer.parseInt(parsed.getField(3)),
                parsed.getField(4),
                parsed.getField(5),
                parsed.getField(6));
        assertEquals(key, rebuilt.getFaultTargetId());
        assertEquals("DRONE_STUCK", rebuilt.getFaultType());
    }

    @Test
    public void dispatchDrone_roundTripsIncident() {
        Incident inc = new Incident("14:07:00", 6, "FIRE_DETECTED", 20,
                "PACKET_LOSS", "EVENT", "14:07:00|6|FIRE_DETECTED");
        String wire = UDPMessage.dispatchDrone(2, inc).toString();
        UDPMessage parsed = UDPMessage.fromString(wire);
        assertEquals(MessageType.DISPATCH_DRONE, parsed.getType());
        assertEquals("2", parsed.getField(0));
        Incident fromDispatch = parsed.toIncident();
        assertEquals("14:07:00|6|FIRE_DETECTED", fromDispatch.getFaultTargetId());
    }

    @Test
    public void incidentCompleted_compactKeyMatchesLegacyJoin() {
        Incident inc = new Incident("12:00:00", 5, "FIRE_DETECTED", 10);
        UDPMessage done = UDPMessage.incidentCompleted(inc);
        String wire = done.toString();
        UDPMessage parsed = UDPMessage.fromString(wire);
        assertEquals(inc.getKey(), UDPMessage.incidentCompletedKey(parsed));
    }
}
