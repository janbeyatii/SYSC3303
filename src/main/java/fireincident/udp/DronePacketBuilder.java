package fireincident.udp;

import model.Incident;

/**
 * Builds UDP request packets for drone-to-scheduler protocol.
 * Format: one line, pipe-separated. TYPE|field1|field2|...
 * Incident: time|zoneId|eventType|severity
 */
public final class DronePacketBuilder {

    private static final String SEP = "|";

    public static byte[] requestWork(int droneId) {
        return ("REQUEST_WORK" + SEP + droneId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] peekNext() {
        return "PEEK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] reportArrival(int droneId, Incident incident) {
        return ("REPORT_ARRIVAL" + SEP + droneId + SEP + incident.getTime() + SEP + incident.getZoneId()
                + SEP + incident.getEventType() + SEP + incident.getSeverity()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] reportCompletion(int droneId, Incident incident) {
        return ("REPORT_COMPLETION" + SEP + droneId + SEP + incident.getTime() + SEP + incident.getZoneId()
                + SEP + incident.getEventType() + SEP + incident.getSeverity()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] reportReturnToBase(int droneId) {
        return ("REPORT_RETURN" + SEP + droneId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] reportState(int droneId, String state, Integer zoneId) {
        String z = (zoneId == null) ? "" : String.valueOf(zoneId);
        return ("REPORT_STATE" + SEP + droneId + SEP + state + SEP + z).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private DronePacketBuilder() {}
}
