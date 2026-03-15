package fireincident.udp;

import model.Incident;

public class UDPMessage {

    public static final int    MAX_SIZE  = 1000;
    public static final String DELIMITER = "|";
    private final MessageType type;
    private final String[]    fields;

    public UDPMessage(MessageType type, String... fields) {
        this.type   = type;
        this.fields = fields;
    }
    public static UDPMessage incidentReport(Incident inc) {
        return new UDPMessage(MessageType.INCIDENT_REPORT,
                inc.getTime(),
                String.valueOf(inc.getZoneId()),
                inc.getEventType(),
                String.valueOf(inc.getSeverity()));
    }
    public static UDPMessage dispatchDrone(int droneId, Incident inc) {
        return new UDPMessage(MessageType.DISPATCH_DRONE,
                String.valueOf(droneId),
                inc.getTime(),
                String.valueOf(inc.getZoneId()),
                inc.getEventType(),
                String.valueOf(inc.getSeverity()));
    }
    public static UDPMessage droneArrived(int droneId, Incident inc) {
        return new UDPMessage(MessageType.DRONE_ARRIVED,
                String.valueOf(droneId),
                inc.getKey());
    }
    public static UDPMessage droneDroppedAgent(int droneId, Incident inc) {
        return new UDPMessage(MessageType.DRONE_DROPPED_AGENT,
                String.valueOf(droneId),
                inc.getKey());
    }
    public static UDPMessage droneReturning(int droneId) {
        return new UDPMessage(MessageType.DRONE_RETURNING,
                String.valueOf(droneId));
    }
    public static UDPMessage droneIdle(int droneId) {
        return new UDPMessage(MessageType.DRONE_IDLE,
                String.valueOf(droneId));
    }
    public static UDPMessage droneState(int droneId, String state, Integer zoneId) {
        return new UDPMessage(MessageType.DRONE_STATE,
                String.valueOf(droneId),
                state,
                zoneId == null ? "" : String.valueOf(zoneId));
    }
    public static UDPMessage incidentCompleted(Incident inc) {
        return new UDPMessage(MessageType.INCIDENT_COMPLETED, inc.getKey());
    }
    public static UDPMessage noMoreIncidents() {
        return new UDPMessage(MessageType.NO_MORE_INCIDENTS);
    }
    public static UDPMessage shutdown() {
        return new UDPMessage(MessageType.SHUTDOWN);
    }
    public byte[] toBytes() {
        return toString().getBytes();
    }
    public static UDPMessage fromBytes(byte[] data, int length) {
        String received = new String(data, 0, length);
        return fromString(received.trim());
    }
    public static UDPMessage fromString(String wire) {
        String[] parts = wire.split("\\" + DELIMITER, -1);
        MessageType type = MessageType.valueOf(parts[0]);
        String[] fields = new String[parts.length - 1];
        System.arraycopy(parts, 1, fields, 0, fields.length);
        return new UDPMessage(type, fields);
    }
    public Incident toIncident() {
        String time      = getField(1);
        int    zoneId    = Integer.parseInt(getField(2));
        String eventType = getField(3);
        int    severity  = Integer.parseInt(getField(4));
        return new Incident(time, zoneId, eventType, severity);
    }
    public MessageType getType() { return type; }

    public String getField(int index) {
        if (index >= 0 && index < fields.length) return fields[index];
        return "";
    }
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(type.name());
        for (String f : fields) sb.append(DELIMITER).append(f);
        return sb.toString();
    }
}