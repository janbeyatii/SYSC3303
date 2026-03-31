package fireincident;

import model.DroneTelemetry;
import model.Incident;
import model.ZoneConfig;
import fireincident.udp.MessageType;
import fireincident.udp.Ports;
import fireincident.udp.UDPMessage;
import fireincident.udp.DronePacketBuilder;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Central coordinator for fire incidents and drones: FIFO queueing, dispatch, completion callbacks,
 * and Iteration 4 fault handling ({@link #onDroneFaultDetected}).
 * <p>
 * Two modes: <b>in-process</b> ({@code udpEnabled == false}) for unit tests with direct
 * {@link #receiveIncident} / {@link #requestWork} calls; <b>UDP</b> ({@code true}) for distributed
 * processes using {@link UDPMessage} on {@link Ports#SCHEDULER} plus optional binary channel protocol
 * for pull-based drones.
 */
public class Scheduler implements Runnable, SchedulerInterface {
    DatagramPacket sendPacket, receivePacket;
    DatagramSocket sendSocket, receiveSocket;

    private static class Job {
        final Incident incident;

        Job(Incident incident) {
            this.incident = incident;
        }
    }

    public enum FireState { PENDING, ASSIGNED, COMPLETED }
    public enum SchedulerState { IDLE, HAS_PENDING, DRONE_BUSY }

    private SchedulerState schedulerState = SchedulerState.IDLE;
    private final Map<String, FireState> fireStates = new HashMap<>();
    private final Map<String, IncidentCallback> callbacksByKey = new HashMap<>();
    /** FIFO queue of all pending jobs (spec/Iteration 2: process incidents in arrival order). */
    private final LinkedList<Job> fifoQueue = new LinkedList<>();
    /** Per zone+severity for tracking; dispatch order follows fifoQueue. */
    private final Map<Integer, Map<Integer, LinkedList<Job>>> queuesByZoneAndSeverity = new HashMap<>();
    private final Map<Integer, Job> inProgressByZone = new HashMap<>();
    private final Map<Integer, Job> inProgressByDrone = new HashMap<>();
    private final List<SchedulerListener> listeners = new ArrayList<>();
    private final Map<Integer, String> droneStateById = new HashMap<>();
    private final Map<Integer, Integer> droneZoneById = new HashMap<>();
    private final Map<Integer, DroneTelemetry> droneTelemetryById = new HashMap<>();
    private final LinkedList<Integer> idleDrones = new LinkedList<>();
    /** For channel protocol: where to send ASSIGN to each drone (enables multiple drone processes). */
    private final Map<Integer, InetSocketAddress> droneAddresses = new HashMap<>();

    private volatile boolean running = true;
    private final boolean udpEnabled;
    private volatile boolean fireIncidentFinished = false;
    private final ZoneConfig zoneConfig;

    /** Test helper: in-process scheduler without UDP sockets. */
    public Scheduler() {
        this(false);
    }

    /**
     * @param udpEnabled if {@code true}, binds {@link Ports#SCHEDULER} and processes {@link UDPMessage} traffic
     */
    public Scheduler(boolean udpEnabled) {
        this(udpEnabled, new ZoneConfig());
    }

    /**
     * @param zoneConfig used for distance-based drone selection when multiple drones are idle
     */
    public Scheduler(boolean udpEnabled, ZoneConfig zoneConfig) {
        this.udpEnabled = udpEnabled;
        this.zoneConfig = zoneConfig != null ? zoneConfig : new ZoneConfig();
        if (!udpEnabled) {
            return;
        }
        try {
            sendSocket = new DatagramSocket();
            receiveSocket = new DatagramSocket(Ports.SCHEDULER);
            fireLog("[Scheduler] Bound to port " + Ports.SCHEDULER);
            fireLog("[Scheduler] Waiting for packets...\n");
        } catch (IOException e) {
            String hint = (e instanceof java.net.BindException)
                    ? " Port " + Ports.SCHEDULER + " is already in use. Close any other running Scheduler/Main/Drone process or wait a few seconds and try again."
                    : "";
            throw new IllegalStateException("Failed to initialize scheduler sockets." + hint, e);
        }
    }

    /**
     * UDP receive loop: deserialize {@link UDPMessage}, route to handlers, or channel protocol lines.
     */
    @Override
    public void run() {
        if (!udpEnabled || receiveSocket == null) {
            return;
        }
        while (running) {
            try {
                byte[] data = new byte[UDPMessage.MAX_SIZE];
                receivePacket = new DatagramPacket(data, data.length);
                receiveSocket.receive(receivePacket);
                int len = receivePacket.getLength();
                String line = new String(data, 0, len, StandardCharsets.UTF_8).trim();
                InetAddress fromAddr = receivePacket.getAddress();
                int fromPort = receivePacket.getPort();

                String firstToken = line.contains("|") ? line.substring(0, line.indexOf('|')) : line;
                try {
                    MessageType.valueOf(firstToken);
                    UDPMessage msg = UDPMessage.fromString(line);
                    handleMessage(msg);
                } catch (IllegalArgumentException e) {
                    handleChannelMessage(line, fromAddr, fromPort);
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
        fireLog("[Scheduler] Stopped.");
    }

    private synchronized void handleMessage(UDPMessage msg) throws IOException {
        switch (msg.getType()) {
            case INCIDENT_REPORT:
                handleIncidentReport(msg);
                break;

            case DRONE_ARRIVED:
                handleDroneArrived(msg);
                break;

            case DRONE_DROPPED_AGENT:
                handleDroneDroppedAgent(msg);
                break;

            case DRONE_RETURNING:
                handleDroneReturning(msg);
                break;

            case DRONE_IDLE:
                handleDroneIdle(msg);
                break;

            case DRONE_STATE:
                handleDroneState(msg);
                break;

            case NO_MORE_INCIDENTS:
                fireIncidentFinished = true;
                fireLog("[Scheduler] Fire Incident Subsystem finished; no more incidents.");
                checkShutdown();
                break;

            case SHUTDOWN:
                running = false;
                break;

            default:
                fireLog("[Scheduler] Unknown packet: " + msg.getType());
        }
    }

    private void handleIncidentReport(UDPMessage msg) throws IOException {
        Incident incident = new Incident(
                msg.getField(0),
                Integer.parseInt(msg.getField(1)),
                msg.getField(2),
                Integer.parseInt(msg.getField(3)),
                msg.getField(4),
                msg.getField(5),
                msg.getField(6)
        );
        addToQueue(new Job(incident));
        fireStates.put(incident.getKey(), FireState.PENDING);
        schedulerState = SchedulerState.HAS_PENDING;
        fireLog("[Scheduler] Queued incident: " + incident);
        fireIncidentQueued(incident);
        dispatchPending();
    }

    private void handleDroneArrived(UDPMessage msg) {
        int droneId = Integer.parseInt(msg.getField(0));
        String key = incidentKeyFromDroneKeyPayload(msg);
        int zoneId = zoneIdFromIncidentKey(key);
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone for " + key);
        updateDroneState(droneId, DroneState.ARRIVED.name(), zoneId);
    }

    private void handleDroneDroppedAgent(UDPMessage msg) throws IOException {
        int droneId = Integer.parseInt(msg.getField(0));
        String key = incidentKeyFromDroneKeyPayload(msg);
        int zoneId = zoneIdFromIncidentKey(key);
        Job job = inProgressByDrone.remove(droneId);
        if (job != null) {
            inProgressByZone.remove(job.incident.getZoneId());
        } else {
            job = inProgressByZone.remove(zoneId);
        }
        fireLog("[Scheduler] Drone " + droneId + " finished at: " + key);
        if (job != null) {
            fireStates.put(job.incident.getKey(), FireState.COMPLETED);
            fireIncidentCompleted(droneId, job.incident);
            sendToPort(UDPMessage.incidentCompleted(job.incident), Ports.FIRE_IS);
        }
        schedulerState = hasAnyPending() ? SchedulerState.HAS_PENDING : SchedulerState.IDLE;
        dispatchPending();
        checkShutdown();
    }

    private void handleDroneReturning(UDPMessage msg) {
        int droneId = Integer.parseInt(msg.getField(0));
        fireLog("[Scheduler] Drone " + droneId + " returning to base.");
        updateDroneState(droneId, DroneState.RETURNING.name(), null);
    }

    private void handleDroneIdle(UDPMessage msg) throws IOException {
        int droneId = Integer.parseInt(msg.getField(0));
        fireLog("[Scheduler] Drone " + droneId + " is now idle at base (zone 0)");
        if (!idleDrones.contains(droneId)) {
            idleDrones.add(droneId);
        }
        updateDroneState(droneId, DroneState.IDLE.name(), 0); // Idle = at base (zone 0)
        dispatchPending();
    }

    private void handleDroneState(UDPMessage msg) {
        if (msg.getFieldCount() >= 9) {
            DroneTelemetry dt = parseDroneTelemetryFromUdp(msg);
            applyTelemetryAndMaybeUpdateState(dt);
            return;
        }
        int droneId = Integer.parseInt(msg.getField(0));
        String state = msg.getField(1);
        String zoneStr = msg.getField(2);
        Integer zoneId = zoneStr.isEmpty() ? null : Integer.parseInt(zoneStr);
        updateDroneState(droneId, state, zoneId);
    }

    private void handleChannelMessage(String line, InetAddress fromAddr, int fromPort) throws IOException {
        String[] parts = line.split("\\|", -1);
        if (parts.length == 0) return;
        String type = parts[0].trim();
        switch (type) {
            case "REQUEST_WORK":
                handleChannelRequestWork(parts, fromAddr, fromPort);
                break;
            case "REPORT_ARRIVAL":
                handleChannelReportArrival(parts, fromAddr, fromPort);
                break;
            case "REPORT_COMPLETION":
                handleChannelReportCompletion(parts, fromAddr, fromPort);
                break;
            case "REPORT_RETURN":
                handleChannelReportReturn(parts, fromAddr, fromPort);
                break;
            case "REPORT_STATE":
                handleChannelReportState(parts, fromAddr, fromPort);
                break;
            case "PEEK":
                handleChannelPeek(fromAddr, fromPort);
                break;
            default:
                fireLog("[Scheduler] Unknown channel packet: " + type);
        }
    }

    private void recordDroneAddress(int droneId, InetAddress fromAddr, int fromPort) {
        droneAddresses.put(droneId, new InetSocketAddress(fromAddr, fromPort));
    }

    private void handleChannelRequestWork(String[] parts, InetAddress fromAddr, int fromPort) throws IOException {
        if (parts.length < 2) return;
        int droneId = Integer.parseInt(parts[1].trim());
        recordDroneAddress(droneId, fromAddr, fromPort);
        if (!idleDrones.contains(droneId)) {
            idleDrones.add(droneId);
        }
        int currentZone = 0;
        int agentRemaining = 100;
        if (parts.length >= 4) {
            try {
                currentZone = Integer.parseInt(parts[2].trim());
                agentRemaining = Integer.parseInt(parts[3].trim());
                if (agentRemaining < 0) agentRemaining = 0;
            } catch (NumberFormatException ignored) { }
        }
        updateDroneState(droneId, DroneState.IDLE.name(), currentZone);
        Job job = pollNextJobForDrone(droneId, agentRemaining);
        if (job != null) {
            idleDrones.remove((Integer) droneId);
            inProgressByZone.put(job.incident.getZoneId(), job);
            inProgressByDrone.put(droneId, job);
            fireStates.put(job.incident.getKey(), FireState.ASSIGNED);
            schedulerState = SchedulerState.DRONE_BUSY;
            fireLog("[Scheduler] Dispatching Drone " + droneId + " to Incident: " + job.incident);
            sendToAddress(DronePacketBuilder.assignIncident(job.incident), fromAddr, fromPort);
            fireIncidentDispatched(droneId, job.incident);
            dispatchPending();
        } else {
            sendToAddress(DronePacketBuilder.assignNoWork(), fromAddr, fromPort);
        }
    }

    private void handleChannelReportArrival(String[] parts, InetAddress fromAddr, int fromPort) throws IOException {
        if (parts.length < 6) return;
        int droneId = Integer.parseInt(parts[1].trim());
        recordDroneAddress(droneId, fromAddr, fromPort);
        Incident incident = new Incident(parts[2].trim(), Integer.parseInt(parts[3].trim()), parts[4].trim(), Integer.parseInt(parts[5].trim()));
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone for " + incident.getKey());
        updateDroneState(droneId, DroneState.ARRIVED.name(), incident.getZoneId());
        sendToAddress(DronePacketBuilder.ack(), fromAddr, fromPort);
    }

    private void handleChannelReportCompletion(String[] parts, InetAddress fromAddr, int fromPort) throws IOException {
        if (parts.length < 6) return;
        int droneId = Integer.parseInt(parts[1].trim());
        recordDroneAddress(droneId, fromAddr, fromPort);
        Incident incident = new Incident(parts[2].trim(), Integer.parseInt(parts[3].trim()), parts[4].trim(), Integer.parseInt(parts[5].trim()));
        Job job = inProgressByDrone.remove(droneId);
        if (job != null) {
            inProgressByZone.remove(job.incident.getZoneId());
        } else {
            job = inProgressByZone.remove(incident.getZoneId());
        }
        fireLog("[Scheduler] Drone " + droneId + " finished at: " + incident.getKey());
        if (job != null) {
            fireStates.put(job.incident.getKey(), FireState.COMPLETED);
            fireIncidentCompleted(droneId, job.incident);
            sendToPort(UDPMessage.incidentCompleted(job.incident), Ports.FIRE_IS);
        }
        schedulerState = hasAnyPending() ? SchedulerState.HAS_PENDING : SchedulerState.IDLE;
        dispatchPending();
        checkShutdown();
        sendToAddress(DronePacketBuilder.ack(), fromAddr, fromPort);
    }

    private void handleChannelReportReturn(String[] parts, InetAddress fromAddr, int fromPort) throws IOException {
        if (parts.length < 2) return;
        int droneId = Integer.parseInt(parts[1].trim());
        recordDroneAddress(droneId, fromAddr, fromPort);
        fireLog("[Scheduler] Drone " + droneId + " returning to base.");
        updateDroneState(droneId, DroneState.RETURNING.name(), null);
        sendToAddress(DronePacketBuilder.ack(), fromAddr, fromPort);
    }

    private void handleChannelReportState(String[] parts, InetAddress fromAddr, int fromPort) throws IOException {
        if (parts.length < 3) return;
        int droneId = Integer.parseInt(parts[1].trim());
        recordDroneAddress(droneId, fromAddr, fromPort);
        if (parts.length >= 10) {
            DroneTelemetry dt = parseDroneTelemetryFromChannel(parts);
            applyTelemetryAndMaybeUpdateState(dt);
            if (DroneState.IDLE.name().equals(dt.state()) && !idleDrones.contains(droneId)) {
                idleDrones.add(droneId);
            }
            dispatchPending();
            checkShutdown();
            sendToAddress(DronePacketBuilder.ack(), fromAddr, fromPort);
            return;
        }
        String state = parts[2].trim();
        String zoneStr = parts.length > 3 ? parts[3].trim() : "";
        Integer zoneId = zoneStr.isEmpty() ? null : Integer.parseInt(zoneStr);
        if (DroneState.IDLE.name().equals(state)) zoneId = 0; // Idle = at base
        updateDroneState(droneId, state, zoneId);
        if (state.equals(DroneState.IDLE.name()) && !idleDrones.contains(droneId)) {
            idleDrones.add(droneId);
        }
        dispatchPending();
        checkShutdown();
        sendToAddress(DronePacketBuilder.ack(), fromAddr, fromPort);
    }

    private void handleChannelPeek(InetAddress fromAddr, int fromPort) throws IOException {
        Incident next = peekNextIncident();
        String line = next != null
                ? "PEEK_RESP|" + next.getTime() + "|" + next.getZoneId() + "|" + next.getEventType() + "|" + next.getSeverity()
                : "PEEK_RESP";
        sendToAddress(line.getBytes(StandardCharsets.UTF_8), fromAddr, fromPort);
    }

    private void checkShutdown() {
        if (!udpEnabled || sendSocket == null) return;
        if (!fireIncidentFinished || hasAnyPending() || !inProgressByZone.isEmpty()) return;
        if (!allDronesIdle()) return;
        fireLog("[Scheduler] All fires out, all drones at base. Shutting down.");
        for (SchedulerListener l : listeners) l.onSimulationComplete();
        sendShutdownToDrones();
        running = false;
    }

    private boolean allDronesIdle() {
        if (droneStateById.isEmpty()) return false;
        for (String state : droneStateById.values()) {
            if (state == null) return false;
            if (!DroneState.IDLE.name().equals(state)
                    && !DroneState.OFFLINE.name().equals(state)
                    && !DroneState.UNAVAILABLE.name().equals(state)) return false;
        }
        return true;
    }

    private void sendShutdownToDrones() {
        try {
            sendToPort(UDPMessage.shutdown(), Ports.DRONE_SS);
        } catch (IOException e) {
            System.err.println("[Scheduler] Failed to send SHUTDOWN to drone port: " + e.getMessage());
        }
        for (Integer droneId : droneAddresses.keySet()) {
            try {
                InetSocketAddress addr = droneAddresses.get(droneId);
                if (addr != null)
                    sendToAddress(UDPMessage.shutdown().toBytes(), addr.getAddress(), addr.getPort());
            } catch (IOException e) {
                System.err.println("[Scheduler] Failed to send SHUTDOWN to drone " + droneId + ": " + e.getMessage());
            }
        }
    }

    /** Extracts incident key (time|zoneId|eventType) from UDPMessage fields starting at given index. */
    private static String incidentKeyFromFields(UDPMessage msg, int start) {
        return msg.getField(start) + "|" + msg.getField(start + 1) + "|" + msg.getField(start + 2);
    }

    /**
     * DRONE_ARRIVED / DRONE_DROPPED_AGENT: legacy wire had droneId|time|zone|type (4 fields);
     * compact wire has droneId|fullKey (2 fields) when key is sent as one token (with b64 on wire).
     */
    private static String incidentKeyFromDroneKeyPayload(UDPMessage msg) {
        if (msg.getFieldCount() >= 4 && !msg.getField(3).isEmpty()) {
            return incidentKeyFromFields(msg, 1);
        }
        return msg.getField(1);
    }

    private static int zoneIdFromIncidentKey(String key) {
        String[] p = key.split("\\|", 3);
        return Integer.parseInt(p[1].trim());
    }

    /** Returns the droneId that was assigned work, or null if none. Only assigns to push drones (no address). */
    private Integer dispatchPending() throws IOException {
        System.out.println("[Scheduler] Dispatching pending incidents...");
        Integer lastAssigned = null;

        while (hasAnyPending() && !idleDrones.isEmpty()) {
            NextJobResult next = getNextJobForDispatch();
            if (next == null) break;

            int droneId = selectBestPushDrone(next.zoneId);
            if (droneId == -1) break;

            idleDrones.remove((Integer) droneId);
            inProgressByZone.put(next.job.incident.getZoneId(), next.job);
            inProgressByDrone.put(droneId, next.job);
            fireStates.put(next.job.incident.getKey(), FireState.ASSIGNED);
            schedulerState = SchedulerState.DRONE_BUSY;

            fireLog("[Scheduler] Dispatching Drone " + droneId + " to Incident: " + next.job.incident);
            sendToPort(UDPMessage.dispatchDrone(droneId, next.job.incident), Ports.DRONE_SS);
            fireIncidentDispatched(droneId, next.job.incident);
            lastAssigned = droneId;
        }

        return lastAssigned;
    }

    private static class NextJobResult {
        final Job job;
        final int zoneId;
        NextJobResult(Job job, int zoneId) { this.job = job; this.zoneId = zoneId; }
    }

    private void addToQueue(Job job) {
        int zoneId = job.incident.getZoneId();
        int severity = job.incident.getSeverity();
        queuesByZoneAndSeverity.computeIfAbsent(zoneId, k -> new HashMap<>())
                .computeIfAbsent(severity, k -> new LinkedList<>())
                .addLast(job);
        fifoQueue.addLast(job);
    }

    private boolean hasAnyPending() {
        return !fifoQueue.isEmpty();
    }

    /** FIFO: picks oldest pending job, assigns to closest idle drone. Returns null if none dispatchable. */
    private NextJobResult getNextJobForDispatch() {
        Job job = fifoQueue.peekFirst();
        if (job == null) return null;
        int zoneId = job.incident.getZoneId();
        int droneId = selectBestPushDrone(zoneId);
        if (droneId == -1) return null;
        removeJobFromQueue(job);
        return new NextJobResult(job, zoneId);
    }

    /** FIFO: gives drone the oldest job it can handle (agentRemaining >= severity). */
    private Job pollNextJobForDrone(int droneId, int agentRemaining) {
        for (Job job : fifoQueue) {
            if (job.incident.getSeverity() <= agentRemaining) {
                removeJobFromQueue(job);
                return job;
            }
        }
        return null;
    }

    private void removeJobFromQueue(Job job) {
        int zoneId = job.incident.getZoneId();
        int severity = job.incident.getSeverity();
        Map<Integer, LinkedList<Job>> bySeverity = queuesByZoneAndSeverity.get(zoneId);
        if (bySeverity != null) {
            LinkedList<Job> q = bySeverity.get(severity);
            if (q != null) q.removeFirstOccurrence(job);
        }
        fifoQueue.removeFirstOccurrence(job);
    }

    private void requeueJob(Job job) {
        int zoneId = job.incident.getZoneId();
        int severity = job.incident.getSeverity();
        queuesByZoneAndSeverity.computeIfAbsent(zoneId, k -> new HashMap<>())
                .computeIfAbsent(severity, k -> new LinkedList<>())
                .addFirst(job);
        fifoQueue.addFirst(job);
    }

    private int selectBestPushDrone(int zoneId) {
        int best = -1;
        double minDist = Double.MAX_VALUE;
        for (int droneId : idleDrones) {
            if (droneAddresses.containsKey(droneId)) continue;
            Integer zone = droneZoneById.get(droneId);
            if (zone == null) zone = 0;
            double d;
            if (zoneConfig.hasZone(zone) && zoneConfig.hasZone(zoneId)) {
                d = zoneConfig.getDistanceMeters(zone, zoneId);
            } else {
                d = Math.abs(zone - zoneId) * 100.0; // fallback
            }
            if (d < minDist) {
                minDist = d;
                best = droneId;
            }
        }
        return best;
    }

    private void sendToAddress(byte[] data, InetAddress addr, int port) throws IOException {
        if (sendSocket == null) return;
        sendPacket = new DatagramPacket(data, data.length, addr, port);
        sendSocket.send(sendPacket);
    }

    private void sendToPort(UDPMessage message, int port) throws IOException {
        if (sendSocket == null) {
            return;
        }
        byte[] data = message.toBytes();
        sendPacket = new DatagramPacket(data, data.length, InetAddress.getLocalHost(), port);
        sendSocket.send(sendPacket);
    }

    /**
     * In-process path: full drone snapshot from {@link DroneSubsystem} (no UDP).
     */
    public synchronized void applyDroneTelemetry(DroneTelemetry t) {
        applyTelemetryAndMaybeUpdateState(t);
    }

    private void applyTelemetryAndMaybeUpdateState(DroneTelemetry dt) {
        droneTelemetryById.put(dt.droneId(), dt);
        fireDroneTelemetryUpdated(dt);
        String prev = droneStateById.get(dt.droneId());
        if (prev == null || !prev.equals(dt.state())) {
            updateDroneState(dt.droneId(), dt.state(), dt.zoneId());
        } else if (dt.zoneId() != null) {
            droneZoneById.put(dt.droneId(), dt.zoneId());
        }
    }

    private DroneTelemetry parseDroneTelemetryFromUdp(UDPMessage msg) {
        int droneId = Integer.parseInt(msg.getField(0));
        String state = msg.getField(1);
        String zoneStr = msg.getField(2);
        Integer zoneId = zoneStr.isEmpty() ? null : Integer.parseInt(zoneStr);
        int agentRem = Integer.parseInt(msg.getField(3));
        int agentMax = Integer.parseInt(msg.getField(4));
        double batRem = Double.parseDouble(msg.getField(5));
        double batMax = Double.parseDouble(msg.getField(6));
        String dzStr = msg.getField(7);
        Integer dest = dzStr.isEmpty() ? null : Integer.parseInt(dzStr);
        String distStr = msg.getField(8);
        Double dist = distStr.isEmpty() ? null : Double.parseDouble(distStr);
        if (DroneState.IDLE.name().equals(state)) {
            zoneId = 0;
        }
        return new DroneTelemetry(droneId, state, zoneId, agentRem, agentMax, batRem, batMax, dest, dist);
    }

    private static DroneTelemetry parseDroneTelemetryFromChannel(String[] parts) {
        int droneId = Integer.parseInt(parts[1].trim());
        String state = parts[2].trim();
        String zoneStr = parts[3].trim();
        Integer zoneId = zoneStr.isEmpty() ? null : Integer.parseInt(zoneStr);
        int agentRem = Integer.parseInt(parts[4].trim());
        int agentMax = Integer.parseInt(parts[5].trim());
        double batRem = Double.parseDouble(parts[6].trim());
        double batMax = Double.parseDouble(parts[7].trim());
        String dz = parts[8].trim();
        Integer dest = dz.isEmpty() ? null : Integer.parseInt(dz);
        String distStr = parts[9].trim();
        Double dist = distStr.isEmpty() ? null : Double.parseDouble(distStr);
        if (DroneState.IDLE.name().equals(state)) {
            zoneId = 0;
        }
        return new DroneTelemetry(droneId, state, zoneId, agentRem, agentMax, batRem, batMax, dest, dist);
    }

    /**
     * Updates drone state and zone; if state is {@link DroneState#UNAVAILABLE} or {@link DroneState#OFFLINE},
     * re-queues any in-progress incident for that drone.
     *
     * @param droneId logical drone id
     * @param state   {@link DroneState} name
     * @param zoneId  current zone, or {@code null} if unknown / in transit
     */
    public synchronized void updateDroneState(int droneId, String state, Integer zoneId) {
        if (state != null) {
            droneStateById.put(droneId, state);
            if (DroneState.IDLE.name().equals(state)) {
                if (!idleDrones.contains(droneId)) {
                    idleDrones.add(droneId);
                }
            } else {
                idleDrones.remove((Integer) droneId);
            }
            if (DroneState.UNAVAILABLE.name().equals(state) || DroneState.OFFLINE.name().equals(state)) {
                Job job = inProgressByDrone.remove(droneId);
                if (job != null) {
                    inProgressByZone.remove(job.incident.getZoneId());
                    fireStates.put(job.incident.getKey(), FireState.PENDING);
                    requeueJob(job);
                    schedulerState = SchedulerState.HAS_PENDING;
                    fireLog("[Scheduler] Re-queued incident after Drone " + droneId + " became " + state + ": " + job.incident);
                    notifyAll();
                }
            }
        }
        if (zoneId != null) droneZoneById.put(droneId, zoneId);
        fireDroneStateChanged(droneId, state, zoneId);
    }

    /** @return number of pending incidents not yet assigned */
    public synchronized int getQueueSize() {
        return fifoQueue.size();
    }

    /** @return number of incidents currently assigned to drones */
    public synchronized int getInProgressCount() {
        return inProgressByZone.size();
    }

    /** @return lifecycle state for the given incident, or {@code null} if unknown */
    public synchronized FireState getFireState(Incident incident) {
        return fireStates.get(incident.getKey());
    }

    /** @return coarse scheduler mode for tests and diagnostics */
    public synchronized SchedulerState getSchedulerState() {
        return schedulerState;
    }

    private void fireIncidentQueued(Incident incident) {
        for (SchedulerListener l : listeners) l.onIncidentQueued(incident);
    }

    private void fireIncidentDispatched(int droneId, Incident incident) {
        for (SchedulerListener l : listeners) l.onIncidentDispatched(droneId, incident);
    }

    private void fireIncidentCompleted(int droneId, Incident incident) {
        for (SchedulerListener l : listeners) l.onIncidentCompleted(droneId, incident);
    }

    private void fireDroneStateChanged(int droneId, String state, Integer zoneId) {
        for (SchedulerListener l : listeners) l.onDroneStateChanged(droneId, state, zoneId);
    }

    private void fireDroneTelemetryUpdated(DroneTelemetry t) {
        for (SchedulerListener l : listeners) l.onDroneTelemetryUpdated(t);
    }

    private void fireLog(String message) {
        System.out.println(message);
        for (SchedulerListener l : listeners) l.onLog(message);
    }

    /** @return next FIFO incident without removing it, or {@code null} */
    public synchronized Incident peekNextIncident() {
        Job j = fifoQueue.peekFirst();
        return j != null ? j.incident : null;
    }

    /**
     * Called when Fire subsystem has finished reading the input file.
     */
    @Override
    public synchronized void signalNoMoreIncidents() {
        fireIncidentFinished = true;
        try {
            checkShutdown();
        } catch (Exception ignored) { }
    }

    /**
     * Enqueues an incident (used by in-process tests and indirectly by UDP path).
     *
     * @param callback optional completion callback when the fire is fully handled
     */
    @Override
    public synchronized void receiveIncident(Incident incident, IncidentCallback callback) {
        addToQueue(new Job(incident));
        fireStates.put(incident.getKey(), FireState.PENDING);
        if (callback != null) {
            callbacksByKey.put(incident.getKey(), callback);
        }
        schedulerState = SchedulerState.HAS_PENDING;
        fireLog("[Scheduler] Queued incident: " + incident);
        fireIncidentQueued(incident);
        notifyAll();
    }

    /** Drone requests work assuming default zone 0 and full agent tank. */
    public synchronized Incident requestWork(int droneId) {
        return requestWork(droneId, 0, 100);
    }

    /**
     * Pull-based assignment: waits until a pending incident exists, then returns one compatible
     * with {@code agentRemaining} (in-process / channel drones).
     */
    public synchronized Incident requestWork(int droneId, int currentZone, int agentRemaining) {
        updateDroneState(droneId, DroneState.IDLE.name(), currentZone);
        while (!hasAnyPending()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Job job = pollNextJobForDrone(droneId, agentRemaining);
        if (job == null) return null;
        inProgressByZone.put(job.incident.getZoneId(), job);
        inProgressByDrone.put(droneId, job);
        fireStates.put(job.incident.getKey(), FireState.ASSIGNED);
        schedulerState = SchedulerState.DRONE_BUSY;
        fireLog("[Scheduler] Dispatched incident to Drone " + droneId + ": " + job.incident);
        fireIncidentDispatched(droneId, job.incident);
        return job.incident;
    }

    public synchronized void reportCompletion(int droneId, Incident incident) {
        Job job = inProgressByDrone.remove(droneId);
        if (job != null) {
            inProgressByZone.remove(job.incident.getZoneId());
        } else {
            inProgressByZone.remove(incident.getZoneId());
        }
        fireStates.put(incident.getKey(), FireState.COMPLETED);
        fireLog("[Scheduler] Completion from Drone " + droneId + ": " + incident);
        fireIncidentCompleted(droneId, incident);
        IncidentCallback callback = callbacksByKey.remove(incident.getKey());
        if (callback != null) {
            callback.onIncidentCompleted(incident);
        }
        schedulerState = hasAnyPending() ? SchedulerState.HAS_PENDING : SchedulerState.IDLE;
        notifyAll();
    }

    /** In-process path: drone arrived at zone. */
    public synchronized void reportArrival(int droneId, Incident incident) {
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone " + incident.getZoneId());
        updateDroneState(droneId, DroneState.ARRIVED.name(), incident.getZoneId());
    }

    /** In-process path: drone heading back to base. */
    public synchronized void reportReturnToBase(int droneId) {
        fireLog("[Scheduler] Drone " + droneId + " returning to base");
        updateDroneState(droneId, DroneState.RETURNING.name(), null);
    }

    public synchronized void addListener(SchedulerListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /** @return last known {@link DroneState} name, or {@code null} */
    public synchronized String getDroneState(int droneId) {
        return droneStateById.get(droneId);
    }

    /** @return last known zone index for the drone */
    public synchronized Integer getDroneZone(int droneId) {
        return droneZoneById.get(droneId);
    }

    /**
     * Clears all incident queues, in-progress assignments, and callbacks for a fresh simulation run
     * (e.g. GUI "Restart"). Known drones are marked {@link DroneState#IDLE} at zone 0.
     * If the UDP receive loop had stopped after a completed run, it is started again.
     */
    public synchronized void resetForNewSimulationRun() {
        fifoQueue.clear();
        queuesByZoneAndSeverity.clear();
        fireStates.clear();
        callbacksByKey.clear();
        inProgressByZone.clear();
        inProgressByDrone.clear();
        fireIncidentFinished = false;
        schedulerState = SchedulerState.IDLE;

        HashSet<Integer> known = new HashSet<>();
        known.addAll(idleDrones);
        known.addAll(droneAddresses.keySet());
        known.addAll(droneStateById.keySet());
        droneTelemetryById.clear();
        idleDrones.clear();
        for (Integer id : known) {
            idleDrones.add(id);
            droneStateById.put(id, DroneState.IDLE.name());
            droneZoneById.put(id, 0);
        }
        notifyAll();

        if (udpEnabled && receiveSocket != null && !receiveSocket.isClosed() && !running) {
            running = true;
            Thread t = new Thread(this, "Scheduler");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Stops UDP receive loop and closes sockets. */
    public synchronized void shutdown() {
        running = false;
        notifyAll();
        if (receiveSocket != null && !receiveSocket.isClosed()) {
            receiveSocket.close();
        }
        if (sendSocket != null && !sendSocket.isClosed()) {
            sendSocket.close();
        }
    }
    private synchronized void handleFaultyDrone(int droneId, boolean isHardFault) {
        // Remove the faulty drone from idleDrones to prevent future dispatch
        idleDrones.remove((Integer) droneId);

        // Capture the job once before removing it elsewhere
        Job job = inProgressByDrone.get(droneId);

        if (isHardFault) {
            System.out.println("[Scheduler] Drone " + droneId + " marked as OFFLINE due to a hard fault.");
            // Update the drone state to OFFLINE
            updateDroneState(droneId, DroneState.OFFLINE.name(), null);
        } else {
            System.out.println("[Scheduler] Drone " + droneId + " marked as UNAVAILABLE temporarily.");
            // Update the drone state to UNAVAILABLE
            updateDroneState(droneId, DroneState.UNAVAILABLE.name(), null);
        }

        // Reassign jobs handled by the faulty drone
        if (job != null) {
            fireLog("[Scheduler] Re-queued incident after Drone " + droneId + " fault: " + job.incident);
        }

        // Attempt to dispatch pending jobs
        try {
            dispatchPending();
        } catch (IOException e) {
            System.err.println("[Scheduler] Error dispatching pending incidents: " + e.getMessage());
        }
    }

    /**
     * Iteration 4: notify scheduler of a drone fault; soft faults mark {@link DroneState#UNAVAILABLE},
     * hard faults {@link DroneState#OFFLINE}, then re-dispatch pending work.
     *
     * @param isHardFault {@code true} for nozzle/bay-door style faults that retire the drone
     */
    public synchronized void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault) {
        fireLog("[Scheduler] Drone " + droneId + " fault detected: " + faultMessage);
        notifyListenersDroneFault(droneId, faultMessage, isHardFault);
        handleFaultyDrone(droneId, isHardFault);
    }

    private void notifyListenersDroneFault(int droneId, String faultMessage, boolean isHardFault) {
        for (SchedulerListener listener : listeners) {
            listener.onDroneFaultDetected(droneId, faultMessage, isHardFault);
        }
    }

    private void notifyListenersDroneState(int droneId, String state) {
        for (SchedulerListener listener : listeners) {
            listener.onDroneStateChanged(droneId, state, null);
        }
    }


}
