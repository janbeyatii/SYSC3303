package model;

/**
 * Data class for fire incidents.
 * Stores all the info about an incident: when it happened, which zone, what type, and severity.
 */
public class Incident {
    private String time;
    private int zoneId;
    private String eventType;
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
