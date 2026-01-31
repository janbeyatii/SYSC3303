package model;

/**
 * Data class for fire incidents.
 * Stores all the info about an incident: when it happened, which zone, what type, and severity.
 * Severity is the amount of water/foam needed in litres (Low=10, Moderate=20, High=30 per spec).
 */
public class Incident {
    private String time;
    private int zoneId;
    private String eventType;
    /** Amount of agent (water/foam) needed in litres: 10, 20, or 30 */
    private int severity;

    public Incident(String time, int zoneId, String eventType, int severity) {
        this.time = time;
        this.zoneId = zoneId;
        this.eventType = eventType;
        this.severity = severity;
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

    @Override
    public String toString() {
        return "Incident{" +
                "time='" + time + '\'' +
                ", zoneId=" + zoneId +
                ", eventType='" + eventType + '\'' +
                ", severity=" + severity +
                '}';
    }
}
