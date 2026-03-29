package fireincident;

import model.Incident;
import fireincident.udp.MessageType;
import fireincident.udp.Ports;
import fireincident.udp.UDPMessage;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import java.nio.charset.StandardCharsets;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fire Incident Subsystem: reads incident rows from a file, parses {@link Incident} objects,
 * and delivers them to the scheduler (in-process {@link SchedulerInterface} or UDP
 * {@link UDPMessage#incidentReport} to {@link Ports#SCHEDULER}).
 * <p>
 * Listens on {@link Ports#FIRE_IS} for {@link MessageType#INCIDENT_COMPLETED} acknowledgements
 * when running as a separate process.
 */
public class FireIncidentSubsystem implements Runnable {
    /** Set to true for verbose packet-level debug logging. */
    private static final boolean DEBUG_PACKETS = false;
    private static final Set<String> ALLOWED_FAULT_TYPES = new HashSet<>(Arrays.asList(
            "NONE", "DRONE_STUCK", "NOZZLE_JAM", "PACKET_LOSS", "CORRUPTED_MESSAGE"
    ));
    private static final Set<String> ALLOWED_FAULT_TARGET_TYPES = new HashSet<>(Arrays.asList(
            "NONE", "EVENT", "DRONE"
    ));

    DatagramPacket sendPacket;
    DatagramSocket sendSocket;
    DatagramPacket receivePacket;
    DatagramSocket receiveSocket;

    private String csvFilePath;
    private final SchedulerInterface scheduler;
    private final String udpSchedulerHost;
    private final int udpSchedulerPort;
    /** Tracks incident keys we sent, for verification when drone completes. */
    private final Set<String> pendingIncidentKeys = new HashSet<>();

    /**
     * @param csvFilePath path to incident CSV or whitespace file (header row required)
     */
    public FireIncidentSubsystem(String csvFilePath) {
        this(csvFilePath, null);
    }

    /**
     * In-process mode for tests: incidents go to the given scheduler directly.
     */
    public FireIncidentSubsystem(String csvFilePath, SchedulerInterface scheduler) {
        this(csvFilePath, scheduler, null, -1);
    }

    /**
     * UDP-only constructor for running as a separate process (e.g. from {@link app.FireIncidentMain}).
     *
     * @param schedulerHost scheduler bind address (e.g. {@code 127.0.0.1})
     * @param schedulerPort   UDP port (typically {@link Ports#SCHEDULER})
     */
    public FireIncidentSubsystem(String csvFilePath, String schedulerHost, int schedulerPort) {
        this(csvFilePath, null, schedulerHost, schedulerPort);
    }

    private FireIncidentSubsystem(String csvFilePath, SchedulerInterface scheduler, String schedulerHost, int schedulerPort) {
        this.csvFilePath = csvFilePath;
        this.scheduler = scheduler;
        this.udpSchedulerHost = schedulerHost;
        this.udpSchedulerPort = schedulerPort;
        if (scheduler != null) {
            return;
        }
        try {
            sendSocket = new DatagramSocket();
            receiveSocket = new DatagramSocket(Ports.FIRE_IS);
            System.out.println("[FireIncidentSubsystem] Listening on port " + Ports.FIRE_IS);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize fire incident subsystem sockets", e);
        }
    }

    /** Resolves scheduler address for UDP sends (separate-process mode). */
    private InetAddress resolveSchedulerInetAddress() throws IOException {
        if (udpSchedulerHost != null && !udpSchedulerHost.isEmpty()) {
            return InetAddress.getByName(udpSchedulerHost.trim());
        }
        return InetAddress.getLocalHost();
    }

    private int effectiveSchedulerPort() {
        return udpSchedulerPort > 0 ? udpSchedulerPort : Ports.SCHEDULER;
    }

    @Override
    public void run() {
        Thread receiver = new Thread(this::receiveLoop, "FIS-Receiver");
        receiver.setDaemon(true);
        receiver.start();
        processIncidents();
    }
    /**
     * Reads the CSV file and processes each incident.
     * Skips the first line (header) and sends each incident to the scheduler.
     */
    public void processIncidents() {
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
            // First line should be header, skip it
            String header = reader.readLine();
            if (header == null) {
                System.out.println("[FireIncidentSubsystem] CSV file is empty or has no header.");
                return;
            }
            System.out.println("[FireIncidentSubsystem] Skipping header: " + header);

            String line;
            int lineNumber = 2; 

            while ((line = reader.readLine()) != null) {
                try {
                    Incident incident = parseIncident(line, lineNumber);
                    if (incident != null) {
                        System.out.println("[FireIncidentSubsystem] Parsed incident: " + incident);
                        sendIncident(incident);
                    }
                } catch (Exception e) {
                    System.err.println("[FireIncidentSubsystem] Error parsing line " + lineNumber + ": " + e.getMessage());
                    System.err.println("[FireIncidentSubsystem] Line content: " + line);
                }
                lineNumber++;
            }
            System.out.println("[FireIncidentSubsystem] Finished processing CSV file. Total lines processed: " + (lineNumber - 2));
            if (scheduler != null) {
                scheduler.signalNoMoreIncidents();
            } else {
                sendNoMoreIncidents();
            }
        } catch (IOException e) {
            System.err.println("[FireIncidentSubsystem] Error reading CSV file: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void sendIncident(Incident incident) {
        pendingIncidentKeys.add(incident.getKey());
        if (scheduler != null) {
            IncidentCallback wrap = c -> {
                pendingIncidentKeys.remove(c.getKey());
                System.out.println("[FireIncidentSubsystem] CONFIRMED: Fire zone received assistance - drone arrived and used agent for: " + c.getKey());
            };
            scheduler.receiveIncident(incident, wrap);
            return;
        }
        try {
            byte[] msg = UDPMessage.incidentReport(incident).toBytes();
            InetAddress destAddr = resolveSchedulerInetAddress();
            int destPort = effectiveSchedulerPort();
            sendPacket = new DatagramPacket(msg, msg.length, destAddr, destPort);
            if (DEBUG_PACKETS) {
                System.out.println("[FireIncidentSubsystem] Sending to " + destAddr + ":" + destPort + ": " + new String(msg));
            }
            sendSocket.send(sendPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendNoMoreIncidents() {
        if (sendSocket == null) return;
        try {
            InetAddress destAddr = resolveSchedulerInetAddress();
            int destPort = effectiveSchedulerPort();
            byte[] msg = UDPMessage.noMoreIncidents().toBytes();
            sendPacket = new DatagramPacket(msg, msg.length, destAddr, destPort);
            sendSocket.send(sendPacket);
            System.out.println("[FireIncidentSubsystem] Sent NO_MORE_INCIDENTS to scheduler.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void receiveLoop() {
        while (true) {
            try {
                byte[] data = new byte[UDPMessage.MAX_SIZE];
                receivePacket = new DatagramPacket(data, data.length);
                receiveSocket.receive(receivePacket);
                int len = receivePacket.getLength();
                String received = new String(data, 0, len, StandardCharsets.UTF_8);
                if (DEBUG_PACKETS) {
                    System.out.println("[FireIncidentSubsystem] Received from " + receivePacket.getAddress() + ":" + receivePacket.getPort() + ": " + received.trim());
                }
                UDPMessage msg = UDPMessage.fromString(received.trim());

                if (msg.getType() == MessageType.INCIDENT_COMPLETED) {
                    String incidentKey = UDPMessage.incidentCompletedKey(msg);
                    if (pendingIncidentKeys.remove(incidentKey)) {
                        System.out.println("[FireIncidentSubsystem] CONFIRMED: Fire zone received assistance - drone arrived and used agent for: " + incidentKey);
                    } else {
                        System.err.println("[FireIncidentSubsystem] WARNING: Received completion for unknown incident: " + incidentKey);
                    }
                } else if (msg.getType() == MessageType.SHUTDOWN) {
                    System.out.println("[FireIncidentSubsystem] Shutdown received.");
                    break;
                }

            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
    }
    /**
     * Parses a line into an Incident object.
     * Supports both formats per spec:
     * - Whitespace-separated: time zoneId eventType severity [faultType faultTargetType faultTargetId]
     * - Comma-separated (CSV): time,zoneId,eventType,severity[,faultType,faultTargetType,faultTargetId]
     */
    private Incident parseIncident(String line, int lineNumber) {
        if (line == null || line.trim().isEmpty()) {
            System.out.println("[FireIncidentSubsystem] Skipping empty line " + lineNumber);
            return null;
        }
        String[] parts = line.contains(",") ? line.split(",") : line.trim().split("\\s+");
        if (parts.length != 4 && parts.length != 7) {
            throw new IllegalArgumentException("Expected 4 or 7 fields but found " + parts.length);
        }
        try {
            String time = parts[0].trim();
            int zoneId = Integer.parseInt(parts[1].trim());
            String eventType = parts[2].trim();
            int severity = parseSeverity(parts[3].trim());
            String faultType = Incident.NO_FAULT;
            String faultTargetType = Incident.NO_FAULT;
            String faultTargetId = Incident.NO_FAULT;

            if (parts.length == 7) {
                faultType = normalizeFaultToken(parts[4]);
                faultTargetType = normalizeFaultToken(parts[5]);
                faultTargetId = normalizeFaultToken(parts[6]);

                if (!isAllowedFaultType(faultType)) {
                    throw new IllegalArgumentException("Invalid fault type: " + faultType);
                }
                if (!ALLOWED_FAULT_TARGET_TYPES.contains(faultTargetType)) {
                    throw new IllegalArgumentException("Invalid fault target type: " + faultTargetType);
                }
                if ("NONE".equals(faultTargetType)) {
                    faultTargetId = Incident.NO_FAULT;
                }
            }

            return new Incident(time, zoneId, eventType, severity, faultType, faultTargetType, faultTargetId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format in line " + lineNumber + ": " + e.getMessage());
        }
    }

    private String normalizeFaultToken(String value) {
        if (value == null) {
            return Incident.NO_FAULT;
        }
        String normalized = value.trim().toUpperCase();
        return normalized.isEmpty() ? Incident.NO_FAULT : normalized;
    }
    private boolean isAllowedFaultType(String faultType){
        if (ALLOWED_FAULT_TYPES.contains(faultType)) {
            return true;
        }
        return faultType.contains("ARRIVAL")
                || faultType.contains("SENSOR")
                || faultType.contains("BAY")
                || faultType.contains("DOOR");
    }
    /**
     * Converts severity to litres of water/foam needed (per spec: Low=10 L, Moderate=20 L, High=30 L).
     * Accepts words "High", "Moderate", "Low" or numeric litres 10, 20, 30.
     */
    private int parseSeverity(String severityStr) {
        String normalized = severityStr.trim().toLowerCase();
        switch (normalized) {
            case "high":
                return 30;
            case "moderate":
                return 20;
            case "low":
                return 10;
            default:
                break;
        }
        try {
            int litres = Integer.parseInt(severityStr.trim());
            if (litres == 10 || litres == 20 || litres == 30) {
                return litres;
            }
            throw new IllegalArgumentException("Severity must be 10, 20, or 30 (litres), or High/Moderate/Low, got: " + severityStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unknown severity value: " + severityStr +
                    ". Expected High (30 L), Moderate (20 L), Low (10 L), or 10/20/30");
        }
    }
}
