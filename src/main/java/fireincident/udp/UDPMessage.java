package fireincident.udp;

import model.DroneTelemetry;
import model.Incident;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Typed UDP payload for scheduler, drone, and fire-incident processes.
 * <p>
 * On the wire: {@code MESSAGE_TYPE|field1|field2|...} using {@link #DELIMITER}. Any field value
 * that itself contains {@code |} (e.g. incident key {@code time|zone|type}) is prefixed with
 * {@code b64:} and UTF-8 Base64-encoded so naive splitting does not corrupt payloads.
 */
public class UDPMessage {

    /** Maximum datagram payload size used by send/receive buffers. */
    public static final int    MAX_SIZE  = 1000;
    /** Separator between message type and fields. */
    public static final String DELIMITER = "|";
    private static final String B64_PREFIX = "b64:";

    private final MessageType type;
    /** Decoded semantic field values (after {@link #fromString} processing). */
    private final String[]    fields;

    /**
     * @param type   message discriminator
     * @param fields payload after the type token
     */
    public UDPMessage(MessageType type, String... fields) {
        this.type   = type;
        this.fields = fields;
    }

    /**
     * Fire subsystem notifies the scheduler of a new incident (includes fault metadata).
     */
    public static UDPMessage incidentReport(Incident inc) {
        return new UDPMessage(MessageType.INCIDENT_REPORT,
                inc.getTime(),
                String.valueOf(inc.getZoneId()),
                inc.getEventType(),
                String.valueOf(inc.getSeverity()),
                inc.getFaultType(),
                inc.getFaultTargetType(),
                inc.getFaultTargetId());
    }
    /**
     * Scheduler assigns an incident to a drone (push path on drone listen port).
     */
    public static UDPMessage dispatchDrone(int droneId, Incident inc) {
        return new UDPMessage(MessageType.DISPATCH_DRONE,
                String.valueOf(droneId),
                inc.getTime(),
                String.valueOf(inc.getZoneId()),
                inc.getEventType(),
                String.valueOf(inc.getSeverity()),
                inc.getFaultType(),
                inc.getFaultTargetType(),
                inc.getFaultTargetId());
    }
    /** Drone reports arrival; incident key is sent as one field (encoded on the wire if it contains {@code |}). */
    public static UDPMessage droneArrived(int droneId, Incident inc) {
        return new UDPMessage(MessageType.DRONE_ARRIVED,
                String.valueOf(droneId),
                inc.getKey());
    }

    /** Drone reports suppression complete for the given incident. */
    public static UDPMessage droneDroppedAgent(int droneId, Incident inc) {
        return new UDPMessage(MessageType.DRONE_DROPPED_AGENT,
                String.valueOf(droneId),
                inc.getKey());
    }

    /** Drone is returning to base after an incident. */
    public static UDPMessage droneReturning(int droneId) {
        return new UDPMessage(MessageType.DRONE_RETURNING,
                String.valueOf(droneId));
    }

    /** Drone is idle at base and ready for dispatch. */
    public static UDPMessage droneIdle(int droneId) {
        return new UDPMessage(MessageType.DRONE_IDLE,
                String.valueOf(droneId));
    }

    /** Optional status update for GUI / map (state and optional zone). */
    public static UDPMessage droneState(int droneId, String state, Integer zoneId) {
        return new UDPMessage(MessageType.DRONE_STATE,
                String.valueOf(droneId),
                state,
                zoneId == null ? "" : String.valueOf(zoneId));
    }

    /** Push-drone telemetry: agent (L), battery (s), destination zone, distance remaining (m). */
    public static UDPMessage droneStateTelemetry(DroneTelemetry t) {
        String z = t.zoneId() == null ? "" : String.valueOf(t.zoneId());
        String dz = t.destinationZoneId() == null ? "" : String.valueOf(t.destinationZoneId());
        String dist = t.distanceToDestinationMeters() == null ? "" : String.valueOf(t.distanceToDestinationMeters());
        return new UDPMessage(MessageType.DRONE_STATE,
                String.valueOf(t.droneId()),
                t.state(),
                z,
                String.valueOf(t.agentRemainingLitres()),
                String.valueOf(t.agentCapacityLitres()),
                String.valueOf(t.batteryRemainingSeconds()),
                String.valueOf(t.batteryMaxSeconds()),
                dz,
                dist);
    }

    /** Scheduler notifies Fire subsystem that an incident is fully handled. */
    public static UDPMessage incidentCompleted(Incident inc) {
        return new UDPMessage(MessageType.INCIDENT_COMPLETED, inc.getKey());
    }

    /**
     * Reconstructs {@code time|zoneId|eventType} from an {@link MessageType#INCIDENT_COMPLETED} message.
     * Supports legacy three-field wire format and compact single-field key (after decode).
     */
    public static String incidentCompletedKey(UDPMessage msg) {
        if (msg.getFieldCount() >= 3 && !msg.getField(2).isEmpty()) {
            return msg.getField(0) + DELIMITER + msg.getField(1) + DELIMITER + msg.getField(2);
        }
        return msg.getField(0);
    }

    /** Fire subsystem has no more rows to send. */
    public static UDPMessage noMoreIncidents() {
        return new UDPMessage(MessageType.NO_MORE_INCIDENTS);
    }

    /** Ask subsystems to stop gracefully. */
    public static UDPMessage shutdown() {
        return new UDPMessage(MessageType.SHUTDOWN);
    }

    /** UTF-8 bytes of {@link #toString()} for UDP send. */
    public byte[] toBytes() {
        return toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Parses raw datagram bytes into a message. */
    public static UDPMessage fromBytes(byte[] data, int length) {
        String received = new String(data, 0, length, StandardCharsets.UTF_8);
        return fromString(received.trim());
    }
    /**
     * Parses a received line into a {@link UDPMessage}. Must start with a {@link MessageType} name.
     */
    public static UDPMessage fromString(String wire) {
        String[] parts = wire.split("\\" + DELIMITER, -1);
        MessageType type = MessageType.valueOf(parts[0]);
        String[] raw = new String[parts.length - 1];
        System.arraycopy(parts, 1, raw, 0, raw.length);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = decodeWireField(raw[i]);
        }
        return new UDPMessage(type, raw);
    }

    /**
     * If {@code value} contains {@link #DELIMITER}, encodes as {@code b64:}<i>base64(UTF-8)</i>.
     */
    static String encodeWireField(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(DELIMITER.charAt(0)) < 0) {
            return value;
        }
        return B64_PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decodeWireField(String wire) {
        if (wire == null) {
            return "";
        }
        if (wire.startsWith(B64_PREFIX)) {
            try {
                byte[] decoded = Base64.getDecoder().decode(wire.substring(B64_PREFIX.length()));
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return wire;
            }
        }
        return wire;
    }
    /**
     * Rebuilds an {@link Incident} from a {@link MessageType#DISPATCH_DRONE} message only.
     * Field layout: droneId, time, zoneId, eventType, severity, faultType, faultTargetType, faultTargetId.
     *
     * @return incident embedded in the dispatch
     */
    public Incident toIncident() {
        String time      = getField(1);
        int    zoneId    = Integer.parseInt(getField(2));
        String eventType = getField(3);
        int    severity  = Integer.parseInt(getField(4));
        return new Incident(time, zoneId, eventType, severity,
                getField(5), getField(6), getField(7));
    }

    /** @return message discriminator */
    public MessageType getType() { return type; }

    /** @return number of payload fields (after the type token) */
    public int getFieldCount() {
        return fields.length;
    }

    /**
     * @param index zero-based index into the payload fields (after the type token)
     * @return field text, or empty string if out of range
     */
    public String getField(int index) {
        if (index >= 0 && index < fields.length) return fields[index];
        return "";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(type.name());
        for (String f : fields) {
            sb.append(DELIMITER).append(encodeWireField(f == null ? "" : f));
        }
        return sb.toString();
    }
}
