package model;

/**
 * Data class for fire incidents.
 * Stores all the info about an incident: when it happened, which zone, what type, and severity.
 * Severity is the amount of water/foam needed in litres (Low=10, Moderate=20, High=30 per spec).
 */
public class Incident {
    public static final String NO_FAULT = "NONE";

    private String time;
    private int zoneId;
    private String eventType;
    /** Amount of agent (water/foam) needed in litres: 10, 20, or 30 */
    private int severity;
    /** Injected fault type for Iteration 4 input scenarios. */
    private String faultType;
    /** Fault target scope: NONE, EVENT, or DRONE. */
    private String faultTargetType;
    /** Target event key or drone id for fault injection. */
    private String faultTargetId;

    public Incident(String time, int zoneId, String eventType, int severity) {
        this(time, zoneId, eventType, severity, NO_FAULT, NO_FAULT, NO_FAULT);
    }

    public Incident(String time, int zoneId, String eventType, int severity,
                    String faultType, String faultTargetType, String faultTargetId) {
        this.time = time;
        this.zoneId = zoneId;
        this.eventType = eventType;
        this.severity = severity;
        this.faultType = normalizeFaultField(faultType);
        this.faultTargetType = normalizeFaultField(faultTargetType);
        this.faultTargetId = normalizeFaultField(faultTargetId);
    }

    public String getTime() {
        return time;
    }

    public int getZoneId() {
        return zoneId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSeverity() {
        return severity;
    }

    public String getFaultType() {
        return faultType;
    }

    public String getFaultTargetType() {
        return faultTargetType;
    }

    public String getFaultTargetId() {
        return faultTargetId;
    }

    /** Unique key for this incident (time|zone|type). Used for tracking and GUI row lookup. */
    public String getKey() {
        return getTime() + "|" + getZoneId() + "|" + getEventType();
    }

    private static String normalizeFaultField(String value) {
        if (value == null) {
            return NO_FAULT;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? NO_FAULT : trimmed;
    }

    @Override
    public String toString() {
        return "Incident{" +
                "time='" + time + '\'' +
                ", zoneId=" + zoneId +
                ", eventType='" + eventType + '\'' +
                ", severity=" + severity +
                ", faultType='" + faultType + '\'' +
                ", faultTargetType='" + faultTargetType + '\'' +
                ", faultTargetId='" + faultTargetId + '\'' +
                '}';
    }
}
