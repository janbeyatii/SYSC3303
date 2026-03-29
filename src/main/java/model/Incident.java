package model;

/**
 * Value object for a fire incident from the input file and/or UDP messages.
 * <p>
 * Severity is stored as litres of agent (water/foam): Low=10, Moderate=20, High=30 per course spec.
 * Iteration 4 adds optional fault-injection metadata; legacy rows default fault fields to {@link #NO_FAULT}.
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

    /**
     * Legacy constructor: no fault metadata (equivalent to {@code NONE} for all fault fields).
     *
     * @param time      incident time string from input
     * @param zoneId    fire zone index
     * @param eventType e.g. {@code FIRE_DETECTED}
     * @param severity  litres required: 10, 20, or 30
     */
    public Incident(String time, int zoneId, String eventType, int severity) {
        this(time, zoneId, eventType, severity, NO_FAULT, NO_FAULT, NO_FAULT);
    }

    /**
     * Full constructor including Iteration 4 fault fields.
     *
     * @param faultType       e.g. {@code DRONE_STUCK}, or {@link #NO_FAULT}
     * @param faultTargetType {@code NONE}, {@code EVENT}, or {@code DRONE}
     * @param faultTargetId   event key or drone id (e.g. {@code D1}), or {@link #NO_FAULT}
     */
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

    /**
     * Stable identity for this incident: {@code time|zoneId|eventType}.
     * Used for queue keys, GUI rows, and fault target matching for {@code EVENT} scope.
     */
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
