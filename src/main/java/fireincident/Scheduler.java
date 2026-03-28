package fireincident;

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
    private final LinkedList<Integer> idleDrones = new LinkedList<>();
    /** For channel protocol: where to send ASSIGN to each drone (enables multiple drone processes). */
    private final Map<Integer, InetSocketAddress> droneAddresses = new HashMap<>();

    private volatile boolean running = true;
    private final boolean udpEnabled;
    private volatile boolean fireIncidentFinished = false;
    private final ZoneConfig zoneConfig;

    public Scheduler() {
        this(false);
    }

    public Scheduler(boolean udpEnabled) {
        this(udpEnabled, new ZoneConfig());
    }

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
        int zoneId = Integer.parseInt(msg.getField(2));
        String key = incidentKeyFromFields(msg, 1);
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone for " + key);
        updateDroneState(droneId, DroneState.ARRIVED.name(), zoneId);
    }

    private void handleDroneDroppedAgent(UDPMessage msg) throws IOException {
        int droneId = Integer.parseInt(msg.getField(0));
        int zoneId = Integer.parseInt(msg.getField(2));
        String key = incidentKeyFromFields(msg, 1);
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

    public synchronized int getQueueSize() {
        return fifoQueue.size();
    }

    public synchronized int getInProgressCount() {
        return inProgressByZone.size();
    }

    public synchronized FireState getFireState(Incident incident) {
        return fireStates.get(incident.getKey());
    }

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

    private void fireLog(String message) {
        System.out.println(message);
        for (SchedulerListener l : listeners) l.onLog(message);
    }

    public synchronized Incident peekNextIncident() {
        Job j = fifoQueue.peekFirst();
        return j != null ? j.incident : null;
    }

    @Override
    public synchronized void signalNoMoreIncidents() {
        fireIncidentFinished = true;
        try {
            checkShutdown();
        } catch (Exception ignored) { }
    }

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

    public synchronized Incident requestWork(int droneId) {
        return requestWork(droneId, 0, 100);
    }

    /** Request work from current zone with remaining agent (multi-zone routing per spec). */
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

    public synchronized void reportArrival(int droneId, Incident incident) {
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone " + incident.getZoneId());
        updateDroneState(droneId, DroneState.ARRIVED.name(), incident.getZoneId());
    }

    public synchronized void reportReturnToBase(int droneId) {
        fireLog("[Scheduler] Drone " + droneId + " returning to base");
        updateDroneState(droneId, DroneState.RETURNING.name(), null);
    }

    public synchronized void addListener(SchedulerListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized String getDroneState(int droneId) {
        return droneStateById.get(droneId);
    }

    public synchronized Integer getDroneZone(int droneId) {
        return droneZoneById.get(droneId);
    }

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

    public synchronized void onDroneFaultDetected(int droneId, String faultMessage, boolean isHardFault) {
        System.out.println("[Scheduler] Drone " + droneId + " fault detected: " + faultMessage);
        notifyListenersDroneFault(droneId, faultMessage);
        handleFaultyDrone(droneId, isHardFault);
    }

    private void notifyListenersDroneFault(int droneId, String faultMessage) {
        for (SchedulerListener listener : listeners) {
            listener.onDroneFaultDetected(droneId, faultMessage);
        }
    }

    private void notifyListenersDroneState(int droneId, String state) {
        for (SchedulerListener listener : listeners) {
            listener.onDroneStateChanged(droneId, state, null);
        }
    }


}
