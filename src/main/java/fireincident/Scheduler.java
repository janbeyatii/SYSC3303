package fireincident;

import model.Incident;
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
    public enum SchedulerState { IDLE, HAS_PENDING, DRONE_BUSY, WAITING_FOR_INCIDENT }

    private SchedulerState schedulerState = SchedulerState.IDLE;
    private final Map<String, FireState> fireStates = new HashMap<>();
    private final Map<String, IncidentCallback> callbacksByKey = new HashMap<>();
    private final LinkedList<Job> queue = new LinkedList<>();
    private final Map<Integer, Job> inProgressByZone = new HashMap<>();
    private final List<SchedulerListener> listeners = new ArrayList<>();
    private final Map<Integer, String> droneStateById = new HashMap<>();
    private final Map<Integer, Integer> droneZoneById = new HashMap<>();
    private final LinkedList<Integer> idleDrones = new LinkedList<>();
    /** For channel protocol: where to send ASSIGN to each drone (enables multiple drone processes). */
    private final Map<Integer, InetSocketAddress> droneAddresses = new HashMap<>();

    private volatile boolean running = true;
    private final boolean udpEnabled;
    private volatile boolean fireIncidentFinished = false;

    public Scheduler() {
        this(false);
    }

    public Scheduler(boolean udpEnabled) {
        this.udpEnabled = udpEnabled;
        if (!udpEnabled) {
            return;
        }
        try {
            sendSocket = new DatagramSocket();
            receiveSocket = new DatagramSocket(Ports.SCHEDULER);
            System.out.println("[Scheduler] Bound to port " + Ports.SCHEDULER);
            System.out.println("[Scheduler] Waiting for packets...\n");
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
        System.out.println("[Scheduler] Stopped.");
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
                System.out.println("[Scheduler] Unknown packet: " + msg.getType());
        }
    }

    private void handleIncidentReport(UDPMessage msg) throws IOException {
        Incident incident = new Incident(
                msg.getField(0),
                Integer.parseInt(msg.getField(1)),
                msg.getField(2),
                Integer.parseInt(msg.getField(3))
        );
        queue.addLast(new Job(incident));
        fireStates.put(incident.getKey(), FireState.PENDING);
        schedulerState = SchedulerState.HAS_PENDING;
        fireLog("[Scheduler] Queued incident: " + incident);
        fireIncidentQueued(incident);
        dispatchPending();
    }

    private void handleDroneArrived(UDPMessage msg) {
        int droneId = Integer.parseInt(msg.getField(0));
        int zoneId = Integer.parseInt(msg.getField(2));
        String key = msg.getField(1) + "|" + msg.getField(2) + "|" + msg.getField(3);
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone for " + key);
        updateDroneState(droneId, "ARRIVED", zoneId);
    }

    private void handleDroneDroppedAgent(UDPMessage msg) throws IOException {
        int droneId = Integer.parseInt(msg.getField(0));
        int zoneId = Integer.parseInt(msg.getField(2));
        String key = msg.getField(1) + "|" + msg.getField(2) + "|" + msg.getField(3);
        Job job = inProgressByZone.remove(zoneId);
        fireLog("[Scheduler] Drone " + droneId + " finished at: " + key);
        if (job != null) {
            fireStates.put(job.incident.getKey(), FireState.COMPLETED);
            fireIncidentCompleted(droneId, job.incident);
            sendToPort(UDPMessage.incidentCompleted(job.incident), Ports.FIRE_IS);
        }
        schedulerState = queue.isEmpty() ? SchedulerState.IDLE : SchedulerState.HAS_PENDING;
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
        Integer zoneId = droneZoneById.get(droneId); // Retrieve the last known zone of the drone
        if (zoneId == null) {
            zoneId = 0; // Default to base zone (e.g., 0) if no zone is known
        }
        System.out.println("[Scheduler] Drone " + droneId + " is now idle at zone " + zoneId);
        if (!idleDrones.contains(droneId)) {
            idleDrones.add(droneId);
        }
        updateDroneState(droneId, DroneState.IDLE.name(), zoneId); // Update the drone's state and zone
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
                System.out.println("[Scheduler] Unknown channel packet: " + type);
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
        updateDroneState(droneId, DroneState.IDLE.name(), droneZoneById.get(droneId));
        Job job = queue.isEmpty() ? null : queue.removeFirst();
        if (job != null) {
            idleDrones.remove((Integer) droneId);
            inProgressByZone.put(job.incident.getZoneId(), job);
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
        updateDroneState(droneId, "ARRIVED", incident.getZoneId());
        sendToAddress(DronePacketBuilder.ack(), fromAddr, fromPort);
    }

    private void handleChannelReportCompletion(String[] parts, InetAddress fromAddr, int fromPort) throws IOException {
        if (parts.length < 6) return;
        int droneId = Integer.parseInt(parts[1].trim());
        recordDroneAddress(droneId, fromAddr, fromPort);
        Incident incident = new Incident(parts[2].trim(), Integer.parseInt(parts[3].trim()), parts[4].trim(), Integer.parseInt(parts[5].trim()));
        Job job = inProgressByZone.remove(incident.getZoneId());
        fireLog("[Scheduler] Drone " + droneId + " finished at: " + incident.getKey());
        if (job != null) {
            fireStates.put(job.incident.getKey(), FireState.COMPLETED);
            fireIncidentCompleted(droneId, job.incident);
            sendToPort(UDPMessage.incidentCompleted(job.incident), Ports.FIRE_IS);
        }
        schedulerState = queue.isEmpty() ? SchedulerState.IDLE : SchedulerState.HAS_PENDING;
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
        updateDroneState(droneId, state, zoneId);
        if (state.equals(DroneState.IDLE.name()) && !idleDrones.contains(droneId)) {
            idleDrones.add(droneId);
        }
        dispatchPending();
        checkShutdown();
        sendToAddress(DronePacketBuilder.ack(), fromAddr, fromPort);
    }

    private void handleChannelPeek(InetAddress fromAddr, int fromPort) throws IOException {
        Incident next = queue.isEmpty() ? null : queue.getFirst().incident;
        String line = next != null
                ? "PEEK_RESP|" + next.getTime() + "|" + next.getZoneId() + "|" + next.getEventType() + "|" + next.getSeverity()
                : "PEEK_RESP";
        sendToAddress(line.getBytes(StandardCharsets.UTF_8), fromAddr, fromPort);
    }

    private void checkShutdown() {
        if (!udpEnabled || sendSocket == null) return;
        if (!fireIncidentFinished || !queue.isEmpty() || !inProgressByZone.isEmpty()) return;
        if (!allDronesIdle()) return;
        fireLog("[Scheduler] All fires out, all drones at base. Shutting down.");
        for (SchedulerListener l : listeners) l.onSimulationComplete();
        sendShutdownToDrones();
        running = false;
    }

    private boolean allDronesIdle() {
        if (droneStateById.isEmpty()) return false;
        for (String state : droneStateById.values()) {
            if (state == null || !DroneState.IDLE.name().equals(state)) return false;
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

    /** Returns the droneId that was assigned work, or null if none. Only assigns to push drones (no address). */
    private Integer dispatchPending() throws IOException {
        System.out.println("[Scheduler] Dispatching pending incidents...");
        Integer lastAssigned = null;
        while (!queue.isEmpty() && !idleDrones.isEmpty()) {
            Job job = queue.getFirst();
            int droneId = selectBestPushDrone(job.incident.getZoneId());
            if (droneId == -1) {
                break;
            }
            queue.removeFirst();
            idleDrones.remove((Integer) droneId);
            inProgressByZone.put(job.incident.getZoneId(), job);
            fireStates.put(job.incident.getKey(), FireState.ASSIGNED);
            schedulerState = SchedulerState.DRONE_BUSY;

            System.out.println("[Scheduler] Dispatching Drone " + droneId + " to Incident: " + job.incident);
            sendToPort(UDPMessage.dispatchDrone(droneId, job.incident), Ports.DRONE_SS);
            fireIncidentDispatched(droneId, job.incident);
            lastAssigned = droneId;
        }
        return lastAssigned;
    }

    private int selectBestPushDrone(int zoneId) {
        int best = -1;
        double minDist = Double.MAX_VALUE;
        for (int droneId : idleDrones) {
            if (droneAddresses.containsKey(droneId)) continue;
            Integer zone = droneZoneById.get(droneId);
            if (zone == null) zone = 0;
            double d = Math.abs(zone - zoneId);
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

    private int selectBestDrone(int zoneId) {
        System.out.println("[Scheduler] Selecting best drone for zone: " + zoneId);
        int bestDrone = -1;
        double minDistance = Double.MAX_VALUE;

        for (int droneId : idleDrones) {
            Integer droneZone = droneZoneById.get(droneId);
            if (droneZone == null) droneZone = 0;
            double distance = Math.abs(droneZone - zoneId);
            System.out.println("[Scheduler] Drone " + droneId + " at zone " + droneZone + " with distance " + distance);
            if (distance < minDistance) {
                minDistance = distance;
                bestDrone = droneId;
            }
        }
        System.out.println("[Scheduler] Best drone selected: " + bestDrone);
        return bestDrone;
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
        if (state != null) droneStateById.put(droneId, state);
        if (zoneId != null) droneZoneById.put(droneId, zoneId);
        fireDroneStateChanged(droneId, state, zoneId);
    }

    public synchronized int getQueueSize() {
        return queue.size();
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
        return queue.isEmpty() ? null : queue.getFirst().incident;
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
        queue.addLast(new Job(incident));
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
        updateDroneState(droneId, DroneState.IDLE.name(), null);
        while (queue.isEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Job job = queue.removeFirst();
        inProgressByZone.put(job.incident.getZoneId(), job);
        fireStates.put(job.incident.getKey(), FireState.ASSIGNED);
        schedulerState = SchedulerState.DRONE_BUSY;
        fireLog("[Scheduler] Dispatched incident to Drone " + droneId + ": " + job.incident);
        fireIncidentDispatched(droneId, job.incident);
        return job.incident;
    }

    public synchronized void reportCompletion(int droneId, Incident incident) {
        inProgressByZone.remove(incident.getZoneId());
        fireStates.put(incident.getKey(), FireState.COMPLETED);
        fireLog("[Scheduler] Completion from Drone " + droneId + ": " + incident);
        fireIncidentCompleted(droneId, incident);
        IncidentCallback callback = callbacksByKey.remove(incident.getKey());
        if (callback != null) {
            callback.onIncidentCompleted(incident);
        }
        schedulerState = queue.isEmpty() ? SchedulerState.IDLE : SchedulerState.HAS_PENDING;
        notifyAll();
    }

    public synchronized void reportArrival(int droneId, Incident incident) {
        fireLog("[Scheduler] Drone " + droneId + " arrived at zone " + incident.getZoneId());
        updateDroneState(droneId, "ARRIVED", incident.getZoneId());
    }

    public synchronized void reportReturnToBase(int droneId) {
        fireLog("[Scheduler] Drone " + droneId + " returned to base");
        updateDroneState(droneId, DroneState.IDLE.name(), null);
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
}
