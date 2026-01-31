package model;

import org.junit.Test;
import static org.junit.Assert.*;

public class IncidentTest {

    @Test
    public void constructorAndGetters() {
        Incident i = new Incident("14:03:15", 3, "FIRE_DETECTED", 30);
        assertEquals("14:03:15", i.getTime());
        assertEquals(3, i.getZoneId());
        assertEquals("FIRE_DETECTED", i.getEventType());
        assertEquals(30, i.getSeverity());
    }

    @Test
    public void toStringContainsFields() {
        Incident i = new Incident("14:10:00", 7, "DRONE_REQUEST", 20);
        String s = i.toString();
        assertTrue(s.contains("14:10:00"));
        assertTrue(s.contains("7"));
        assertTrue(s.contains("DRONE_REQUEST"));
        assertTrue(s.contains("20"));
    }

    @Test
    public void severityStoredAsLitres() {
        assertEquals(10, new Incident("00:00:00", 1, "FIRE_DETECTED", 10).getSeverity());
        assertEquals(20, new Incident("00:00:00", 1, "FIRE_DETECTED", 20).getSeverity());
        assertEquals(30, new Incident("00:00:00", 1, "FIRE_DETECTED", 30).getSeverity());
    }
}
