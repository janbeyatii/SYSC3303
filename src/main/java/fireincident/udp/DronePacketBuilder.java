package fireincident.udp;

import model.DroneTelemetry;
import model.Incident;

/**
 * Builds UDP request packets for drone-to-scheduler protocol.
 * Format: one line, pipe-separated. TYPE|field1|field2|...
 * Incident: time|zoneId|eventType|severity
 */
public final class DronePacketBuilder {

    private static final String SEP = "|";

    /** REQUEST_WORK|droneId or REQUEST_WORK|droneId|currentZone|agentRemaining (for multi-zone routing). */
    public static byte[] requestWork(int droneId) {
        return ("REQUEST_WORK" + SEP + droneId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Request work from current position with remaining agent (spec: use remaining agent before return). */
    public static byte[] requestWork(int droneId, int currentZone, int agentRemaining) {
        return ("REQUEST_WORK" + SEP + droneId + SEP + currentZone + SEP + agentRemaining).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    /**
     * REPORT_STATE|droneId|state|zone|agentRem|agentMax|batteryRem|batteryMax|destZone|distM
     */
    public static byte[] reportStateTelemetry(DroneTelemetry t) {
        String z = t.zoneId() == null ? "" : String.valueOf(t.zoneId());
        String dz = t.destinationZoneId() == null ? "" : String.valueOf(t.destinationZoneId());
        String dist = t.distanceToDestinationMeters() == null ? "" : String.valueOf(t.distanceToDestinationMeters());
        String line = "REPORT_STATE" + SEP + t.droneId() + SEP + t.state() + SEP + z + SEP
                + t.agentRemainingLitres() + SEP + t.agentCapacityLitres() + SEP
                + t.batteryRemainingSeconds() + SEP + t.batteryMaxSeconds() + SEP + dz + SEP + dist;
        return line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Response to REQUEST_WORK: assign an incident to the drone. */
    public static byte[] assignIncident(Incident incident) {
        return ("ASSIGN" + SEP + incident.getTime() + SEP + incident.getZoneId()
                + SEP + incident.getEventType() + SEP + incident.getSeverity()
                + SEP + incident.getFaultType() + SEP + incident.getFaultTargetType()
                + SEP + incident.getFaultTargetId()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Response to REQUEST_WORK: no work available. */
    public static byte[] assignNoWork() {
        return "ASSIGN".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] ack() {
        return "ACK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private DronePacketBuilder() {}
}
