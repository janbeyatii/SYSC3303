package fireincident.udp;

import model.Incident;

import java.nio.charset.StandardCharsets;

/**
 * Parses UDP response packets from scheduler.
 * Expected: ASSIGN|time|zoneId|eventType|severity  or  ASSIGN|
 *           PEEK_RESP|time|zoneId|eventType|severity  or  PEEK_RESP|
 *           ACK
 */
public final class DronePacketParser {

    private static final String SEP = "\\|";

    /**
     * Parse response payload. Returns parsed type and optionally an Incident.
     * For ASSIGN and PEEK_RESP with no incident, incident is null.
     */
    public static Response parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new Response("", null);
        }
        String line = new String(payload, StandardCharsets.UTF_8).trim();
        String[] parts = line.split(SEP, -1);
        if (parts.length == 0) {
            return new Response("", null);
        }
        String type = parts[0].trim();
        Incident incident = null;
        if (("ASSIGN".equals(type) || "PEEK_RESP".equals(type)) && parts.length >= 5) {
            String time = parts[1].trim();
            if (!time.isEmpty()) {
                try {
                    int zoneId = Integer.parseInt(parts[2].trim());
                    String eventType = parts[3].trim();
                    int severity = Integer.parseInt(parts[4].trim());
                    incident = new Incident(time, zoneId, eventType, severity);
                } catch (NumberFormatException ignored) {
                    // leave incident null
                }
            }
        }
        return new Response(type, incident);
    }

    public static final class Response {
        public final String type;
        public final Incident incident;

        public Response(String type, Incident incident) {
            this.type = type;
            this.incident = incident;
        }
    }

    private DronePacketParser() {}
}
